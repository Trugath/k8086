package com.trugath.k8086

import com.trugath.k8086.platform.HostPlatform

/**
 * Pace guest CPU time against wall clock at a configured MHz rate.
 *
 * XT-class defaults to ~4.77 MHz; 80286 AT-class uses a higher rate (see [CPU_HZ_80286]).
 * Without pacing, headless / audio-disabled runs free-run as fast as the host CPU
 * allows; AdLib/PC-speaker host-line writes accidentally provided pacing
 * when audio was enabled.
 */
internal class RealtimePacer(
    private val cpuHz: Double = CPU_HZ_8088,
    private val sliceCycles: Int = SLICE_CYCLES,
) {
    private var cycles = 0L
    private var anchorNs = HostPlatform.nanoTime()

    fun reset() {
        cycles = 0
        anchorNs = HostPlatform.nanoTime()
    }

    /**
     * Account for [n] guest cycles and sleep while ahead of real time.
     * [keepGoing] is polled during sleeps so cooperative stop stays responsive.
     */
    fun addCycles(n: Int, keepGoing: () -> Boolean = { true }) {
        if (n <= 0) return
        cycles += n.toLong()
        if (cycles < sliceCycles) return

        val targetNs = (cycles * NANOS_PER_SECOND / cpuHz).toLong()
        var ahead = targetNs - (HostPlatform.nanoTime() - anchorNs)
        if (ahead < -CATCH_UP_NS) {
            reset()
            return
        }
        while (ahead > MIN_SLEEP_NS && keepGoing()) {
            val chunk = minOf(ahead, MAX_SLEEP_CHUNK_NS)
            HostPlatform.sleepNanos(chunk)
            ahead = targetNs - (HostPlatform.nanoTime() - anchorNs)
        }

        if (cycles > (cpuHz / 2).toLong()) {
            reset()
        }
    }

    companion object {
        const val CPU_HZ_8088 = 4_772_727.0
        const val CPU_HZ_80286 = 8_000_000.0
        const val CPU_HZ_80286_10 = 10_000_000.0
        const val CPU_HZ_80386 = 16_000_000.0
        @Deprecated("Use CPU_HZ_8088", ReplaceWith("CPU_HZ_8088"))
        const val CPU_HZ = CPU_HZ_8088
        const val SLICE_CYCLES = 20_000
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MIN_SLEEP_NS = 500_000L
        private const val MAX_SLEEP_CHUNK_NS = 2_000_000L
        private const val CATCH_UP_NS = 200_000_000L
    }
}
