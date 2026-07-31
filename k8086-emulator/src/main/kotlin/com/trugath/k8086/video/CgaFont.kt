package com.trugath.k8086.video

/**
 * IBM CGA text-mode character generator: 256 glyphs × 8 rows (CP437).
 *
 * The XT system BIOS only stores glyphs 0x00–0x7F at F000:FA6E. Real CGA hardware
 * has a full 256-character ROM on the adapter; masking high codes with 0x7F maps
 * box-drawing onto ASCII (e.g. 0xCD `═` → 0x4D `M`).
 *
 * Source: IBM CGA.F08 from the [vga-text-mode-fonts](https://github.com/viler-int10h/vga-text-mode-fonts)
 * collection (raw VGA/CGA BIOS font format).
 */
internal object CgaFont {
    private const val RESOURCE = "/com/trugath/k8086/video/cga_font_8x8.bin"
    private const val BYTES = 256 * 8

    val bytes: ByteArray = run {
        val stream = CgaFont::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing CGA font resource $RESOURCE")
        stream.use { it.readBytes() }.also {
            require(it.size == BYTES) { "CGA font must be $BYTES bytes (got ${it.size})" }
        }
    }

    fun row(ch: Int, y: Int): Int =
        bytes[(ch and 0xFF) * 8 + (y and 7)].toInt() and 0xFF
}
