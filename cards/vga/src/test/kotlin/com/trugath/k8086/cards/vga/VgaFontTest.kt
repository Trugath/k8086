package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors [com.trugath.k8086.video.CgaFontTest] for the VGA card font resource. */
class VgaFontTest {
    @Test
    fun highAsciiBoxDrawingNotMaskedToAscii() {
        val m = (0 until 8).map { VgaFont.row(0x4D, it) }
        val doubleHoriz = (0 until 8).map { VgaFont.row(0xCD, it) }
        assertFalse(m == doubleHoriz, "═ must not share glyphs with M")

        val singleHoriz = (0 until 8).map { VgaFont.row(0xC4, it) }
        val d = (0 until 8).map { VgaFont.row(0x44, it) }
        assertFalse(singleHoriz == d, "─ must not share glyphs with D")

        assertEquals(0xFF, doubleHoriz[2])
        assertEquals(0xFF, doubleHoriz[4])
    }

    @Test
    fun fontHasAll256Glyphs() {
        assertEquals(2048, VgaFont.bytes.size)
        for (y in 0 until 8) assertEquals(0xFF, VgaFont.row(0xDB, y))
        for (y in 0 until 8) {
            assertEquals(0, VgaFont.row(0x20, y))
            assertEquals(0, VgaFont.row(0x00, y))
        }
        assertTrue(VgaFont.row(0x41, 1) != 0, "'A' has pixels")
    }
}
