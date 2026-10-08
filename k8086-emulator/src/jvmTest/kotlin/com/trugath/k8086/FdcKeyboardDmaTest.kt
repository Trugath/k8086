package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

class KeyboardTest {
    @Test
    fun enqueueDeliversScanCodeAndAckAdvancesQueue() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val kb = Keyboard(pic, ppi)

        kb.typeKey(0x1E) // 'A' make + break
        assertTrue((pic.requestRegister() and 0x02) != 0, "IRQ1 pending")
        assertEquals(0x1E, ppi.ioReadByte(0x60))

        // Pulse PB7 to acknowledge (clear keyboard).
        ppi.ioWriteByte(0x61, 0x80)
        ppi.ioWriteByte(0x61, 0x00)

        assertEquals(0x9E, ppi.ioReadByte(0x60), "break code follows after ack")
    }

    @Test
    fun ctrlAltDeleteQueuesMakesThenBreaks() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val kb = Keyboard(pic, ppi)

        kb.sendCtrlAltDelete()
        val expected = intArrayOf(0x1D, 0x38, 0x53, 0xD3, 0xB8, 0x9D)
        for (code in expected) {
            assertEquals(code, ppi.ioReadByte(0x60))
            ppi.ioWriteByte(0x61, 0x80)
            ppi.ioWriteByte(0x61, 0x00)
        }
    }
}

class Fdc765Test {
    private fun harness(): Triple<Emulator8086, Fdc765, Dma8237> {
        val cpu = Emulator8086()
        val pic = Pic8259()
        val dma = Dma8237(cpu)
        val fdc = Fdc765(cpu, pic, dma)
        return Triple(cpu, fdc, dma)
    }

    private fun releaseReset(fdc: Fdc765) {
        fdc.ioWriteByte(0x3F2, 0x00) // hold reset
        fdc.ioWriteByte(0x3F2, 0x0C) // out of reset, DMA+IRQ enable, drive 0
    }

    private fun readResult(fdc: Fdc765, n: Int): IntArray {
        val out = IntArray(n)
        for (i in 0 until n) {
            assertTrue((fdc.ioReadByte(0x3F4) and 0x40) != 0, "expect DIO in result phase")
            out[i] = fdc.ioReadByte(0x3F5)
        }
        return out
    }

    @Test
    fun resetSenseSpecifySeekRecalibrateAndReadId() {
        val (cpu, fdc, _) = harness()
        releaseReset(fdc)
        assertEquals(0x0C, fdc.digitalOutputRegister())
        assertTrue((fdc.ioReadByte(0x3F4) and 0x80) != 0)

        // Four Sense Interrupt Status polls after reset.
        for (i in 0 until 4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            val r = readResult(fdc, 2)
            assertEquals(0xC0 or i, r[0])
        }

        // Specify (no result).
        fdc.ioWriteByte(0x3F5, 0x03)
        fdc.ioWriteByte(0x3F5, 0xAF)
        fdc.ioWriteByte(0x3F5, 0x02)

        // Recalibrate drive 0 → IRQ6, then sense.
        fdc.ioWriteByte(0x3F5, 0x07)
        fdc.ioWriteByte(0x3F5, 0x00)
        assertEquals(0, fdc.presentCylinderOf(0))
        fdc.ioWriteByte(0x3F5, 0x08)
        assertEquals(0x20, readResult(fdc, 2)[0] and 0xF8)

        // Seek to cylinder 5.
        fdc.ioWriteByte(0x3F5, 0x0F)
        fdc.ioWriteByte(0x3F5, 0x00)
        fdc.ioWriteByte(0x3F5, 0x05)
        assertEquals(5, fdc.presentCylinderOf(0))

        // Mount media so Sense Drive Status reports ready.
        val img = File.createTempFile("fdc-ready", ".img").also { it.deleteOnExit() }
        RandomAccessFile(img, "rw").use { it.setLength(80L * 2 * 18 * 512) }
        cpu.setupBootDisks(floppyImage = img.absolutePath, hardDiskImage = null)

        fdc.ioWriteByte(0x3F5, 0x04) // Sense Drive Status
        fdc.ioWriteByte(0x3F5, 0x00)
        val st3 = readResult(fdc, 1)[0]
        assertTrue((st3 and 0x20) != 0) // ready

        fdc.ioWriteByte(0x3F5, 0x0A) // Read ID
        fdc.ioWriteByte(0x3F5, 0x00)
        val id = readResult(fdc, 7)
        assertEquals(5, id[3]) // cylinder
    }

