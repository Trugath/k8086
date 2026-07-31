package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CgaCompositeTest {

    @Test
    fun mode6AutoEnablesWhenColorBurstOn() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        // Mode 6: graphics + hi-res + video enable, color burst ON (bit2 clear).
        cga.ioWriteByte(0x3D8, 0x1A)
        cga.ioWriteByte(0x3D9, 0x0F) // white FG
        assertTrue(cga.isCompositeActive(), "AUTO should enable composite for mode 6 with burst")
    }

    @Test
    fun mode6AutoDisablesWhenColorBurstOff() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        // Mode 6 with B&W bit set (bit2) → no color burst.
        cga.ioWriteByte(0x3D8, 0x1E)
        cga.ioWriteByte(0x3D9, 0x0F)
        assertFalse(cga.isCompositeActive(), "AUTO should stay RGB when burst is disabled")
    }

    @Test
    fun mode4StaysRgbUnderAuto() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x0A) // graphics, not hi-res
        cga.ioWriteByte(0x3D9, 0x30) // cyan/magenta palette
        assertFalse(cga.isCompositeActive(), "AUTO should not force composite for mode 4")
    }

    @Test
    fun mode4CompositeWhenForcedOn() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.compositeMode = CgaComposite.Mode.ON
        cga.ioWriteByte(0x3D8, 0x0A)
        cga.ioWriteByte(0x3D9, 0x30)
        assertTrue(cga.isCompositeActive(), "ON should composite any graphics mode")
    }

    @Test
    fun whiteForegroundSolidPatternsMapToDistinctColors() {
        val lut = CgaComposite.rebuild(colorSelect = 0x0F, modeControl = 0x1A).mode6
        assertEquals(0x000000, lut[0b0000], "0000 → black")
        // 1111 must be near-white (reenigne gamma path is not exactly #FFFFFF).
        val white = lut[0b1111]
        val wr = (white shr 16) and 0xFF
        val wg = (white shr 8) and 0xFF
        val wb = white and 0xFF
        assertTrue(wr > 200 && wg > 200 && wb > 200, "1111 should be bright, was #${white.toString(16)}")
        assertNotEquals(lut[0b0001], lut[0b0010], "different patterns yield different artifact colors")
        assertNotEquals(lut[0b0100], lut[0b1000])
    }

    @Test
    fun compositeMode6RendersNibbleColorsIntoFramebuffer() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x1A)
        cga.ioWriteByte(0x3D9, 0x0F)

        // Even scanline 0, first byte: high nibble 1111 (white), low nibble 0000 (black).
        cpu.writePhysByte(0xB8000, 0xF0)
        cga.renderFrame()

        val lut = CgaComposite.rebuild(0x0F, 0x1A).mode6
        // Peek via PNG round-trip would be heavy; instead re-compose through a fresh
        // image by reading pixels from a second Cga after writeFrame is awkward.
        // Spot-check LUT wiring: solid pattern 0xFF fills a run with near-white.
        cpu.writePhysByte(0xB8000, 0xFF)
        val tmp = java.io.File.createTempFile("k8086-comp", ".png")
        tmp.deleteOnExit()
        cga.writeFramePng(tmp)
        val img = javax.imageio.ImageIO.read(tmp)
        assertEquals(640, img.width)
        assertEquals(200, img.height)
        val px = img.getRGB(0, 0) and 0xFFFFFF
        assertEquals(lut[0xF] and 0xFFFFFF, px, "first artifact pixel should match pattern 1111")
        // Byte boundary: second nibble of 0xFF is also 1111 → x=4 same color.
        assertEquals(px, img.getRGB(4, 0) and 0xFFFFFF)
    }

    @Test
    fun cycleCompositeModeVisitsAllStates() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x1A)
        assertEquals(CgaComposite.Mode.AUTO, cga.compositeMode)
        cga.cycleCompositeMode()
        assertEquals(CgaComposite.Mode.ON, cga.compositeMode)
        cga.cycleCompositeMode()
        assertEquals(CgaComposite.Mode.OFF, cga.compositeMode)
        assertFalse(cga.isCompositeActive())
        cga.cycleCompositeMode()
        assertEquals(CgaComposite.Mode.AUTO, cga.compositeMode)
        assertTrue(cga.isCompositeActive())
    }

    @Test
    fun hueOffsetChangesLut() {
        val a = CgaComposite.rebuild(0x0F, 0x1A, hueOffsetDeg = 0.0).mode6[1]
        val b = CgaComposite.rebuild(0x0F, 0x1A, hueOffsetDeg = 60.0).mode6[1]
        assertNotEquals(a, b, "hue dial should rotate artifact colors")
    }
}
