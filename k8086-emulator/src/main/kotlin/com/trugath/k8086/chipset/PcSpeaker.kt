package com.trugath.k8086.chipset

import java.awt.GraphicsEnvironment
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * PC speaker: audible when PPI port B bit 1 (speaker data) is set and PIT counter 2
 * output is high — the same AND used on the real XT. Host audio is optional; unit
 * tests use [isSounding] without opening a sound device.
 *
 * Output is unsigned 8-bit PCM: silence is the midpoint (0x80). Skipping [SourceDataLine]
 * writes while muted underruns the device and clicks, so mute still feeds silence.
 *
 * Periods below the host Nyquist limit (ultrasonic PIT2 rates games often leave
 * programmed during rests) are rendered as silence so they do not alias into hiss.
 */
class PcSpeaker(
    private val pit: Pit8253,
    private val ppi: Ppi8255,
    enableAudio: Boolean = !GraphicsEnvironment.isHeadless(),
) {
    private val line: SourceDataLine? = if (enableAudio) openLine() else null
    private var sampleAccum = 0.0
    private val buf = ByteArray(256)
    private var bufPos = 0

    /** When true, feed silence instead of the speaker waveform (VM unfocused / host mute). */
    @Volatile
    var muted: Boolean = false

    /**
     * When true, skip all device writes (turbo). [SourceDataLine.write] blocks when full
     * and would pace the CPU; silence-feeding is only for [muted].
     */
    @Volatile
    var suspended: Boolean = false

    /** True when the speaker data enable and PIT2 square-wave output are both high. */
    fun isSounding(): Boolean =
        speakerAudible() && pit.timer2Output()

    /** Advance audio by [cpuCycles] at [cpuHz] guest clock (default XT). */
    fun tickCpuCycles(cpuCycles: Int, cpuHz: Double = CPU_HZ) {
        val out = line ?: return
        if (suspended) return
        val hz = if (cpuHz > 0.0) cpuHz else CPU_HZ
        sampleAccum += cpuCycles * (SAMPLE_RATE / hz)
        var n = sampleAccum.toInt()
        if (n <= 0) return
        sampleAccum -= n
        while (n-- > 0) {
            buf[bufPos++] = sampleLevel()
            if (bufPos == buf.size) {
                val free = out.available()
                if (free >= buf.size) {
                    out.write(buf, 0, buf.size)
                }
                bufPos = 0
            }
        }
    }

    fun close() {
        try {
            line?.stop()
            line?.close()
        } catch (_: Exception) {
        }
    }

    private fun speakerDataEnabled(): Boolean = (ppi.portBValue() and 0x02) != 0

    private fun speakerGateEnabled(): Boolean = (ppi.portBValue() and 0x01) != 0

    /**
     * Guest wants an audible square wave: data bit on, gate on, and PIT2 period
     * representable at [SAMPLE_RATE] (otherwise the square wave aliases to noise).
     */
    private fun speakerAudible(): Boolean {
        if (!speakerDataEnabled() || !speakerGateEnabled()) return false
        val reload = pit.reloadValue(2)
        val period = if (reload == 0) 0x10000 else reload
        return period >= MIN_PIT_PERIOD
    }

    /** Unsigned 8-bit sample for the current speaker / mute state. */
    internal fun sampleLevel(): Byte {
        if (muted || !speakerAudible()) return SILENCE
        return if (pit.timer2Output()) HIGH else LOW
    }

    private fun openLine(): SourceDataLine? = try {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 8, 1, false, false)
        val info = javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) null
        else AudioSystem.getLine(info).also {
            (it as SourceDataLine).open(format, 4096)
            it.start()
        } as SourceDataLine
    } catch (_: Exception) {
        null
    }

    companion object {
        const val SAMPLE_RATE = 22050.0
        const val CPU_HZ = 4_772_727.0
        /** PIT input clock (Hz). */
        const val PIT_HZ = 1_193_182.0
        /**
         * Minimum mode-3 reload whose fundamental is ≤ Nyquist at [SAMPLE_RATE].
         * Smaller divisors (common "off" / rest programming) alias to hiss if rendered.
         */
        val MIN_PIT_PERIOD: Int =
            ((PIT_HZ / (SAMPLE_RATE / 2.0)) + 0.999).toInt().coerceAtLeast(2)
        /** Unsigned PCM midpoint — true digital silence for this line format. */
        const val SILENCE: Byte = 0x80.toByte()
        private const val AMP = 0x30
        val HIGH: Byte = (0x80 + AMP).toByte()
        val LOW: Byte = (0x80 - AMP).toByte()
    }
}