    @Test
    fun invalidCommandReturnsSt0Invalid() {
        val (_, fdc, _) = harness()
        releaseReset(fdc)
        // Drain reset senses
        repeat(4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            readResult(fdc, 2)
        }
        fdc.ioWriteByte(0x3F5, 0x1F) // invalid
        assertEquals(0x80, readResult(fdc, 1)[0])
    }

    @Test
    fun readWithoutImageFailsAbnormally() {
        val (_, fdc, _) = harness()
        releaseReset(fdc)
        repeat(4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            readResult(fdc, 2)
        }
        // Read Data — 9 bytes
        val cmd = intArrayOf(0x06, 0x00, 0, 0, 1, 2, 18, 0x1B, 0xFF)
        for (b in cmd) fdc.ioWriteByte(0x3F5, b)
        val r = readResult(fdc, 7)
        assertTrue((r[0] and 0x40) != 0, "abnormal termination")
    }

    @Test
    fun readSectorViaDmaIntoMemory() {
        val (cpu, fdc, dma) = harness()
        val img = File.createTempFile("k8086-fd", ".img")
        img.deleteOnExit()
        RandomAccessFile(img, "rw").use { raf ->
            raf.setLength(1474560) // 1.44 MB
            raf.seek(0)
            raf.write(ByteArray(512) { i -> (0xA0 + (i and 0x0F)).toByte() })
        }
        cpu.setupBootDisks(floppyImage = img.absolutePath, hardDiskImage = null)

        // Program DMA channel 2: addr 0x1000, count 511 (512 bytes), page 0, unmasked.
        dma.ioWriteByte(0x0C, 0) // clear flip-flop
        dma.ioWriteByte(0x04, 0x00) // ch2 addr lo
        dma.ioWriteByte(0x04, 0x10) // ch2 addr hi → 0x1000
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x05, 0xFF) // count lo
        dma.ioWriteByte(0x05, 0x01) // count hi → 511
        dma.ioWriteByte(0x81, 0x00) // page
        dma.ioWriteByte(0x0B, 0x46) // mode: ch2, single, increment, write
        dma.ioWriteByte(0x0A, 0x02) // unmask ch2

