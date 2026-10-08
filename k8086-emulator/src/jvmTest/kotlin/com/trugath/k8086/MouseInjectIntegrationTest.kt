package com.trugath.k8086

import com.trugath.k8086.chipset.MicrosoftSerialMouse
import com.trugath.k8086.chipset.MouseInjectPort
import com.trugath.k8086.chipset.Uart8250
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MouseInjectIntegrationTest {
    @Test
    fun mouseInjectPortFeedsUartFifo() {
        val pic = com.trugath.k8086.chipset.Pic8259()
        val uart = Uart8250(pic)
        uart.ioWriteByte(0x3F9, Uart8250.IER_RDA)
        val port = MouseInjectPort { dx, dy, buttons ->
            for (b in MicrosoftSerialMouse.encode(dx, dy, buttons)) {
                uart.enqueueRx(b)
            }
        }
        port.ioWriteByte(MouseInjectPort.PORT, 0x01)
        port.ioWriteByte(MouseInjectPort.PORT, 10)
        port.ioWriteByte(MouseInjectPort.PORT, 5)
        assertEquals(3, uart.rxFifoSize())
        val d = MicrosoftSerialMouse.decode(
            uart.ioReadByte(0x3F8),
            uart.ioReadByte(0x3F8),
            uart.ioReadByte(0x3F8),
        )!!
        assertEquals(10, d.dx)
        assertEquals(5, d.dy)
        assertEquals(1, d.buttons)
    }
}
