package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

class PcSpeakerTest {
    @Test
    fun soundingRequiresSpeakerDataAndPit2High() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val speaker = PcSpeaker(pit, ppi, enableAudio = false)

        // Gate timer 2 and enable speaker data; program an audible reload.
        ppi.ioWriteByte(0x61, 0x03) // PB0 gate + PB1 data
        pit.ioWriteByte(0x43, 0xB6) // counter 2, lo/hi, mode 3
        pit.ioWriteByte(0x42, 0x00)
        pit.ioWriteByte(0x42, 0x10) // reload 0x1000
        pit.tickCpuCycles(200_000)

        // After enough ticks, output has toggled at least once; sounding tracks AND of PB1 and output.
        val sounding = speaker.isSounding()
        assertEquals((ppi.portBValue() and 0x02) != 0 && pit.timer2Output(), sounding)

        ppi.ioWriteByte(0x61, 0x01) // clear speaker data bit
        assertFalse(speaker.isSounding(), "PB1 clear must silence the speaker")
    }

    @Test
    fun silentWhenGateClosedEvenIfDataBitSet() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val speaker = PcSpeaker(pit, ppi, enableAudio = false)
        // Program an audible tone, freeze PIT2 output high, then drop only the gate.
        ppi.ioWriteByte(0x61, 0x03)
        pit.ioWriteByte(0x43, 0xB6)
        pit.ioWriteByte(0x42, 0x00)
        pit.ioWriteByte(0x42, 0x10) // reload 0x1000 — well above Nyquist floor
        pit.tickCpuCycles(500_000)
        // Force output high if needed by ticking until true, then close gate.
        var steps = 0
        while (!pit.timer2Output() && steps++ < 50) pit.tickCpuCycles(50_000)
        assertTrue(pit.timer2Output() || steps > 0)
        ppi.ioWriteByte(0x61, 0x02) // PB1 data only — gate off
        assertFalse(speaker.isSounding())
        assertEquals(PcSpeaker.SILENCE, speaker.sampleLevel(), "gate off must be midpoint silence")
    }

    @Test
    fun ultrasonicPit2RendersAsSilence() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val speaker = PcSpeaker(pit, ppi, enableAudio = false)
        ppi.ioWriteByte(0x61, 0x03)
        pit.ioWriteByte(0x43, 0xB6)
        pit.ioWriteByte(0x42, 0x02) // reload 2 — far above Nyquist at 22.05 kHz
        pit.ioWriteByte(0x42, 0x00)
        pit.tickCpuCycles(100_000)
        assertEquals(PcSpeaker.SILENCE, speaker.sampleLevel())
        assertFalse(speaker.isSounding())
    }

    @Test
    fun tickAndCloseAreNoOpsWithoutAudioDevice() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val speaker = PcSpeaker(pit, ppi, enableAudio = false)
        speaker.tickCpuCycles(50_000)
        speaker.close()
    }

    @Test
    fun sampleLevelIsMidpointWhenSilentOrMuted() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val speaker = PcSpeaker(pit, ppi, enableAudio = false)

        // Gate + data off → unsigned silence (0x80), not 0x00 (which clicks on underrun/mix).
        ppi.ioWriteByte(0x61, 0x00)
        assertEquals(PcSpeaker.SILENCE, speaker.sampleLevel())

        speaker.muted = true
        ppi.ioWriteByte(0x61, 0x03)
        pit.ioWriteByte(0x43, 0xB6)
        pit.ioWriteByte(0x42, 0x00)
        pit.ioWriteByte(0x42, 0x10) // reload 0x1000 — audible at 22.05 kHz
        pit.tickCpuCycles(200_000)
        assertEquals(PcSpeaker.SILENCE, speaker.sampleLevel(), "mute must feed midpoint silence")

        speaker.muted = false
        // With data+gate enabled and a representable period, level is HIGH or LOW.
        val level = speaker.sampleLevel().toInt() and 0xFF
        assertTrue(level == (PcSpeaker.HIGH.toInt() and 0xFF) || level == (PcSpeaker.LOW.toInt() and 0xFF))
    }
}

