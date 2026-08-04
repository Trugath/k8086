package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pixel-level Mode 13h / Mode Y coverage (Wolf-style), analogous to [CgaGraphicsModeTest].
 */
class VgaGraphicsModeTest {
    @Test
    fun mode13hChain4MapsAddressLowBitsToPlane() {
        val v = VgaCore()
        v.setMode13h()
        assertTrue(v.chain4())
        assertFalse(v.isTextMode())

        // Linear offsets 0..3 → planes 0..3 at addr 0.
        for (i in 0 until 4) {
            v.memWrite(i, 0xA0 + i)
            v.dac[0xA0 + i] = (0x11 * (i + 1)) shl 16
        }
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        for (x in 0 until 4) {
            assertEquals(((0x11 * (x + 1)) shl 16) or 0xFF000000.toInt(), out[x])
        }
        // Offset 4 → plane 0 addr 1
        v.memWrite(4, 0x55)
        v.dac[0x55] = 0x0000FF00
        v.composeFrame(out)
        assertEquals(0xFF00FF00.toInt(), out[4])
    }

    @Test
    fun mode13hRowStrideIsEightyChain4Bytes() {
        val v = VgaCore()
        v.setMode13h()
        // Pixel (0,1) is linear offset 320 → plane 0 addr 80.
        v.planes[0][80] = 0x7E
        v.dac[0x7E] = 0x000000FF
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(0xFF0000FF.toInt(), out[320])
        assertEquals(v.dac[0] or 0xFF000000.toInt(), out[0])
    }

    @Test
    fun wolfDePlaneSequenceEnablesModeYWrites() {
        val v = VgaCore()
        v.setMode13h()
        // VL_DePlaneVGA: SC_MEMMODE &= ~8 | 4
        v.ioWrite(0x3C4, 4)
        val memMode = (v.ioRead(0x3C5) and 0xF7) or 0x04
        v.ioWrite(0x3C5, memMode)
        assertFalse(v.chain4())

        // GC_MODE &= ~0x13
        v.ioWrite(0x3CE, 5)
        v.ioWrite(0x3CF, v.ioRead(0x3CF) and 0xEC)
        // GC_MISC &= ~2
        v.ioWrite(0x3CE, 6)
        v.ioWrite(0x3CF, v.ioRead(0x3CF) and 0xFD)

        // Map mask plane 2 only, write one byte → only plane 2.
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x04)
        v.memWrite(10, 0x42)
        assertEquals(0, v.planes[0][10].toInt() and 0xFF)
        assertEquals(0, v.planes[1][10].toInt() and 0xFF)
        assertEquals(0x42, v.planes[2][10].toInt() and 0xFF)
        assertEquals(0, v.planes[3][10].toInt() and 0xFF)

        v.dac[0x42] = 0x00C0FFEE
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        // addr 10 → x = 10*4 + plane = 42 on scanline 0
        assertEquals(0xFFC0FFEE.toInt(), out[10 * 4 + 2])
    }

    @Test
    fun modeYAllPlanesMapMaskWritesSameByte() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, 0x06)
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x0F)
        v.memWrite(0, 0x99)
        for (p in 0 until 4) {
            assertEquals(0x99, v.planes[p][0].toInt() and 0xFF)
        }
        v.dac[0x99] = 0x00ABCDEF
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        for (x in 0 until 4) {
            assertEquals(0xFFABCDEF.toInt(), out[x])
        }
    }

    @Test
    fun modeYPageFlipViaCrtcStartAddress() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, 0x06)
        // Page 0 empty; page at start=80*100 = 8000 — put colour on plane0[8000]
        val page = 80 * 100
        v.planes[0][page] = 0x31
        v.dac[0x31] = 0x00112233
        v.ioWrite(0x3D4, 0x0C)
        v.ioWrite(0x3D5, (page ushr 8) and 0xFF)
        v.ioWrite(0x3D4, 0x0D)
        v.ioWrite(0x3D5, page and 0xFF)
        assertEquals(page, v.startAddress())
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(0xFF112233.toInt(), out[0])
    }

    @Test
    fun setMode13hClearsPlanesAndLeavesTextModeFlagClear() {
        val v = VgaCore()
        v.setMode03h()
        v.textBuffer[0] = 'A'.code.toByte()
        v.setMode13h()
        assertFalse(v.isTextMode())
        assertEquals(0x13, v.biosMode)
        assertEquals(0, v.planes[0][0].toInt() and 0xFF)
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        // Identity DAC index 0 → black
        assertEquals(0xFF000000.toInt(), out[0])
    }

    @Test
    fun graphicsThenTextRestoreClearsScreenCells() {
        val v = VgaCore()
        v.setMode13h()
        v.memWrite(0, 0xFF)
        v.setMode03h()
        assertTrue(v.isTextMode())
        assertEquals(0x20, v.textBuffer[0].toInt() and 0xFF)
        assertEquals(0x07, v.textBuffer[1].toInt() and 0xFF)
        val out = IntArray(VgaCore.TEXT_PIXEL_W * VgaCore.TEXT_PIXEL_H)
        v.composeTextFrame(out)
        // Space glyph: all background (attr 07 → bg 0)
        assertTrue(out.take(8).all { it == VgaCore.CGA_RGB[0] })
    }
}
