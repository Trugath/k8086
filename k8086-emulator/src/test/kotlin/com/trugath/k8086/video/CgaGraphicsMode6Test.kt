package com.trugath.k8086.video

import com.trugath.k8086.cpu.Emulator8086
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RGBI (non-composite) CGA mode 6: 640×200 1bpp APA packing.
 * Composite mode 6 is covered separately in [com.trugath.k8086.CgaCompositeTest].
 */
class CgaGraphicsMode6Test {

    /** Mode 6 hi-res + video enable + B&W (bit2) so AUTO would stay RGB anyway. */
    private fun mode6Rgb(cga: Cga, colorSelect: Int = 0x0F) {
        cga.compositeMode = CgaComposite.Mode.OFF
        cga.ioWriteByte(0x3D8, 0x1E)
        cga.ioWriteByte(0x3D9, colorSelect)
    }

    private fun px(snap: FramebufferSnapshot, x: Int, y: Int): Int =
        snap.argb[y * snap.width + x] and 0xFFFFFF

    @Test
    fun mode6FramebufferIs640x200() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        mode6Rgb(cga)
        val snap = cga.copyFramebuffer()!!
        assertEquals(640, snap.width)
        assertEquals(200, snap.height)
        assertTrue(snap.graphicsMode)
        assertTrue(cga.isGraphicsMode())
        assertFalse(cga.isCompositeActive())
        assertTrue((cga.modeControlValue() and 0x10) != 0, "hi-res bit must be set")
    }

    @Test
    fun mode6PacksEightMsbFirstPixelsPerByte() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        mode6Rgb(cga, colorSelect = 0x0F) // white FG
        // 0xA5 = 10100101 → on,off,on,off,off,on,off,on
        cpu.writePhysByte(0xB8000, 0xA5)
        val snap = cga.copyFramebuffer()!!
        val fg = Cga.CGA_PALETTE[0x0F]
        val bg = Cga.CGA_PALETTE[0x00]
        val expected = booleanArrayOf(true, false, true, false, false, true, false, true)
        for (i in 0 until 8) {
            val want = if (expected[i]) fg else bg
            assertEquals(want, px(snap, i, 0), "pixel x=$i for pattern 0xA5")
        }
    }

    @Test
    fun mode6ForegroundComesFromColorSelectLowNibble() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        mode6Rgb(cga, colorSelect = 0x0E) // yellow
        cpu.writePhysByte(0xB8000, 0x80) // only MSB on → pixel 0 FG
        val snap = cga.copyFramebuffer()!!
        assertEquals(Cga.CGA_PALETTE[0x0E], px(snap, 0, 0), "on-pixel should be yellow")
        assertEquals(Cga.CGA_PALETTE[0x00], px(snap, 1, 0), "off-pixel should be black")
    }

    @Test
    fun mode6OddScanlineUsesBa000Bank() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        mode6Rgb(cga, colorSelect = 0x0F)
        // Even bank clear; odd bank (y=1) solid on.
        cpu.writePhysByte(0xBA000, 0xFF)
        val snap = cga.copyFramebuffer()!!
        assertEquals(Cga.CGA_PALETTE[0x00], px(snap, 0, 0), "even line must stay black")
        assertEquals(Cga.CGA_PALETTE[0x0F], px(snap, 0, 1), "odd line reads 0xBA000")
        assertEquals(Cga.CGA_PALETTE[0x0F], px(snap, 7, 1))
    }

    @Test
    fun mode6EvenRowStrideIs80Bytes() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        mode6Rgb(cga, colorSelect = 0x0F)
        // Second even scanline (y=2) starts at B8000 + 80.
        cpu.writePhysByte(0xB8000 + 80, 0xFF)
        val snap = cga.copyFramebuffer()!!
        assertEquals(Cga.CGA_PALETTE[0x00], px(snap, 0, 0), "y=0 must stay clear")
        assertEquals(Cga.CGA_PALETTE[0x0F], px(snap, 0, 2), "y=2 uses +80 stride")
    }
}
