package com.trugath.k8086.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolDtoTest {
    @Test
    fun consoleFrameEqualityUsesPixels() {
        val a = ConsoleFrame(2, 1, intArrayOf(0xFF0000, 0x00FF00))
        val b = ConsoleFrame(2, 1, intArrayOf(0xFF0000, 0x00FF00))
        val c = ConsoleFrame(2, 1, intArrayOf(0xFF0000, 0x0000FF))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun consoleFrameIncludesCompositeMetadata() {
        val frame = ConsoleFrame(
            width = 320,
            height = 200,
            argb = IntArray(320 * 200),
            graphicsMode = true,
            compositeMode = CompositeKind.ON,
            compositeActive = true,
        )
        assertTrue(frame.graphicsMode)
        assertEquals(CompositeKind.ON, frame.compositeMode)
        assertTrue(frame.compositeActive)
    }

    @Test
    fun vmIdToStringIsValue() {
        val id = VmId("abc-123")
        assertEquals("abc-123", id.toString())
        assertEquals("abc-123", id.value)
    }

    @Test
    fun networkStubIsEmpty() {
        assertTrue(NetworkApiStub.listNetworks().isEmpty())
        assertNull(NetworkApiStub.getNetwork("n"))
    }

    @Test
    fun vmSummaryAndMetricsDefaults() {
        val id = VmId("x")
        val s = VmSummary(id, "n", VmState.Stopped)
        assertEquals(null, s.errorMessage)
        val m = VmMetrics(id, VmState.Running)
        assertEquals(0L, m.instructionCount)
        assertEquals(0L, m.uptimeMs)
        assertTrue(m.floppyPaths.isEmpty())
    }

    @Test
    fun cpuDebugStateAndMemoryDumpHoldValues() {
        val state = CpuDebugState(
            ax = 1, bx = 2, cx = 3, dx = 4,
            sp = 0xFFFE, bp = 0, si = 0, di = 0,
            es = 0, cs = 0xF000, ss = 0, ds = 0,
            ip = 0xFFF0, flags = 0xF002,
            linearCsIp = 0xFFFF0, halted = false, instructionCount = 42,
            nextBytes = listOf(0xEA, 0x5B, 0xE0, 0x00, 0xF0), nextLength = 5,
        )
        assertEquals(0xF000, state.cs)
        assertEquals(5, state.nextLength)
        assertEquals(listOf(0xEA, 0x5B, 0xE0, 0x00, 0xF0), state.nextBytes)
        val dump = MemoryDump(0x100, listOf(0x90, 0x90))
        assertEquals(0x100, dump.address)
        assertEquals(2, dump.bytes.size)
    }
}
