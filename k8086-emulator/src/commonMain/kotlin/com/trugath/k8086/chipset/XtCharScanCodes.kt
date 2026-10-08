package com.trugath.k8086.chipset

/**
 * Map characters to IBM XT scan-code set 1 sequences (US QWERTY).
 * Used for clipboard paste into the emulated keyboard.
 */
object XtCharScanCodes {
    private const val LSHIFT = 0x2A
    private val DIGIT = intArrayOf(
        0x0B, // 0
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, // 1-9
    )

    /**
     * Emit make/break scancodes for [text] via [emit].
     * Skips unmapped characters. Caps length at [maxChars].
     * Treats `\r`, `\n`, and `\r\n` as Enter.
     */
    fun paste(text: String, emit: (Int) -> Unit, maxChars: Int = 4096) {
        var i = 0
        val limit = minOf(text.length, maxChars)
        while (i < limit) {
            val c = text[i]
            when (c) {
                '\r' -> {
                    typeKey(0x1C, emit)
                    if (i + 1 < limit && text[i + 1] == '\n') i++
                }
                '\n' -> typeKey(0x1C, emit)
                else -> {
                    val stroke = strokeFor(c)
                    if (stroke != null) {
                        if (stroke.shift) {
                            emit(LSHIFT)
                            typeKey(stroke.make, emit)
                            emit(LSHIFT or 0x80)
                        } else {
                            typeKey(stroke.make, emit)
                        }
                    }
                }
            }
            i++
        }
    }

    private fun typeKey(make: Int, emit: (Int) -> Unit) {
        emit(make)
        emit(make or 0x80)
    }

    private data class Stroke(val make: Int, val shift: Boolean)

    private fun letterMake(lower: Char): Int = when (lower) {
        'q' -> 0x10; 'w' -> 0x11; 'e' -> 0x12; 'r' -> 0x13
        't' -> 0x14; 'y' -> 0x15; 'u' -> 0x16; 'i' -> 0x17
        'o' -> 0x18; 'p' -> 0x19
        'a' -> 0x1E; 's' -> 0x1F; 'd' -> 0x20; 'f' -> 0x21
        'g' -> 0x22; 'h' -> 0x23; 'j' -> 0x24; 'k' -> 0x25
        'l' -> 0x26
        'z' -> 0x2C; 'x' -> 0x2D; 'c' -> 0x2E; 'v' -> 0x2F
        'b' -> 0x30; 'n' -> 0x31; 'm' -> 0x32
        else -> -1
    }

    private fun strokeFor(c: Char): Stroke? {
        when (c) {
            in 'a'..'z' -> {
                val m = letterMake(c)
                return if (m >= 0) Stroke(m, false) else null
            }
            in 'A'..'Z' -> {
                val m = letterMake(c.lowercaseChar())
                return if (m >= 0) Stroke(m, true) else null
            }
            in '0'..'9' -> return Stroke(DIGIT[c - '0'], false)
            ' ' -> return Stroke(0x39, false)
            '\t' -> return Stroke(0x0F, false)
            '`' -> return Stroke(0x29, false)
            '~' -> return Stroke(0x29, true)
            '!' -> return Stroke(0x02, true)
            '@' -> return Stroke(0x03, true)
            '#' -> return Stroke(0x04, true)
            '$' -> return Stroke(0x05, true)
            '%' -> return Stroke(0x06, true)
            '^' -> return Stroke(0x07, true)
            '&' -> return Stroke(0x08, true)
            '*' -> return Stroke(0x09, true)
            '(' -> return Stroke(0x0A, true)
            ')' -> return Stroke(0x0B, true)
            '-' -> return Stroke(0x0C, false)
            '_' -> return Stroke(0x0C, true)
            '=' -> return Stroke(0x0D, false)
            '+' -> return Stroke(0x0D, true)
            '[' -> return Stroke(0x1A, false)
            '{' -> return Stroke(0x1A, true)
            ']' -> return Stroke(0x1B, false)
            '}' -> return Stroke(0x1B, true)
            '\\' -> return Stroke(0x2B, false)
            '|' -> return Stroke(0x2B, true)
            ';' -> return Stroke(0x27, false)
            ':' -> return Stroke(0x27, true)
            '\'' -> return Stroke(0x28, false)
            '"' -> return Stroke(0x28, true)
            ',' -> return Stroke(0x33, false)
            '<' -> return Stroke(0x33, true)
            '.' -> return Stroke(0x34, false)
            '>' -> return Stroke(0x34, true)
            '/' -> return Stroke(0x35, false)
            '?' -> return Stroke(0x35, true)
            else -> return null
        }
    }
}
