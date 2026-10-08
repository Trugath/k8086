package com.trugath.k8086

import com.trugath.k8086.chipset.MicrosoftSerialMouse
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.chipset.SerialMouseAdapter
import com.trugath.k8086.chipset.Uart8250
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MicrosoftSerialMouseTest {
    @Test
    fun encodeSyncBitAndButtons() {
        val p = MicrosoftSerialMouse.encode(0, 0, MicrosoftSerialMouse.BUTTON_LEFT or MicrosoftSerialMouse.BUTTON_RIGHT)
        assertEquals(0x40 or 0x20 or 0x10, p[0])
        assertEquals(0, p[1])
        assertEquals(0, p[2])
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val cases = listOf(
            Triple(0, 0, 0),
            Triple(1, -1, MicrosoftSerialMouse.BUTTON_LEFT),
            Triple(-5, 10, MicrosoftSerialMouse.BUTTON_RIGHT),
            Triple(127, -128, MicrosoftSerialMouse.BUTTON_LEFT or MicrosoftSerialMouse.BUTTON_RIGHT),
            Triple(-128, 127, 0),
        )
        for ((dx, dy, buttons) in cases) {
            val p = MicrosoftSerialMouse.encode(dx, dy, buttons)
            val d = MicrosoftSerialMouse.decode(p[0], p[1], p[2])
            assertNotNull(d)
            assertEquals(dx, d!!.dx, "dx for $dx,$dy,$buttons")
            assertEquals(dy, d.dy, "dy for $dx,$dy,$buttons")
            assertEquals(buttons, d.buttons, "buttons for $dx,$dy,$buttons")
        }
    }

    @Test
    fun decodeRejectsMissingSync() {
        assertNull(MicrosoftSerialMouse.decode(0x00, 0x01, 0x02))
    }

    @Test
    fun clampExtremes() {
        val p = MicrosoftSerialMouse.encode(200, -300, 0)
        val d = MicrosoftSerialMouse.decode(p[0], p[1], p[2])!!
        assertEquals(127, d.dx)
        assertEquals(-128, d.dy)
    }

    @Test
    fun adapterFeedsUartFifoAndIrq() {
        val pic = Pic8259()
        val uart = Uart8250(pic)
        uart.ioWriteByte(0x3F9, Uart8250.IER_RDA)
        val mouse = SerialMouseAdapter(uart::enqueueRx)
        mouse.sendEvent(3, -2, MicrosoftSerialMouse.BUTTON_LEFT)
        assertTrue((pic.requestRegister() and (1 shl 4)) != 0)
        assertEquals(3, uart.rxFifoSize())
        val b0 = uart.ioReadByte(0x3F8)
        val b1 = uart.ioReadByte(0x3F8)
        val b2 = uart.ioReadByte(0x3F8)
        val d = MicrosoftSerialMouse.decode(b0, b1, b2)!!
        assertEquals(3, d.dx)
        assertEquals(-2, d.dy)
        assertEquals(MicrosoftSerialMouse.BUTTON_LEFT, d.buttons)
        assertEquals(0, uart.rxFifoSize())
    }
}
