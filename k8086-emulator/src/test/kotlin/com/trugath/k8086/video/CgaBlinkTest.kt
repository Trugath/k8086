package com.trugath.k8086.video

import com.trugath.k8086.cpu.Emulator8086
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CgaBlinkTest {
    @Test
    fun blinkAttributeHidesForegroundOnOffPhase() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        // 40-col text + video enable + blink enable (mode 0 style).
        cga.ioWriteByte(0x3D8, 0x28)

        // Cell (0,0): 'A' with normal blink attribute 0x87 (blink + white on black).
        cpu.writePhysByte(0xB8000, 0x41)
        cpu.writePhysByte(0xB8001, 0x87)

        // Advance into the visible half of the blink cycle and sample a foreground pixel.
        repeat(8) { cga.tickCpuCycles(Cga.CYCLES_PER_FRAME) }
        val on = cga.copyFramebuffer()!!
        val fgOn = on.argb[1 * on.width + 1] // interior of 'A'
        assertEquals(Cga.CGA_PALETTE[0x07], fgOn and 0xFFFFFF)

        // Skip to the hidden half (frames 16..31).
        repeat(16) { cga.tickCpuCycles(Cga.CYCLES_PER_FRAME) }
        val off = cga.copyFramebuffer()!!
        val fgOff = off.argb[1 * off.width + 1]
        assertEquals(Cga.CGA_PALETTE[0x00], fgOff and 0xFFFFFF, "blink-off phase must draw background")
        assertNotEquals(fgOn and 0xFFFFFF, fgOff and 0xFFFFFF)
    }

    @Test
    fun blinkDisabledUsesBrightBackground() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        // Blink bit clear → attribute bit7 selects bright background.
        cga.ioWriteByte(0x3D8, 0x08) // video enable only
        cpu.writePhysByte(0xB8000, 0x20) // space
        cpu.writePhysByte(0xB8001, 0x80) // black on bright black? bit7 bg → color 8
        repeat(2) { cga.tickCpuCycles(Cga.CYCLES_PER_FRAME) }
        val snap = cga.copyFramebuffer()!!
        assertEquals(Cga.CGA_PALETTE[0x08], snap.argb[0] and 0xFFFFFF)
    }
}
