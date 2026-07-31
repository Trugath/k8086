package com.trugath.k8086

import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.chipset.Uart8250
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Uart8250Test {
    @Test
    fun divisorLatchRoundTrip() {
        val uart = Uart8250(Pic8259())
        uart.ioWriteByte(0x3FB, 0x80) // DLAB
        uart.ioWriteByte(0x3F8, 0x0C) // lo
        uart.ioWriteByte(0x3F9, 0x00) // hi → 12
        assertEquals(12, uart.divisorLatch())
        assertEquals(0x0C, uart.ioReadByte(0x3F8))
        assertEquals(0x00, uart.ioReadByte(0x3F9))
        uart.ioWriteByte(0x3FB, 0x03) // 8N1, clear DLAB
    }

    @Test
    fun loopbackEchoesThrToRbr() {
        val uart = Uart8250(Pic8259())
        uart.ioWriteByte(0x3FC, 0x10) // LOOP
        uart.ioWriteByte(0x3F8, 0x5A)
        assertTrue((uart.lineStatus() and Uart8250.LSR_DR) != 0)
        assertEquals(0x5A, uart.ioReadByte(0x3F8))
        assertEquals(0, uart.lineStatus() and Uart8250.LSR_DR)
    }

    @Test
    fun enqueueRxRaisesIrq4WhenIerEnabled() {
        val pic = Pic8259()
        val uart = Uart8250(pic)
        uart.ioWriteByte(0x3F9, Uart8250.IER_RDA)
        uart.enqueueRx(0x41)
        assertTrue((pic.requestRegister() and (1 shl 4)) != 0, "IRQ4 pending")
        assertEquals(Uart8250.IIR_RDA, uart.interruptId())
        assertEquals(0x41, uart.ioReadByte(0x3F8))
        assertEquals(Uart8250.IIR_NO_INT, uart.interruptId())
    }

    @Test
    fun scratchAndModemStatusPresent() {
        val uart = Uart8250(Pic8259())
        uart.ioWriteByte(0x3FF, 0xAB)
        assertEquals(0xAB, uart.ioReadByte(0x3FF))
        val msr = uart.ioReadByte(0x3FE)
        assertTrue((msr and Uart8250.MSR_CTS) != 0)
        assertTrue((msr and Uart8250.MSR_DSR) != 0)
        assertTrue((msr and Uart8250.MSR_DCD) != 0)
    }

    @Test
    fun onTransmitFiresForThrWritesOutsideLoopback() {
        val uart = Uart8250(Pic8259())
        val seen = mutableListOf<Int>()
        uart.onTransmit = { seen += it }
        uart.ioWriteByte(0x3F8, 0x41)
        uart.ioWriteByte(0x3F8, 0x42)
        assertEquals(listOf(0x41, 0x42), seen)
    }

    @Test
    fun onTransmitDoesNotFireInLoopback() {
        val uart = Uart8250(Pic8259())
        val seen = mutableListOf<Int>()
        uart.onTransmit = { seen += it }
        uart.ioWriteByte(0x3FC, 0x10) // LOOP
        uart.ioWriteByte(0x3F8, 0x5A)
        assertTrue(seen.isEmpty())
    }
}
