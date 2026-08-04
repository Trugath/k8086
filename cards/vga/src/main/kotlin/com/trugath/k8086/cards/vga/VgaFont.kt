package com.trugath.k8086.cards.vga

/** IBM CGA 8×8 CP437 font (same glyphs as motherboard CGA adapter). */
internal object VgaFont {
    private const val RESOURCE = "/com/trugath/k8086/cards/vga/cga_font_8x8.bin"
    private const val BYTES = 256 * 8

    val bytes: ByteArray = run {
        val stream = VgaFont::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing font resource $RESOURCE")
        stream.use { it.readBytes() }.also {
            require(it.size == BYTES) { "font must be $BYTES bytes (got ${it.size})" }
        }
    }

    fun row(ch: Int, y: Int): Int =
        bytes[(ch and 0xFF) * 8 + (y and 7)].toInt() and 0xFF
}