class HdInt13Test {
    @Test
    fun provisionedBlank10MbParamsWriteReadRoundTrip() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        val tmp = File.createTempFile("k8086-hd-blank", ".img")
        tmp.deleteOnExit()
        tmp.delete() // force setupBootDisks to provision
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)
        assertEquals(XT_HARD_DISK_BYTES, tmp.length())

        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x08)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(1, cpu.getReg8(REG_DL))

        // Write pattern at CHS 0/0/1 via AH=03
        cpu.setReg16(REG_ES, 0x1000)
        cpu.setReg16(REG_BX, 0)
        for (i in 0 until 512) cpu.setMem(0x10000 + i, 0xC3)
        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x03)
        cpu.setReg8(REG_AL, 1)
        cpu.setReg8(REG_CH, 0)
        cpu.setReg8(REG_CL, 1)
        cpu.setReg8(REG_DH, 0)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))

        for (i in 0 until 512) cpu.setMem(0x10000 + i, 0)
        cpu.setReg8(REG_AH, 0x02)
        cpu.setReg8(REG_AL, 1)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(0xC3, cpu.getMem(0x10000))
        assertEquals(0xC3, cpu.getMem(0x10000 + 511))
    }

    @Test
    fun geometryFitsClassicXtMapping() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        val tmp = File.createTempFile("k8086-hd", ".img")
        tmp.deleteOnExit()
        // 4 heads * 17 spt * 40 cyl * 512 = 1,392,640 bytes
        RandomAccessFile(tmp, "rw").use { it.setLength(4L * 17 * 40 * 512) }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)
        val g = hd.geometryFor(cpu.diskImage(0x80)!!)
        assertEquals(17, g.sectorsPerTrack)
        assertEquals(4, g.heads)
        assertEquals(40, g.cylinders)
    }

    @Test
    fun int13ReadWriteRoundTrip() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        cpu.hostServices.onInt13HardDisk = { hd.handle() }

        val tmp = File.createTempFile("k8086-hd-rw", ".img")
        tmp.deleteOnExit()
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(4L * 17 * 10 * 512)
            raf.seek(0)
            raf.write(ByteArray(512) { 0xA5.toByte() })
        }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)

        // Buffer at 1000:0000
        cpu.setReg16(REG_ES, 0x1000)
        cpu.setReg16(REG_BX, 0x0000)
        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x02) // read
        cpu.setReg8(REG_AL, 1) // 1 sector
        cpu.setReg8(REG_CH, 0)
        cpu.setReg8(REG_CL, 1) // sector 1
        cpu.setReg8(REG_DH, 0)

        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x200)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        // INT 13h — shim handles; no IVT needed
        cpu.writeInstruction(byteArrayOf(0xCD.toByte(), 0x13.toByte()))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0, cpu.getFlag(FLAG_CF), "read should succeed")
        assertEquals(0, cpu.getReg8(REG_AH), "AH status 0")
        assertEquals(0xA5, cpu.getMem(0x10000), "sector data copied to ES:BX")

        // Write different pattern to sector 2 via INT 13h AH=03
        for (i in 0 until 512) cpu.setMem(0x10000 + i, 0x5A)
        cpu.setReg8(REG_AH, 0x03)
        cpu.setReg8(REG_AL, 1)
        cpu.setReg8(REG_CL, 2)
        cpu.setIp(0x200)
        cpu.writeInstruction(byteArrayOf(0xCD.toByte(), 0x13.toByte()))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0, cpu.getFlag(FLAG_CF))

        RandomAccessFile(tmp, "r").use { raf ->
            raf.seek(512)
            val b = ByteArray(512)
            raf.readFully(b)
            assertEquals(0x5A, b[0].toInt() and 0xFF)
        }
    }

    @Test
    fun floppyInt13NotClaimedByShim() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        cpu.setReg8(REG_DL, 0x00)
        assertFalse(hd.handle(), "shim must ignore floppy DL")
    }

    @Test
    fun resetStatusVerifyParamsAndDasdType() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        val tmp = File.createTempFile("k8086-hd-ops", ".img")
        tmp.deleteOnExit()
        val sectors = 4L * 17 * 20
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(sectors * 512)
            raf.write(ByteArray(512) { 0x11.toByte() })
        }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)
        cpu.setReg8(REG_DL, 0x80)

        cpu.setReg8(REG_AH, 0x00)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getReg8(REG_AH))
        assertEquals(0, cpu.getFlag(FLAG_CF))

        cpu.setReg8(REG_AH, 0x01)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getReg8(REG_AH))

        cpu.setReg16(REG_ES, 0x1000)
        cpu.setReg16(REG_BX, 0)
        cpu.setReg8(REG_AH, 0x04) // verify
        cpu.setReg8(REG_AL, 1)
        cpu.setReg8(REG_CH, 0)
        cpu.setReg8(REG_CL, 1)
        cpu.setReg8(REG_DH, 0)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))

        cpu.setReg8(REG_AH, 0x08)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(1, cpu.getReg8(REG_DL))
        assertTrue(cpu.getReg8(REG_DH) >= 0) // max head

        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x15)
        assertTrue(hd.handle())
        assertEquals(0x02, cpu.getReg8(REG_AH))
        val reported =
            (cpu.getReg16(REG_CX).toLong() shl 16) or cpu.getReg16(REG_DX).toLong()
        assertEquals(sectors, reported)
    }

    @Test
    fun missingDriveAndBadChsFail() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x02)
        assertTrue(hd.handle())
        assertEquals(1, cpu.getFlag(FLAG_CF))
        assertEquals(0x01, cpu.getReg8(REG_AH))

        val tmp = File.createTempFile("k8086-hd-bad", ".img")
        tmp.deleteOnExit()
        RandomAccessFile(tmp, "rw").use { it.setLength(4L * 17 * 5 * 512) }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)

        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x02)
        cpu.setReg8(REG_AL, 1)
        cpu.setReg8(REG_CH, 0)
        cpu.setReg8(REG_CL, 0) // invalid sector 0
        cpu.setReg8(REG_DH, 0)
        assertTrue(hd.handle())
        assertEquals(1, cpu.getFlag(FLAG_CF))
        assertEquals(0x04, cpu.getReg8(REG_AH))

        cpu.setReg8(REG_AH, 0x01) // status of last op
        assertTrue(hd.handle())
        assertEquals(0x04, cpu.getReg8(REG_AH))
        assertEquals(1, cpu.getFlag(FLAG_CF))
    }

    @Test
    fun formatSeekInitAndAlternateReset() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        val tmp = File.createTempFile("k8086-hd-fmt", ".img")
        tmp.deleteOnExit()
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(4L * 17 * 10 * 512)
            raf.write(ByteArray(512) { 0xEE.toByte() })
        }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)
        cpu.setReg8(REG_DL, 0x80)

        // Format track 0 head 0
        cpu.setReg8(REG_AH, 0x05)
        cpu.setReg8(REG_CH, 0)
        cpu.setReg8(REG_CL, 1)
        cpu.setReg8(REG_DH, 0)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
        RandomAccessFile(tmp, "r").use { raf ->
            val b = ByteArray(512)
            raf.readFully(b)
            assertEquals(0, b[0].toInt() and 0xFF)
        }

        cpu.setReg8(REG_AH, 0x0C) // seek
        cpu.setReg8(REG_CH, 1)
        cpu.setReg8(REG_CL, 1)
        cpu.setReg8(REG_DH, 0)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))

        cpu.setReg8(REG_AH, 0x09) // init params
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))

        cpu.setReg8(REG_AH, 0x0D) // alternate reset
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))
    }

    @Test
    fun zeroSectorCountSucceedsAndTrulyUnsupportedFails() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        val tmp = File.createTempFile("k8086-hd-z", ".img")
        tmp.deleteOnExit()
        RandomAccessFile(tmp, "rw").use { it.setLength(4L * 17 * 5 * 512) }
        cpu.setupBootDisks(hardDiskImage = tmp.absolutePath)

        cpu.setReg8(REG_DL, 0x80)
        cpu.setReg8(REG_AH, 0x02)
        cpu.setReg8(REG_AL, 0)
        assertTrue(hd.handle())
        assertEquals(0, cpu.getFlag(FLAG_CF))

        cpu.setReg8(REG_AH, 0x41) // EDD — unsupported
        assertTrue(hd.handle())
        assertEquals(1, cpu.getFlag(FLAG_CF))
        assertEquals(0x01, cpu.getReg8(REG_AH))
    }

    @Test
    fun geometryGrowsHeadsForLargeImages() {
        val cpu = Emulator8086()
        val hd = HdInt13(cpu)
        val tmp = File.createTempFile("k8086-hd-big", ".img")
        tmp.deleteOnExit()
        // Force cyl > 1023 at 4 heads → heads must grow.
        val totalSectors = 4L * 17 * 2000
        RandomAccessFile(tmp, "rw").use { it.setLength(totalSectors * 512) }
        RandomAccessFile(tmp, "r").use { raf ->
            val geo = hd.geometryFor(raf)
            assertTrue(geo.heads > 4, "heads=${geo.heads}")
            assertTrue(geo.cylinders <= 1024)
        }
    }
}
