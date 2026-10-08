package com.trugath.k8086

import com.trugath.k8086.chipset.ParallelPort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParallelPortTest {
    private fun strobe(port: ParallelPort, data: Int, base: Int = 0x378) {
        port.ioWriteByte(base, data)
        val ctrl = port.ioReadByte(base + 2)
        port.ioWriteByte(base + 2, ctrl or ParallelPort.CTRL_STROBE)
        port.ioWriteByte(base + 2, ctrl and ParallelPort.CTRL_STROBE.inv())
    }

    @Test
    fun statusReportsReadyAndSelected() {
        val port = ParallelPort()
        val status = port.ioReadByte(0x379)
        assertTrue((status and ParallelPort.STATUS_SELECT) != 0)
        assertTrue((status and ParallelPort.STATUS_BUSY_N) != 0)
        assertTrue((status and ParallelPort.STATUS_PE) == 0)
        assertTrue((status and ParallelPort.STATUS_ERROR_N) != 0)
    }

    @Test
    fun strobeCapturesDataByte() {
        val port = ParallelPort()
        val seen = mutableListOf<Int>()
        port.onByte = { seen += it }
        strobe(port, 0x41)
        strobe(port, 0x42)
        assertEquals(listOf(0x41, 0x42), seen)
        assertEquals(2, port.pendingByteCount())
    }

    @Test
    fun formFeedCompletesJobIncludingFf() {
        val port = ParallelPort()
        strobe(port, 'H'.code)
        strobe(port, 'i'.code)
        strobe(port, ParallelPort.FORM_FEED)
        val jobs = port.drainCompletedJobs()
        assertEquals(1, jobs.size)
        assertArrayEquals(
            byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), 0x0C),
            jobs[0].bytes,
        )
        assertEquals(0, port.pendingByteCount())
    }

    @Test
    fun idleTimeoutCompletesJob() {
        val port = ParallelPort(idleTimeoutMs = 50)
        strobe(port, 'A'.code)
        strobe(port, 'B'.code)
        assertTrue(port.drainCompletedJobs().isEmpty())
        // Drive idle flush with an explicit clock (avoids wall-clock flake).
        port.pollIdle(nowMs = System.currentTimeMillis() + 100)
        val jobs = port.drainCompletedJobs()
        assertEquals(1, jobs.size)
        assertArrayEquals(
            byteArrayOf('A'.code.toByte(), 'B'.code.toByte()),
            jobs[0].bytes,
        )
    }

    @Test
    fun initPulseClearsPendingBuffer() {
        val port = ParallelPort()
        strobe(port, 'X'.code)
        assertEquals(1, port.pendingByteCount())
        // Pulse /INIT low then high (BIOS style).
        port.ioWriteByte(0x37A, 0x00)
        port.ioWriteByte(0x37A, ParallelPort.CTRL_INIT)
        assertEquals(0, port.pendingByteCount())
        assertTrue(port.drainCompletedJobs().isEmpty())
    }

    @Test
    fun dataLatchReadable() {
        val port = ParallelPort()
        port.ioWriteByte(0x378, 0x5A)
        assertEquals(0x5A, port.ioReadByte(0x378))
    }

    @Test
    fun onJobCompletedCallbackFiresOnFormFeed() {
        val port = ParallelPort()
        var called = 0
        port.onJobCompleted = { called++ }
        strobe(port, 'Z'.code)
        strobe(port, ParallelPort.FORM_FEED)
        assertEquals(1, called)
        assertEquals(1, port.completedJobCount())
    }
}
