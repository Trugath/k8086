package com.trugath.k8086

/**
 * Pace guest CPU time against wall clock at the IBM PC/XT ~4.77 MHz rate.
 *
 * Without this, headless / audio-disabled runs free-run as fast as the host CPU
 * allows; AdLib/PC-speaker [SourceDataLine.write] accidentally provided pacing
 * when audio was enabled.
 */
internal class RealtimePacer(
    private val cpuHz: Double = CPU_HZ,
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
        const val CPU_HZ = 4_772_727.0
        /** ~4 ms of guest time between pace checks. */
        const val SLICE_CYCLES = 20_000
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MIN_SLEEP_NS = 500_000L // 0.5 ms
        private const val MAX_SLEEP_CHUNK_NS = 2_000_000L // 2 ms
        private const val CATCH_UP_NS = 200_000_000L // 200 ms
    }
}