        releaseReset(fdc)
        repeat(4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            readResult(fdc, 2)
        }
        val cmd = intArrayOf(0x46, 0x00, 0, 0, 1, 2, 18, 0x1B, 0xFF) // MT|MFM read
        for (b in cmd) fdc.ioWriteByte(0x3F5, b)
        val r = readResult(fdc, 7)
        assertEquals(0, r[0] and 0xC0, "normal termination, st0=${r[0]}")
        assertEquals(0xA0, cpu.readPhysByte(0x1000))
        assertEquals(0xA1, cpu.readPhysByte(0x1001))
    }

    @Test
    fun formatTrackThenReadFillPattern() {
        val (cpu, fdc, dma) = harness()
        val img = File.createTempFile("k8086-fmt", ".img")
        img.deleteOnExit()
        RandomAccessFile(img, "rw").use { it.setLength(368640) } // 360 KB
        cpu.setupBootDisks(floppyImage = img.absolutePath, hardDiskImage = null)

        // ID table at 0x2000: 9 × (C,H,R,N) for track 0 head 0.
        var addr = 0x2000
        for (sector in 1..9) {
            cpu.writePhysByte(addr++, 0) // C
            cpu.writePhysByte(addr++, 0) // H
            cpu.writePhysByte(addr++, sector) // R
            cpu.writePhysByte(addr++, 2) // N = 512
        }

        // DMA ch2 read (mem→FDC): 36 bytes.
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x04, 0x00)
        dma.ioWriteByte(0x04, 0x20)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x05, 35)
        dma.ioWriteByte(0x05, 0x00)
        dma.ioWriteByte(0x81, 0x00)
        dma.ioWriteByte(0x0B, 0x4A) // ch2 single increment read
        dma.ioWriteByte(0x0A, 0x02)

        releaseReset(fdc)
        repeat(4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            readResult(fdc, 2)
        }

        // Format Track MFM: N=2, SC=9, GPL=0x2A, fill=0xF6
        for (b in intArrayOf(0x4D, 0x00, 2, 9, 0x2A, 0xF6)) fdc.ioWriteByte(0x3F5, b)
        val fmt = readResult(fdc, 7)
        assertEquals(0, fmt[0] and 0xC0, "format st0=${fmt[0]}")

        // Read sector 1 via DMA into 0x1000 and check fill.
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x04, 0x00)
        dma.ioWriteByte(0x04, 0x10)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x05, 0xFF)
        dma.ioWriteByte(0x05, 0x01)
        dma.ioWriteByte(0x81, 0x00)
        dma.ioWriteByte(0x0B, 0x46)
        dma.ioWriteByte(0x0A, 0x02)

        for (b in intArrayOf(0x46, 0x00, 0, 0, 1, 2, 9, 0x2A, 0xFF)) fdc.ioWriteByte(0x3F5, b)
        val rd = readResult(fdc, 7)
        assertEquals(0, rd[0] and 0xC0, "read after format st0=${rd[0]}")
        assertEquals(0xF6, cpu.readPhysByte(0x1000))
        assertEquals(0xF6, cpu.readPhysByte(0x11FF))
    }

    @Test
    fun formatWithoutImageFailsAbnormally() {
        val (_, fdc, _) = harness()
        releaseReset(fdc)
        repeat(4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            readResult(fdc, 2)
        }
        for (b in intArrayOf(0x4D, 0x00, 2, 9, 0x2A, 0xF6)) fdc.ioWriteByte(0x3F5, b)
        val r = readResult(fdc, 7)
        assertTrue((r[0] and 0x40) != 0, "abnormal termination")
    }

    @Test
    fun unusedPortsAreHarmless() {
        val (_, fdc, _) = harness()
        assertEquals(0x00, fdc.ioReadByte(0x3F0))
        assertEquals(0x00, fdc.ioReadByte(0x3F7))
        assertEquals(0xFF, fdc.ioReadByte(0x3F1))
        fdc.ioWriteByte(0x3F7, 0x00)
    }

    @Test
    fun diskChangeLineStickyUntilMotorSelect() {
        val (_, fdc, _) = harness()
        fdc.signalDiskChange()
        assertEquals(0x80, fdc.ioReadByte(0x3F7))
        // Motor on for drive 0 clears the sticky bit.
        fdc.ioWriteByte(0x3F2, 0x1C) // motor0 + DMA/IRQ + out of reset + drive 0
        assertEquals(0x00, fdc.ioReadByte(0x3F7))
    }

    @Test
    fun changeFloppyImageSwapsFileAndSignalsDiskChange() {
        val cpu = Emulator8086()
        val a = File.createTempFile("k8086-fda", ".img")
        val b = File.createTempFile("k8086-fdb", ".img")
        a.deleteOnExit()
        b.deleteOnExit()
        RandomAccessFile(a, "rw").use { it.setLength(1474560) }
        RandomAccessFile(b, "rw").use { it.setLength(1474560) }

        cpu.setupBootDisks(floppyImage = a.absolutePath, hardDiskImage = null)
        assertTrue(cpu.isDiskOpen(0))
        cpu.changeFloppyImage(0, b.absolutePath)
        assertTrue(cpu.isDiskOpen(0))
        cpu.changeFloppyImage(0, null)
        assertFalse(cpu.isDiskOpen(0))
    }

    @Test
    fun writeSectorViaDmaFromMemory() {
        val (cpu, fdc, dma) = harness()
        val img = File.createTempFile("k8086-fdw", ".img")
        img.deleteOnExit()
        RandomAccessFile(img, "rw").use { it.setLength(1474560) }
        cpu.setupBootDisks(floppyImage = img.absolutePath, hardDiskImage = null)

        for (i in 0 until 512) cpu.writePhysByte(0x1000 + i, 0x5A)

        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x04, 0x00)
        dma.ioWriteByte(0x04, 0x10)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x05, 0xFF)
        dma.ioWriteByte(0x05, 0x01)
        dma.ioWriteByte(0x81, 0x00)
        dma.ioWriteByte(0x0B, 0x4A) // ch2 single increment read (mem→FDC)
        dma.ioWriteByte(0x0A, 0x02)

        releaseReset(fdc)
        repeat(4) {
            fdc.ioWriteByte(0x3F5, 0x08)
            readResult(fdc, 2)
        }
        // Write Data MFM
        for (b in intArrayOf(0x45, 0x00, 0, 0, 1, 2, 18, 0x1B, 0xFF)) fdc.ioWriteByte(0x3F5, b)
        val r = readResult(fdc, 7)
        assertEquals(0, r[0] and 0xC0, "write st0=${r[0]}")

        RandomAccessFile(img, "r").use { raf ->
            raf.seek(0)
            assertEquals(0x5A, raf.read())
            raf.seek(511)
            assertEquals(0x5A, raf.read())
        }
    }
}

