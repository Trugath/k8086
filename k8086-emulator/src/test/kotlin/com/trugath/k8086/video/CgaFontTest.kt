package com.trugath.k8086.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CgaFontTest {
    @Test
    fun highAsciiBoxDrawingNotMaskedToAscii() {
        // Regression: drawGlyph used (ch and 0x7F), so CP437 0xCD (═) looked like 'M' (0x4D).
        val m = (0 until 8).map { CgaFont.row(0x4D, it) }
        val doubleHoriz = (0 until 8).map { CgaFont.row(0xCD, it) }
        assertFalse(m == doubleHoriz, "═ must not share glyphs with M")

        val singleHoriz = (0 until 8).map { CgaFont.row(0xC4, it) }
        val d = (0 until 8).map { CgaFont.row(0x44, it) }
        assertFalse(singleHoriz == d, "─ must not share glyphs with D")

        // ═ has two solid horizontal scanlines; 'M' does not.
        assertEquals(0xFF, doubleHoriz[2])
        assertEquals(0xFF, doubleHoriz[4])
    }

    @Test
    fun fontHasAll256Glyphs() {
        assertEquals(2048, CgaFont.bytes.size)
        // Full block should be solid.
        for (y in 0 until 8) assertEquals(0xFF, CgaFont.row(0xDB, y))
        // Space / NUL are blank.
        for (y in 0 until 8) {
            assertEquals(0, CgaFont.row(0x20, y))
            assertEquals(0, CgaFont.row(0x00, y))
        }
        assertTrue(CgaFont.row(0x41, 1) != 0, "'A' has pixels")
    }
}
