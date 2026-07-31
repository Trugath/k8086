package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import com.trugath.k8086.api.IoDevice
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IoBusCoverageTest {
    @Test
    fun unmapOwnerAndOutOfRangeLookups() {
        val bus = IoBus()
        val dev = object : IoDevice {
            override fun ioReadByte(port: Int) = 0x42
            override fun ioWriteByte(port: Int, value: Int) {}
        }
        bus.map(dev, listOf(0x300, 0x301), owner = "card-x")
        assertEquals("card-x", bus.ownerFor(0x300))
        assertEquals(dev, bus.deviceFor(0x300))

        bus.unmap(listOf(0x300))
        assertNull(bus.deviceFor(0x300))
        assertNull(bus.ownerFor(0x300))
        assertEquals(dev, bus.deviceFor(0x301))

        assertNull(bus.deviceFor(-1))
        assertNull(bus.ownerFor(0x10000))
        assertThrows(IllegalArgumentException::class.java) {
            bus.map(dev, listOf(0x10000), owner = "bad")
        }
    }

    @Test
    fun sameDeviceMayRemapSamePorts() {
        val bus = IoBus()
        val dev = object : IoDevice {
            override fun ioReadByte(port: Int) = 0
            override fun ioWriteByte(port: Int, value: Int) {}
        }
        bus.map(dev, 0x310)
        bus.map(dev, 0x310) // idempotent
        assertEquals(dev, bus.deviceFor(0x310))
    }
}
