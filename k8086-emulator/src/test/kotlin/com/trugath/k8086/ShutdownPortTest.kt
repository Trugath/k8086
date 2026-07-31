package com.trugath.k8086

import com.trugath.k8086.chipset.ShutdownPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShutdownPortTest {
    @Test
    fun triggersOnShutdownSequence() {
        var fired = 0
        val port = ShutdownPort { fired++ }
        for (ch in "Shutdown") {
            assertFalse(port.wasTriggered())
            port.ioWriteByte(ShutdownPort.PORT, ch.code)
        }
        assertTrue(port.wasTriggered())
        assertEquals(1, fired)
        // Further writes are ignored
        port.ioWriteByte(ShutdownPort.PORT, 'S'.code)
        assertEquals(1, fired)
    }

    @Test
    fun resetsOnMismatchButAllowsRestart() {
        var fired = 0
        val port = ShutdownPort { fired++ }
        port.ioWriteByte(ShutdownPort.PORT, 'S'.code)
        port.ioWriteByte(ShutdownPort.PORT, 'x'.code)
        for (ch in "Shutdown") {
            port.ioWriteByte(ShutdownPort.PORT, ch.code)
        }
        assertTrue(port.wasTriggered())
        assertEquals(1, fired)
    }
}
