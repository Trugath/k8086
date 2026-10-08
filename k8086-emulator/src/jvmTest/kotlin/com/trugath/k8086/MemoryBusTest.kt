package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import com.trugath.k8086.api.MemoryDevice
import com.trugath.k8086.api.MemoryRegion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryBusTest {
    @Test
    fun ramFallthroughReadWrite() {
        val bus = MemoryBus(0x10000)
        bus.write8(0x1234, 0xAB)
        assertEquals(0xAB, bus.read8(0x1234))
    }

    @Test
    fun romIsWriteProtected() {
        val bus = MemoryBus(0x10000)
        val rom = ByteArray(16) { 0x55 }
        bus.map(MemoryRegion.Rom(0xF000, 16, rom), "test-rom")
        assertEquals(0x55, bus.read8(0xF000))
        bus.write8(0xF000, 0x00)
        assertEquals(0x55, bus.read8(0xF000), "ROM writes must be ignored")
        assertTrue(bus.isRom(0xF000))
    }
}

class MemoryBusMmioTest {
    @Test
    fun mmioOverlay() {
        val bus = MemoryBus(RAM_SIZE)
        var stored = 0
        val dev = object : MemoryDevice {
            override fun memReadByte(offset: Int): Int = stored
            override fun memWriteByte(offset: Int, value: Int) {
                stored = value and 0xFF
            }
        }
        bus.map(MemoryRegion.Mmio(0xA0000, 16, dev), "vga")
        bus.write8(0xA0000, 0x3C)
        assertEquals(0x3C, bus.read8(0xA0000))
        assertEquals(0x3C, stored)
    }

    @Test
    fun overlappingRomsConflict() {
        val bus = MemoryBus(RAM_SIZE)
        bus.map(MemoryRegion.Rom(0xC0000, 0x2000, ByteArray(0x2000)), "a")
        assertThrows(IllegalStateException::class.java) {
            bus.map(MemoryRegion.Rom(0xC1000, 0x2000, ByteArray(0x2000)), "b")
        }
    }
}
