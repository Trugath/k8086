package com.trugath.k8086.chipset

/**
 * Microsoft serial mouse protocol (7-bit, 1200 baud, 3-byte packets).
 *
 * Byte 1: `01 LR Y7 Y6 X7 X6` (bit6 always 1 for sync)
 * Byte 2: `0  X5..X0`
 * Byte 3: `0  Y5..Y0`
 *
 * X/Y are 8-bit two's-complement deltas (positive X = right, positive Y = up).
 * L/R are 1 when the corresponding button is pressed.
 */
object MicrosoftSerialMouse {
    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2

    /** Encode one motion/button update into a 3-byte Microsoft packet. */
    fun encode(dx: Int, dy: Int, buttons: Int): IntArray {
        val x = dx.coerceIn(-128, 127)
        val y = dy.coerceIn(-128, 127)
        val b1 = 0x40 or
            (if ((buttons and BUTTON_LEFT) != 0) 0x20 else 0) or
            (if ((buttons and BUTTON_RIGHT) != 0) 0x10 else 0) or
            ((y shr 4) and 0x0C) or
            ((x shr 6) and 0x03)
        val b2 = x and 0x3F
        val b3 = y and 0x3F
        return intArrayOf(b1, b2, b3)
    }

    /** Decode a synced 3-byte packet; returns null if byte0 lacks the sync bit. */
    fun decode(b0: Int, b1: Int, b2: Int): Decoded? {
        if ((b0 and 0x40) == 0) return null
        var x = ((b0 and 0x03) shl 6) or (b1 and 0x3F)
        var y = ((b0 and 0x0C) shl 4) or (b2 and 0x3F)
        if ((x and 0x80) != 0) x -= 256
        if ((y and 0x80) != 0) y -= 256
        var buttons = 0
        if ((b0 and 0x20) != 0) buttons = buttons or BUTTON_LEFT
        if ((b0 and 0x10) != 0) buttons = buttons or BUTTON_RIGHT
        return Decoded(x, y, buttons)
    }

    data class Decoded(val dx: Int, val dy: Int, val buttons: Int)
}

/**
 * Host-side adapter: relative mouse events → Microsoft serial bytes into a UART RX path.
 */
class SerialMouseAdapter(
    private val enqueueRx: (Int) -> Unit,
) {
    fun sendEvent(dx: Int, dy: Int, buttons: Int) {
        for (b in MicrosoftSerialMouse.encode(dx, dy, buttons)) {
            enqueueRx(b)
        }
    }
}
