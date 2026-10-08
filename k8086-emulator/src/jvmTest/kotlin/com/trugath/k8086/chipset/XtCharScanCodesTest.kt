package com.trugath.k8086.chipset

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XtCharScanCodesTest {
    @Test
    fun pasteLowercaseAndEnter() {
        val out = mutableListOf<Int>()
        XtCharScanCodes.paste("ab\n", out::add)
        // a make/break, b make/break, Enter make/break
        assertEquals(
            listOf(0x1E, 0x9E, 0x30, 0xB0, 0x1C, 0x9C),
            out,
        )
    }

    @Test
    fun pasteShiftedAndCrlf() {
        val out = mutableListOf<Int>()
        XtCharScanCodes.paste("A\r\n", out::add)
        // Shift+a, then single Enter for CRLF
        assertEquals(
            listOf(0x2A, 0x1E, 0x9E, 0xAA, 0x1C, 0x9C),
            out,
        )
    }

    @Test
    fun pasteDigitsAndPunctuation() {
        val out = mutableListOf<Int>()
        XtCharScanCodes.paste("1.", out::add)
        assertEquals(listOf(0x02, 0x82, 0x34, 0xB4), out)
    }
}
