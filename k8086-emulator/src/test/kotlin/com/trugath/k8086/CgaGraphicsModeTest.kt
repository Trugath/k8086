package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.video.Cga
import com.trugath.k8086.video.CgaComposite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pixel-level CGA APA graphics (modes 4/5/6), analogous to VGA Mode 13h/Y tests.
 */
class CgaGraphicsModeTest {
    @Test
    fun mode4PacksFourPixelsPerByteWithCyanMagentaPalette() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.compositeMode = CgaComposite.Mode.OFF
        // Graphics + video enable (mode 4-ish).
        cga.ioWriteByte(0x3D8, 0x0A)
        // High intensity + palette 1 (cyan/magenta/white), bg black.
        cga.ioWriteByte(0x3D9, 0x30)
        assertTrue(cga.isGraphicsMode())
        assertFalse(cga.isCompositeActive())

        // 11 10 01 00 → pixels 3,2,1,0
        cpu.writePhysByte(0xB8000, 0xE4)
        val snap = cga.copyFramebuffer()!!
        assertEquals(320, snap.width)
        assertEquals(200, snap.height)
        assertTrue(snap.graphicsMode)

        val pal = intArrayOf(
            Cga.CGA_PALETTE[0],  // bg
            Cga.CGA_PALETTE[3 + 8], // cyan hi
            Cga.CGA_PALETTE[5 + 8], // magenta hi
            Cga.CGA_PALETTE[7 + 8], // white hi
        )
        assertEquals(pal[3], snap.argb[0] and 0xFFFFFF)
        assertEquals(pal[2], snap.argb[1] and 0xFFFFFF)
        assertEquals(pal[1], snap.argb[2] and 0xFFFFFF)
        assertEquals(pal[0], snap.argb[3] and 0xFFFFFF)
    }

    @Test
    fun mode4OddScanlineUsesSecondBank() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.compositeMode = CgaComposite.Mode.OFF
        cga.ioWriteByte(0x3D8, 0x0A)
        cga.ioWriteByte(0x3D9, 0x10) // green/red/brown hi, bg black
        // Even line bank empty; odd line bank: solid colour 3 in first byte.
        cpu.writePhysByte(0xBA000, 0xC0) // 11 00 00 00 → pixel0=3
        val snap = cga.copyFramebuffer()!!
        val brownHi = Cga.CGA_PALETTE[6 + 8]
        assertEquals(Cga.CGA_PALETTE[0], snap.argb[0] and 0xFFFFFF, "even y=0 stays black")
        assertEquals(brownHi, snap.argb[1 * 320] and 0xFFFFFF, "odd y=1 from BA000")
    }

    @Test
    fun mode6HiResOneBitPixels() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.compositeMode = CgaComposite.Mode.OFF
        // Graphics + hi-res + B&W + video enable → RGB mode 6.
        cga.ioWriteByte(0x3D8, 0x1E)
        cga.ioWriteByte(0x3D9, 0x0F) // white foreground
        assertTrue(cga.isGraphicsMode())
        assertFalse(cga.isCompositeActive())

        // Bit7 set → leftmost pixel on.
        cpu.writePhysByte(0xB8000, 0x80)
        val snap = cga.copyFramebuffer()!!
        assertEquals(640, snap.width)
        assertEquals(200, snap.height)
        assertEquals(Cga.CGA_PALETTE[0x0F], snap.argb[0] and 0xFFFFFF)
        assertEquals(Cga.CGA_PALETTE[0], snap.argb[1] and 0xFFFFFF)
    }

    @Test
    fun mode6SecondByteStartsAtPixel8() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.compositeMode = CgaComposite.Mode.OFF
        cga.ioWriteByte(0x3D8, 0x1E)
        cga.ioWriteByte(0x3D9, 0x0E) // yellow FG
        cpu.writePhysByte(0xB8000, 0x00)
        cpu.writePhysByte(0xB8001, 0x01) // bit0 → pixel x=15
        val snap = cga.copyFramebuffer()!!
        assertEquals(Cga.CGA_PALETTE[0], snap.argb[8] and 0xFFFFFF)
        assertEquals(Cga.CGA_PALETTE[0x0E], snap.argb[15] and 0xFFFFFF)
    }

    @Test
    fun mode4Palette0GreenRedBrownLowIntensity() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.compositeMode = CgaComposite.Mode.OFF
        cga.ioWriteByte(0x3D8, 0x0A)
        cga.ioWriteByte(0x3D9, 0x00) // palette 0, low, bg black
        cpu.writePhysByte(0xB8000, 0x40) // 01 00 00 00 → pixel0 colour 1 = green
        val snap = cga.copyFramebuffer()!!
        assertEquals(Cga.CGA_PALETTE[2], snap.argb[0] and 0xFFFFFF)
    }

    @Test
    fun switchingToTextLeavesGraphicsFlagClear() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x0A)
        assertTrue(cga.isGraphicsMode())
        cga.ioWriteByte(0x3D8, 0x29) // 80-col text
        assertFalse(cga.isGraphicsMode())
        cpu.writePhysByte(0xB8000, 'Q'.code)
        cpu.writePhysByte(0xB8001, 0x07)
        val snap = cga.copyFramebuffer()!!
        assertEquals(640, snap.width)
        assertFalse(snap.graphicsMode)
        // Glyph 'Q' should light at least one non-black pixel.
        assertTrue(snap.argb.any { (it and 0xFFFFFF) == Cga.CGA_PALETTE[0x07] })
    }
}
