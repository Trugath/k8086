package com.trugath.k8086

import com.trugath.k8086.chipset.MouseInjectPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MouseInjectMachineTest {
    @Test
    fun machineMapsMouseInjectAndFeedsUart() {
        TestAssets.assumeRomsPresent()
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(showVideo = false, enableCom1 = true),
        )
        assertEquals("mouse-inject", machine.ioBus.ownerFor(MouseInjectPort.PORT))
        val uart = machine.uart
        assertNotNull(uart)

        machine.enqueueMouseEvent(10, 5, 1)
        assertEquals(3, uart!!.rxFifoSize())

        val dev = machine.ioBus.deviceFor(MouseInjectPort.PORT)!!
        // drain previous
        repeat(3) { uart.ioReadByte(0x3F8) }
        assertEquals(0, uart.rxFifoSize())

        dev.ioWriteByte(MouseInjectPort.PORT, 0x01)
        dev.ioWriteByte(MouseInjectPort.PORT, 10)
        dev.ioWriteByte(MouseInjectPort.PORT, 5)
        assertEquals(3, uart.rxFifoSize())
    }
}
