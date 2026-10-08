package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Many CGA games terminate via INT 21h AH=4Ch while still in graphics mode. Without
// a restore, CLS writes char/attr cells into APA VRAM (garbage pixels). Restore also
// stamps BDA 40:84 so DIR /P does not treat MAX_Y=0.
class VideoModeRestoreTest {
    @Test
    fun dosTerminateRestoresTextModeFromGraphics() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cpu.hostServices.onDosTerminate = { cga.restoreTextModeIfGraphics() }
        cpu.attachIoBus(IoBus().also { it.map(cga, (0x3D0..0x3DF).toList()) })

        // Put the adapter into mode-4-like graphics the way a game would (OUT 0x3D8).
        cga.ioWriteByte(0x3D8, 0x2A) // graphics + video enable
        // Sprinkle some graphics-looking bytes into VRAM.
        cpu.writePhysByte(0xB8000, 0xFF)
        cpu.writePhysByte(0xB8001, 0xFF)
        assertTrue((cga.modeControlValue() and 0x02) != 0)

        // INT 21h AH=4Ch at 0000:0100.
        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x100)
        cpu.setReg16(REG_SS, 0x1000)
        cpu.setReg16(REG_SP, 0xFFFE)
        // Seed IVT entry for INT 21h to a single IRET so the interrupt returns.
        val handler = 0x8000
        cpu.writePhysByte(0x21 * 4, handler and 0xFF)
        cpu.writePhysByte(0x21 * 4 + 1, (handler ushr 8) and 0xFF)
        cpu.writePhysByte(0x21 * 4 + 2, 0)
        cpu.writePhysByte(0x21 * 4 + 3, 0)
        cpu.writePhysByte(handler, 0xCF) // IRET

        cpu.setReg8(REG_AH, 0x4C)
        cpu.setReg8(REG_AL, 0x00)
        cpu.writeInstruction(byteArrayOf(0xCD.toByte(), 0x21.toByte()))
        assertTrue(cpu.executeSingleInstruction())

        assertEquals(0, cga.modeControlValue() and 0x02, "graphics bit should clear on DOS terminate")
        assertEquals(0x03, cpu.getMem(0x449), "BDA mode should be 3")
        assertEquals(80, cpu.getMem(0x44A), "BDA columns should be 80")
        assertEquals(24, cpu.getMem(0x484), "BDA rows-1 should be 24")
        assertEquals(0x20, cpu.getMem(0xB8000), "text VRAM should be cleared to spaces")
        assertEquals(0x07, cpu.getMem(0xB8001), "text VRAM attribute should be 07")
        assertEquals(0, cpu.getMem(0x450), "cursor col reset")
        assertEquals(0, cpu.getMem(0x451), "cursor row reset")
    }

    @Test
    fun restoreIsNoOpWhenAlreadyInTextMode() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x29) // 80-col text
        cpu.writePhysByte(0xB8000, 0x41) // 'A' - must not be wiped
        cga.restoreTextModeIfGraphics()
        assertEquals(0x41, cpu.getMem(0xB8000), "text-mode VRAM must be left alone")
        assertEquals(0x29, cga.modeControlValue())
    }
}
