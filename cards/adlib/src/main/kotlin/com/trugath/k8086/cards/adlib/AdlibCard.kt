package com.trugath.k8086.cards.adlib

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind
import java.awt.GraphicsEnvironment
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

class AdlibCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.adlib",
        name = "AdLib / OPL2",
        description = "Classic AdLib ports with timer detection and OPL2 2-op FM audio.",
        category = "Sound",
        fields = listOf(
            ConfigField(
                "port", "I/O base", ConfigFieldType.HEX_INT, "0x388",
                "Index/data pair at base and base+1", affectsResources = true,
            ),
            ConfigField(
                "audio", "Host audio", ConfigFieldType.BOOL, "true",
                "Enable OPL2 FM mixer output",
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["port"]) ?: 0x388
        val audio = when (config["audio"]?.lowercase()) {
            "false", "0", "off", "no" -> false
            "true", "1", "on", "yes" -> true
            else -> !GraphicsEnvironment.isHeadless()
        }
        return AdlibCard(base, enableAudio = audio)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["port"]) ?: 0x388
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, base, base + 1, descriptor().id),
        )
    }
}

/**
 * AdLib / OPL2-compatible card at 0x388/0x389.
 *
 * Timer/status behaviour for detection plus a self-contained 2-op FM core
 * ([Opl2Core]) for music and effects.
 *
 * Config: `port=0x388`, `audio=true|false` (default: on when a display is present)
 */
class AdlibCard(
    private val portBase: Int,
    private val enableAudio: Boolean = false,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.adlib"
    override val name = "AdLib OPL2 (0x${portBase.toString(16)})"

    private val opl = Opl2Core(SAMPLE_RATE)
    private var index = 0
    private var status = 0x00
    private var timer1 = 0
    private var timer2 = 0
    private var timerCtrl = 0

    private val line: SourceDataLine? = if (enableAudio) openLine() else null
    private var sampleAccum = 0.0
    private val buf = ByteArray(256)
    private var bufPos = 0

    private var attachedHost: IsaHost? = null

    /** Exposed for unit tests. */
    internal val core: Opl2Core get() = opl

    override fun attach(host: IsaHost) {
        attachedHost = host
        val device = object : IoDevice {
            override fun ioReadByte(port: Int): Int = when (port - portBase) {
                0 -> status and 0xFF
                else -> 0xFF
            }

            override fun ioWriteByte(port: Int, value: Int) {
                val v = value and 0xFF
                when (port - portBase) {
                    0 -> index = v
                    1 -> writeData(v)
                }
            }
        }
        host.mapIo(device, portBase..(portBase + 1))
        host.addTickable { cycles ->
            tickTimers(cycles)
            tickAudio(cycles)
        }
    }

    override fun detach() {
        attachedHost = null
        // Do not drain() — can block indefinitely if the line is stuck.
        try {
            line?.stop()
            line?.close()
        } catch (_: Exception) {
        }
    }

    private fun writeData(value: Int) {
        when (index) {
            0x02 -> timer1 = value
            0x03 -> timer2 = value
            0x04 -> {
                timerCtrl = value
                if ((value and 0x80) != 0) status = status and 0x1F
            }
            else -> opl.writeReg(index, value)
        }
        // Keep timer regs visible in the OPL register file too.
        if (index in 0x02..0x04) opl.regs[index] = value and 0xFF
    }

    private var cycleAccum = 0
    private fun tickTimers(cpuCycles: Int) {
        cycleAccum += cpuCycles
        while (cycleAccum >= 80) {
            cycleAccum -= 80
            if ((timerCtrl and 0x01) != 0 && (timerCtrl and 0x40) == 0) {
                timer1 = (timer1 + 1) and 0xFF
                if (timer1 == 0) status = status or 0xC0
            }
            if ((timerCtrl and 0x02) != 0 && (timerCtrl and 0x20) == 0) {
                timer2 = (timer2 + 1) and 0xFF
                if (timer2 == 0) status = status or 0xA0
            }
        }
    }

    private fun tickAudio(cpuCycles: Int) {
        val out = line ?: return
        // Turbo must not touch the audio device — write() blocks and paces the CPU.
        if (attachedHost?.isAudioOutputSuspended() == true) return
        val muted = attachedHost?.isAudioMuted() == true
        val cpuHz = attachedHost?.realtimeCpuHz()?.takeIf { it > 0.0 } ?: CPU_HZ_DEFAULT
        sampleAccum += cpuCycles * (SAMPLE_RATE / cpuHz)
        var n = sampleAccum.toInt()
        if (n <= 0) return
        sampleAccum -= n
        // Cap work per tick — a huge REP cycle dump must not starve the CPU loop.
        if (n > 512) {
            sampleAccum += (n - 512)
            n = 512
        }
        while (n-- > 0) {
            val sample = if (muted) 0 else opl.renderSample8()
            // Unsigned 8-bit: midpoint 0x80 is silence. Always write so mute does not underrun.
            buf[bufPos++] = (sample + 128).toByte()
            if (bufPos == buf.size) {
                // Never block the emu thread — overproducing (wrong Hz) or a full
                // buffer would freeze VGA presents while PCM keeps draining.
                val free = out.available()
                if (free >= buf.size) {
                    out.write(buf, 0, buf.size)
                }
                bufPos = 0
            }
        }
    }

    /** Test helper: true when any channel has key-on. */
    fun anyKeyOn(): Boolean = opl.anyKeyOn()

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
        /** Fallback when host does not report [IsaHost.realtimeCpuHz]. */
        const val CPU_HZ_DEFAULT = 4_772_727.0
        @Deprecated("Use CPU_HZ_DEFAULT", ReplaceWith("CPU_HZ_DEFAULT"))
        const val CPU_HZ = CPU_HZ_DEFAULT
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
