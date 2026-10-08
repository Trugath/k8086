package com.trugath.k8086

import com.trugath.k8086.chipset.Dma8237
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.REG_AH
import com.trugath.k8086.cpu.REG_AL
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CH
import com.trugath.k8086.cpu.REG_CL
import com.trugath.k8086.cpu.REG_DH
import com.trugath.k8086.cpu.REG_DL
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.XT_HARD_DISK_BYTES
import com.trugath.k8086.cpu.*
import com.trugath.k8086.storage.Wd1003
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

class Wd1003Test {
    @Test
    fun statusReadyAfterResetAndTestReadyRaisesIrq() {
        val cpu = Emulator8086()
        val pic = Pic8259()
        val dma = Dma8237(cpu)
        val hdc = Wd1003(cpu, pic, dma)
        val tmp = File.createTempFile("wd1003", ".img")
        tmp.deleteOnExit()
        RandomAccessFile(tmp, "rw").use { it.setLength(4L * 17 * 5 * 512) }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)
        hdc.attachImage(0, cpu.diskImage(0x80))

        hdc.ioWriteByte(0x321, 0)
        assertTrue((hdc.statusRegister() and Wd1003.STA_READY) != 0)
        assertTrue((hdc.statusRegister() and Wd1003.STA_COMMAND) != 0)

        hdc.ioWriteByte(0x323, 0x03) // DMA + IRQ
        hdc.ioWriteByte(0x322, 0) // select
        hdc.issueCommand(intArrayOf(Wd1003.CMD_TESTREADY, 0, 0, 0, 0, 0))
        assertTrue((hdc.statusRegister() and Wd1003.STA_INTERRUPT) != 0)
        assertTrue((pic.requestRegister() and (1 shl 5)) != 0)
        val csb = hdc.ioReadByte(0x320)
        assertEquals(0, csb and Wd1003.CSB_ERROR)
    }

    @Test
    fun dmaReadWriteRoundTrip() {
        val cpu = Emulator8086()
        val pic = Pic8259()
        val dma = Dma8237(cpu)
        val hdc = Wd1003(cpu, pic, dma)
        val tmp = File.createTempFile("wd1003-dma", ".img")
        tmp.deleteOnExit()
        RandomAccessFile(tmp, "rw").use { it.setLength(4L * 17 * 5 * 512) }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)
        hdc.attachImage(0, cpu.diskImage(0x80))

        // Program DMA channel 3: address 0x10000, count 511 (512 bytes)
        dma.ioWriteByte(0x0C, 0) // clear flip-flop
        dma.ioWriteByte(0x06, 0x00) // addr low
        dma.ioWriteByte(0x06, 0x00) // addr high → 0x0000
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x07, 0xFF) // count low
        dma.ioWriteByte(0x07, 0x01) // count high → 0x01FF = 511
        dma.ioWriteByte(0x82, 0x01) // page → phys 0x10000
        dma.ioWriteByte(0x0A, 0x03) // unmask channel 3

        for (i in 0 until 512) cpu.setMem(0x10000 + i, 0x5A)
        hdc.ioWriteByte(0x323, 0x03)
        hdc.ioWriteByte(0x322, 0)
        hdc.issueCommand(intArrayOf(Wd1003.CMD_WRITE, 0, 1, 0, 1, 0))
        assertEquals(0, hdc.ioReadByte(0x320) and Wd1003.CSB_ERROR)

        for (i in 0 until 512) cpu.setMem(0x10000 + i, 0)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x06, 0x00)
        dma.ioWriteByte(0x06, 0x00)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x07, 0xFF)
        dma.ioWriteByte(0x07, 0x01)
        dma.ioWriteByte(0x82, 0x01)
        dma.ioWriteByte(0x0A, 0x03)

        hdc.ioWriteByte(0x323, 0x03)
        hdc.ioWriteByte(0x322, 0)
        hdc.issueCommand(intArrayOf(Wd1003.CMD_READ, 0, 1, 0, 1, 0))
        assertEquals(0, hdc.ioReadByte(0x320) and Wd1003.CSB_ERROR)
        assertEquals(0x5A, cpu.getMem(0x10000))
        assertEquals(0x5A, cpu.getMem(0x10000 + 511))
    }
}

