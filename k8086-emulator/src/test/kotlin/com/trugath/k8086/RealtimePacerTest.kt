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
        pacer.addCycles(200_000) {
            calls++
            calls < 3
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue(calls >= 3, "keepGoing should be polled during sleep")
        assertTrue(elapsedMs < 30.0, "aborted sleep should not wait full guest slice")
    }
}
