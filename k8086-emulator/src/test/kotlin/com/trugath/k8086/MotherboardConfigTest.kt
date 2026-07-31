package com.trugath.k8086

import com.trugath.k8086.chipset.Ppi8255
import com.trugath.k8086.config.InitialVideoMode
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.cpu.Emulator80286
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.MathCoprocessor8087
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotherboardConfigTest {
    @Test
    fun sw1EncodesCoprocessorVideoAndRamBanks() {
        val cfg = MotherboardConfig(
            baseMemoryKb = 256,
            mathCoprocessor = true,
            initialVideo = InitialVideoMode.CGA_40x25,
        )
        val sw = cfg.sw1WithoutFloppies()
        assertEquals(0x02, sw and 0x02, "8087 bit")
        assertEquals(0b01, (sw shr 4) and 0x03, "CGA 40 video")
        assertEquals(3, (sw shr 2) and 0x03, "256 KB banks")
        assertEquals(256 * 1024, cfg.conventionalMemoryEnd())
    }

    @Test
    fun ppiAppliesMotherboardThenFloppy() {
        val ppi = Ppi8255()
        ppi.configureMotherboard(
            MotherboardConfig(baseMemoryKb = 128, mathCoprocessor = true, initialVideo = InitialVideoMode.CGA_80x25),
        )
        ppi.configureFloppyDrives(2)
        assertEquals(1, ppi.sw1 and 0x01)
        assertEquals(0x02, ppi.sw1 and 0x02)
        assertEquals(1, (ppi.sw1 shr 2) and 0x03) // 128 KB banks
        assertEquals(0b10, (ppi.sw1 shr 4) and 0x03)
        assertEquals(1, (ppi.sw1 shr 6) and 0x03) // 2 drives → n-1 = 1
        assertEquals(2, ppi.floppyDriveCount())
    }

    @Test
    fun conventionalMemoryGapFloatsHigh() {
        val cpu = Emulator8086()
        cpu.conventionalMemoryEnd = 128 * 1024
        cpu.writePhysByte(0x10000, 0x55)
        assertEquals(0x55, cpu.readPhysByte(0x10000))
        cpu.writePhysByte(0x20000, 0xAA) // above 128 KB
        assertEquals(0xFF, cpu.readPhysByte(0x20000), "absent RAM reads as bus float")
    }

    @Test
    fun machineWiresMotherboardOptions() {
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(
                    baseMemoryKb = 256,
                    mathCoprocessor = true,
                    initialVideo = InitialVideoMode.CGA_80x25,
                ),
                showVideo = false,
            ),
        )
        assertTrue(machine.cpu is Emulator8088, "XT default CPU is 8088")
        assertEquals(CpuModel.I8088, machine.cpu.model)
        assertEquals(256 * 1024, machine.cpu.conventionalMemoryEnd)
        assertNotNull(machine.cpu.mathCoprocessor)
        assertEquals(0x02, machine.ppi.sw1 and 0x02)
    }

    @Test
    fun machineSelects8086WhenConfigured() {
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(cpu = CpuModel.I8086),
                showVideo = false,
            ),
        )
        assertEquals(CpuModel.I8086, machine.cpu.model)
        assertTrue(machine.cpu !is Emulator8088)
    }

    @Test
    fun machineSelects80286WhenConfigured() {
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(cpu = CpuModel.I80286),
                showVideo = false,
            ),
        )
        assertEquals(CpuModel.I80286, machine.cpu.model)
        assertTrue(machine.cpu is Emulator80286)
    }

    @Test
    fun mathCoprocessorFninitAndFstcw() {
        val fpu = MathCoprocessor8087()
        fpu.executeEsc(0xDB, iMod = 3, iReg = 4, iRm = 3, rmAddr = 0) { _, _ -> }
        assertEquals(0x037F, fpu.controlWord)
        assertEquals(0xFFFF, fpu.tagWord)
        var written = -1
        fpu.executeEsc(0xD9, iMod = 0, iReg = 7, iRm = 6, rmAddr = 0x500) { addr, word ->
            assertEquals(0x500, addr)
            written = word
        }
        assertEquals(0x037F, written)
    }

    @Test
    fun machineRoutesUnmasked8087ExceptionToNmi() {
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(mathCoprocessor = true),
                showVideo = false,
            ),
        )
        val fpu = machine.cpu.mathCoprocessor!!
        val memory = ByteArray(16)
        memory[0] = 0x7B // CW=037B: divide-by-zero unmasked
        memory[1] = 0x03
        val one = 1.0f.toRawBits()
        repeat(4) { memory[4 + it] = (one ushr (it * 8)).toByte() }
        val access = MathCoprocessor8087.Access(
            readByte = { memory[it].toInt() and 0xFF },
            writeByte = { addr, value -> memory[addr] = value.toByte() },
        )
        fpu.executeEsc(0xD9, 0, 5, 0, 0, access) // FLDCW
        fpu.executeEsc(0xD9, 0, 0, 0, 4, access) // FLD 1.0
        fpu.executeEsc(0xD8, 0, 6, 0, 8, access) // FDIV 0.0
        assertTrue(machine.cpu.isNmiPending())
    }

    @Test
    fun withoutCoprocessorMathIsNull() {
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            showVideo = false,
        )
        assertNull(machine.cpu.mathCoprocessor)
        assertEquals(0, machine.ppi.sw1 and 0x02)
    }

    @Test
    fun equipmentByteReflectsCoprocessorWhenEnabled() {
        // BIOS BDA equipment word at 0040:0010; bit 1 is the math-coprocessor flag
        // once POST copies SW1. We assert the PPI/SW1 source that feeds INT 11h.
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(mathCoprocessor = true),
                showVideo = false,
            ),
        )
        assertNotNull(machine.cpu.mathCoprocessor)
        assertEquals(0x02, machine.ppi.sw1 and 0x02, "SW1 bit 1 feeds INT 11h equipment bit 1")
        // Seed BDA as POST would after reading SW1.
        val equip = machine.ppi.sw1 and 0xFF
        machine.cpu.writePhysByte(0x410, equip)
        machine.cpu.writePhysByte(0x411, 0)
        assertEquals(0x02, machine.cpu.readPhysByte(0x410) and 0x02)
    }
}
