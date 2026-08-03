package com.trugath.k8086

import com.trugath.k8086.bus.IoBus
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.loadSystemRoms
import com.trugath.k8086.video.Cga
import com.trugath.k8086.video.CgaComposite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * BIOS INT 10h AH=00 AL=06 smoke: guest mode-set programs the CGA adapter into
 * 640×200 hi-res graphics. Pixel packing is covered by [com.trugath.k8086.video.CgaGraphicsMode6Test].
 */
class CgaMode6Int10Test {
    @Test
    fun int10SetMode6ProgramsHiResAndFramebuffer() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)

        val cpu = Emulator8088()
        cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)
        val pic = Pic8259()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x29) // start in 80-col text like a normal boot
        val bus = IoBus()
        bus.map(cga, (0x3D0..0x3DF).toList())
        bus.map(pic, listOf(0x20, 0x21))
        cpu.attachIoBus(bus)
        cpu.attachInterruptSource(pic)
        pic.ioWriteByte(0x21, 0xFF)

        fun w8(a: Int, v: Int) = cpu.writePhysByte(a, v)
        fun w16(a: Int, v: Int) {
            w8(a, v and 0xFF)
            w8(a + 1, (v shr 8) and 0xFF)
        }
        // Minimal BDA + INT 10h vector (same seed as CgaVideoThroughputTest).
        w8(0x449, 3)
        w16(0x44A, 80)
        w16(0x44C, 0x1000)
        w16(0x44E, 0)
        w16(0x450, 0)
        w8(0x462, 0)
        w16(0x463, 0x3D4)
        w8(0x465, 0x29)
        w8(0x466, 0x30)
        w16(0x410, 0x002D)
        w16(0x40, 0xF065)
        w16(0x42, 0xF000)

        // mov ax,0006h / int 10h / mov ax,0B800h / mov es,ax / xor di,di /
        // mov al,0A5h / stosb / hlt
        val body = intArrayOf(
            0xB8, 0x06, 0x00,
            0xCD, 0x10,
            0xB8, 0x00, 0xB8,
            0x8E, 0xC0,
            0x31, 0xFF,
            0xB0, 0xA5,
            0xAA,
            0xF4,
        )
        cpu.setReg16(REG_SS, 0x3000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_ES, 0x1000)
        cpu.setFlagsValue(0)
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        for (i in body.indices) cpu.writePhysByte(0x10100 + i, body[i])

        var instr = 0
        while (instr < 500_000 && !cpu.isHalted()) {
            if (!cpu.executeSingleInstruction()) break
            instr++
            cga.tickCpuCycles(Machine.peripheralCyclesFor(cpu.lastInstructionCycles))
        }
        assertTrue(cpu.isHalted(), "guest should HLT after INT 10h mode 6 + VRAM write (instr=$instr)")
        assertTrue((cga.modeControlValue() and 0x10) != 0, "BIOS mode 6 must set hi-res bit")
        assertTrue((cga.modeControlValue() and 0x02) != 0, "BIOS mode 6 must set graphics bit")
        assertEquals(0xA5, cpu.readPhysByte(0xB8000) and 0xFF, "guest stosb should land in APA VRAM")

        // Force RGBI so pixel asserts match CgaGraphicsMode6Test packing rules.
        cga.compositeMode = CgaComposite.Mode.OFF
        val snap = cga.copyFramebuffer()!!
        assertEquals(640, snap.width)
        assertEquals(200, snap.height)
        assertTrue(snap.graphicsMode)
        val fg = Cga.CGA_PALETTE[cga.colorSelectValue() and 0x0F]
        val bg = Cga.CGA_PALETTE[0x00]
        // 0xA5 = 10100101
        val expected = booleanArrayOf(true, false, true, false, false, true, false, true)
        for (i in 0 until 8) {
            val want = if (expected[i]) fg else bg
            assertEquals(want, snap.argb[i] and 0xFFFFFF, "pixel x=$i after INT 10h mode 6")
        }
    }
}