class Dma8237CoverageTest {
    @Test
    fun programReadBackMaskResetAndRefresh() {
        val cpu = Emulator8086()
        val dma = Dma8237(cpu)

        dma.ioWriteByte(0x0D, 0) // master clear
        assertTrue(dma.isMasked(0))

        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x00, 0x34)
        dma.ioWriteByte(0x00, 0x12) // addr 0x1234
        dma.ioWriteByte(0x0C, 0)
        assertEquals(0x34, dma.ioReadByte(0x00))
        assertEquals(0x12, dma.ioReadByte(0x00))

        dma.ioWriteByte(0x87, 0x0A)
        assertEquals(0x0A, dma.pageRegister(0))

        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x01, 0x10)
        dma.ioWriteByte(0x01, 0x00) // count 0x0010
        dma.ioWriteByte(0x0B, 0x58) // ch0 autoinit
        dma.ioWriteByte(0x0A, 0x00) // unmask ch0
        assertFalse(dma.isMasked(0))

        repeat(0x11) { dma.refreshCycle() }
        assertTrue(dma.terminalCount(0) || dma.ioReadByte(0x08) and 1 != 0)

        dma.ioWriteByte(0x0E, 0) // clear all masks
        assertFalse(dma.isMasked(1))
        dma.ioWriteByte(0x0F, 0x0F)
        assertTrue(dma.isMasked(2))
    }

    @Test
    fun maskRegisterReadbackVia0AAnd0F() {
        val dma = Dma8237(Emulator8086())
        dma.ioWriteByte(0x0D, 0) // master clear → all masked
        assertEquals(0x0F, dma.ioReadByte(0x0F))
        assertEquals(0x0F, dma.ioReadByte(0x0A))

        dma.ioWriteByte(0x0E, 0) // clear all masks
        assertEquals(0x00, dma.ioReadByte(0x0F))
        assertEquals(0x00, dma.ioReadByte(0x0A))

        dma.ioWriteByte(0x0A, 0x06) // set mask on channel 2 (bits: ch=2, set=1)
        assertEquals(0x04, dma.ioReadByte(0x0F))
        assertEquals(0x04, dma.ioReadByte(0x0A))

        dma.ioWriteByte(0x0F, 0x0A) // write all-channel mask
        assertEquals(0x0A, dma.ioReadByte(0x0F))
        assertEquals(0x0A, dma.ioReadByte(0x0A))
    }

    @Test
    fun port80ScratchAndSoftwareRequestAndAutoinit() {
        val cpu = Emulator8086()
        val dma = Dma8237(cpu)

        dma.ioWriteByte(0x80, 0x5A)
        assertEquals(0x5A, dma.ioReadByte(0x80))
        dma.ioWriteByte(0x80, 0xA5)
        assertEquals(0xA5, dma.ioReadByte(0x80))

        dma.ioWriteByte(0x09, 0x06) // software request channel 2
        assertEquals(0x40, dma.ioReadByte(0x08) and 0xF0)
        dma.ioWriteByte(0x09, 0x02) // clear request channel 2
        assertEquals(0x00, dma.ioReadByte(0x08) and 0xF0)

        // Non-autoinit: TC must remask the channel.
        dma.ioWriteByte(0x0D, 0)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x04, 0x00) // ch2 addr low
        dma.ioWriteByte(0x04, 0x10) // ch2 addr high → 0x1000
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x05, 0x00) // count low
        dma.ioWriteByte(0x05, 0x00) // count = 0 → one byte transfer
        dma.ioWriteByte(0x0B, 0x46) // ch2 single, increment, no autoinit
        dma.ioWriteByte(0x0A, 0x02) // unmask ch2
        assertFalse(dma.isMasked(2))
        dma.dmaWriteByte(2, 0xAB)
        assertTrue(dma.terminalCount(2))
        assertTrue(dma.isMasked(2))

        // Autoinit refresh: address and count reload from base on TC.
        dma.ioWriteByte(0x0D, 0)
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x00, 0x00)
        dma.ioWriteByte(0x00, 0x20) // base addr 0x2000
        dma.ioWriteByte(0x0C, 0)
        dma.ioWriteByte(0x01, 0x01)
        dma.ioWriteByte(0x01, 0x00) // count 1 → two refresh cycles to TC
        dma.ioWriteByte(0x0B, 0x58) // ch0 autoinit
        dma.ioWriteByte(0x0A, 0x00) // unmask ch0
        dma.refreshCycle()
        assertEquals(0x2001, dma.currentAddress(0))
        dma.refreshCycle() // TC → reload
        assertEquals(0x2000, dma.currentAddress(0))
        assertEquals(0x0001, dma.currentCount(0))
        assertFalse(dma.isMasked(0))
    }
}
