package com.trugath.k8086

/**
 * Pace guest CPU time against wall clock at a configured MHz rate.
 *
 * XT-class defaults to ~4.77 MHz; 80286 AT-class uses a higher rate (see [CPU_HZ_80286]).
 * Without pacing, headless / audio-disabled runs free-run as fast as the host CPU
 * allows; AdLib/PC-speaker [SourceDataLine.write] accidentally provided pacing
 * when audio was enabled.
 */
internal class RealtimePacer(
    private val cpuHz: Double = CPU_HZ_8088,
    private val sliceCycles: Int = SLICE_CYCLES,
) {
    private var cycles = 0L
    private var anchorNs = System.nanoTime()

    fun reset() {
        cycles = 0
        anchorNs = System.nanoTime()
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
        var ahead = targetNs - (System.nanoTime() - anchorNs)
        if (ahead < -CATCH_UP_NS) {
            // Far behind (GC pause, host load): resync rather than run forever at warp.
            reset()
            return
        }
        while (ahead > MIN_SLEEP_NS && keepGoing()) {
            val chunk = minOf(ahead, MAX_SLEEP_CHUNK_NS)
            val ms = chunk / 1_000_000L
            val ns = (chunk % 1_000_000L).toInt()
            try {
                Thread.sleep(ms, ns)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            ahead = targetNs - (System.nanoTime() - anchorNs)
        }

        // Limit long-term drift from sleep / timer granularity.
        if (cycles > (cpuHz / 2).toLong()) {
            reset()
        }
    }

    companion object {
        /** IBM PC/XT clock. */
        const val CPU_HZ_8088 = 4_772_727.0
        /** Typical early AT 80286 rate — Wolf3D is a 286-class title. */
        const val CPU_HZ_80286 = 8_000_000.0
        @Deprecated("Use CPU_HZ_8088", ReplaceWith("CPU_HZ_8088"))
        const val CPU_HZ = CPU_HZ_8088
        /** ~4 ms of guest time between pace checks. */
        const val SLICE_CYCLES = 20_000
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MIN_SLEEP_NS = 500_000L // 0.5 ms
        private const val MAX_SLEEP_CHUNK_NS = 2_000_000L // 2 ms
        private const val CATCH_UP_NS = 200_000_000L // 200 ms
    }
}