class HardDiskBootIntegrationTest {
    @Test
    fun bootsWithFixedDiskBiosReadsMbrGeometry() {
        TestAssets.assumeRomsPresent()
        val u18 = TestAssets.u18
        val u19 = TestAssets.u19

        val hd = File.createTempFile("k8086-hd-boot", ".img")
        hd.deleteOnExit()
        RandomAccessFile(hd, "rw").use { raf ->
            raf.setLength(XT_HARD_DISK_BYTES)
            // Minimal MBR: jump + OEM + 0x55AA signature
            raf.seek(0)
            raf.write(ByteArray(512).also { buf ->
                buf[0] = 0xEB.toByte()
                buf[1] = 0x3C.toByte()
                buf[2] = 0x90.toByte()
                buf[510] = 0x55.toByte()
                buf[511] = 0xAA.toByte()
            })
        }

        val machine = Machine(
            u18.absolutePath,
            u19.absolutePath,
            MachineOptions(
                showVideo = false,
                hardDisk = HardDiskControllerConfig(
                    enabled = true,
                    imagePath = hd.absolutePath,
                    bootFromDisk = true,
                    useInt13Shim = false,
                    useHostFixedDiskBios = true,
                ),
            ),
        )
        val controller = requireNotNull(machine.wd1003)
        val bios = requireNotNull(machine.fixedDiskBios)
        assertEquals(null, machine.hdInt13)

        machine.cpu.loadSystemRoms(u18.absolutePath, u19.absolutePath)
        machine.cpu.setupBootDisks(hardDiskImage = "@${hd.absolutePath}")
        controller.attachImage(0, machine.cpu.diskImage(0x80))

        // Controller present and ready
        assertTrue((controller.statusRegister() and Wd1003.STA_READY) != 0)

        val cpu = machine.cpu
        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x08)
        assertTrue(bios.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(1, cpu.getReg8(REG_DL))

        // Read MBR via FixedDiskBios
        cpu.setReg16(REG_ES, 0x1000)
        cpu.setReg16(REG_BX, 0)
        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x02)
        cpu.setReg8(REG_AL, 1)
        cpu.setReg8(REG_CH, 0)
        cpu.setReg8(REG_CL, 1)
        cpu.setReg8(REG_DH, 0)
        assertTrue(bios.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(0x55, cpu.getMem(0x10000 + 510))
        assertEquals(0xAA, cpu.getMem(0x10000 + 511))

        // Run a slice of POST — should not crash with controller mapped
        var steps = 0L
        while (steps < 500_000L) {
            if (!cpu.step()) break
            machine.tickDevices(cpu.lastInstructionCycles.coerceAtLeast(15))
            steps++
        }
        assertTrue(steps > 1000, "POST should execute instructions")
    }

    @Test
    fun secondDriveAndGeometryOverride() {
        TestAssets.assumeRomsPresent()
        val hd0 = File.createTempFile("k8086-hd0", ".img").also { it.deleteOnExit() }
        val hd1 = File.createTempFile("k8086-hd1", ".img").also { it.deleteOnExit() }
        RandomAccessFile(hd0, "rw").use { it.setLength(XT_HARD_DISK_BYTES) }
        RandomAccessFile(hd1, "rw").use { it.setLength(XT_HARD_DISK_BYTES) }

        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                showVideo = false,
                hardDisk = HardDiskControllerConfig(
                    enabled = true,
                    imagePath = hd0.absolutePath,
                    secondImagePath = hd1.absolutePath,
                    cylinders = 100,
                    heads = 8,
                    sectorsPerTrack = 17,
                    useHostFixedDiskBios = true,
                ),
            ),
        )
        machine.cpu.setupBootDisks(
            hardDiskImage = hd0.absolutePath,
            secondHardDiskImage = hd1.absolutePath,
        )
        val controller = requireNotNull(machine.wd1003)
        controller.attachImage(0, machine.cpu.diskImage(0x80))
        controller.attachImage(1, machine.cpu.diskImage(0x81))

        assertTrue(controller.drivePresent(0))
        assertTrue(controller.drivePresent(1))
        assertEquals(100, controller.geometry(0).cylinders)
        assertEquals(8, controller.geometry(0).heads)

        val cpu = machine.cpu
        cpu.setReg8(REG_DL, 0x81)
        cpu.setReg8(REG_AH, 0x08)
        assertTrue(requireNotNull(machine.fixedDiskBios).handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(2, cpu.getReg8(REG_DL))
    }
}
