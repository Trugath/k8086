package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.MemoryRegion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IsaHostImplCoverageTest {
    @Test
    fun mapsUnmapsIoMemoryIrqDmaAndCpuAccess() {
        val machine = TestAssets.machine()
        val host = IsaHostImpl(machine, "test-card")

        val latch = intArrayOf(0)
        val io = object : IoDevice {
            override fun ioReadByte(port: Int) = latch[0]
            override fun ioWriteByte(port: Int, value: Int) {
                latch[0] = value and 0xFF
            }
        }
        host.mapIo(io, 0x320..0x321)
        assertEquals("test-card", machine.ioBus.ownerFor(0x320))
        machine.ioBus.deviceFor(0x320)!!.ioWriteByte(0x320, 0x7E)
        assertEquals(0x7E, machine.ioBus.deviceFor(0x320)!!.ioReadByte(0x320))
        host.unmapIo(0x320..0x321)
        assertNull(machine.ioBus.deviceFor(0x320))

        val ram = ByteArray(16) { 0 }
        host.mapMemory(MemoryRegion.Ram(0xD8000, 16, ram))
        host.cpuWrite8(0xD8000, 0xAB)
        assertEquals(0xAB, host.cpuRead8(0xD8000))

        host.raiseIrq(5)
        assertTrue((machine.pic.requestRegister() and (1 shl 5)) != 0)
        host.lowerIrq(5)
        assertEquals(0, machine.pic.requestRegister() and (1 shl 5))

        val dma = host.claimDmaChannel(1)
        assertEquals(1, dma.channel)
        assertThrows(IllegalStateException::class.java) {
            host.claimDmaChannel(1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            machine.claimDmaChannel(9, "bad")
        }

        var ticks = 0
        host.addTickable { ticks += it }
        machine.tickDevices(12)
        assertEquals(12, ticks)
    }

    @Test
    fun mapOptionRomRejectsUnalignedBase() {
        val machine = TestAssets.machine()
        val host = IsaHostImpl(machine, "rom")
        val rom = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0xCB.toByte()) + ByteArray(508)
        assertThrows(IllegalArgumentException::class.java) {
            host.mapOptionRom(rom, 0xC8001)
        }
    }

    @Test
    fun extendConventionalMemoryRaisesEndAndEnablesAccess() {
        TestAssets.assumeRomsPresent()
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                realtime = false,
                motherboard = com.trugath.k8086.config.MotherboardConfig(baseMemoryKb = 256),
            ),
        )
        val host = IsaHostImpl(machine, "mem-exp")
        assertEquals(256 * 1024, host.conventionalMemoryEnd())

        // Hole floats as 0xFF until extended.
        assertEquals(0xFF, host.cpuRead8(0x40000))
        host.cpuWrite8(0x40000, 0x5A)
        assertEquals(0xFF, host.cpuRead8(0x40000))

        host.extendConventionalMemory(0xA0000)
        assertEquals(0xA0000, host.conventionalMemoryEnd())
        host.cpuWrite8(0x40000, 0x5A)
        assertEquals(0x5A, host.cpuRead8(0x40000))
        host.cpuWrite8(0x9FFFE, 0xA5)
        assertEquals(0xA5, host.cpuRead8(0x9FFFE))

        assertThrows(IllegalArgumentException::class.java) {
            host.extendConventionalMemory(0x80000) // cannot shrink
        }
        assertThrows(IllegalArgumentException::class.java) {
            host.extendConventionalMemory(0xA0001) // not paragraph-aligned / over A0000
        }
    }
}
