package com.trugath.k8086

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealtimePacerTest {
    @Test
    fun sleepsWhenAheadOfWallClock() {
        val pacer = RealtimePacer(sliceCycles = 5_000)
        val start = System.nanoTime()
        // ~10 ms of guest time at 4.77 MHz
        pacer.addCycles(50_000)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue(elapsedMs >= 6.0, "expected pacing sleep, elapsed=${elapsedMs}ms")
    }

    @Test
    fun keepGoingAbortsSleep() {
        val pacer = RealtimePacer(sliceCycles = 5_000)
        var calls = 0
        val start = System.nanoTime()
        // One sleep chunk then abort. Two chunks (~30 ms on Windows' coarse
        // timer) sat on the old <30 ms bound and flaked.
        pacer.addCycles(200_000) {
            calls++
            calls < 2
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue(calls >= 2, "keepGoing should be polled during sleep")
        assertTrue(elapsedMs < 30.0, "aborted sleep should not wait full guest slice")
    }
}
