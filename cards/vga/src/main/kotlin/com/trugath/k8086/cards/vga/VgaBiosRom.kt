package com.trugath.k8086.cards.vga

/**
 * VGA option ROM at C000:0000 — INT 10h for modes 03h / 13h + teletype.
 *
 * Soft ports (handled in [VgaCard]):
 * - [SOFT_MODE_PORT] (0x1CE): set video mode (AL)
 * - [SOFT_ATTR_PORT] (0x1CD): teletype attribute (BL)
 * - [SOFT_TTY_PORT] (0x1CF): teletype character (AL)
 */
object VgaBiosRom {
    const val SOFT_ATTR_PORT = 0x1CD
    const val SOFT_MODE_PORT = 0x1CE
    const val SOFT_TTY_PORT = 0x1CF
    private const val INT10_OFF = 0x80

    fun build(): ByteArray {
        val a = Asm()
        // Option ROM header
        a.emit(0x55, 0xAA, 0x04)

        // --- INIT ---
        a.emit(0xFA) // CLI
        a.emit(0x50, 0x52, 0x1E, 0x06, 0x53) // push ax,dx,ds,es,bx
        a.emit(0x31, 0xC0) // xor ax,ax
        a.emit(0x8E, 0xD8) // mov ds,ax
        a.emit(0x8E, 0xC0) // mov es,ax
        // IVT INT10 = CS:int10_off
        a.emit(0xC7, 0x06); a.emitWord(0x0040); a.emitWord(INT10_OFF)
        a.emit(0x8C, 0x0E); a.emitWord(0x0042)
        // Soft set mode 03
        a.emit(0xBA); a.emitWord(SOFT_MODE_PORT)
        a.emit(0xB0, 0x03)
        a.emit(0xEE)
        // BDA
        a.emit(0xC6, 0x06); a.emitWord(0x0449); a.emit(0x03)
        a.emit(0xC7, 0x06); a.emitWord(0x044A); a.emitWord(80)
        a.emit(0xC7, 0x06); a.emitWord(0x044C); a.emitWord(0x1000)
        a.emit(0xC7, 0x06); a.emitWord(0x0450); a.emitWord(0)
        a.emit(0xC6, 0x06); a.emitWord(0x0462); a.emit(0x00)
        a.emit(0x5B, 0x07, 0x1F, 0x5A, 0x58) // pop bx,es,ds,dx,ax
        a.emit(0xFB, 0xCB) // STI; RETF

        while (a.pc < INT10_OFF) a.emit(0x90)
        check(a.pc == INT10_OFF) { "INT10 must be at $INT10_OFF, got ${a.pc}" }

        // --- INT 10h dispatcher ---
        // AH=0Fh get mode
        a.emit(0x80, 0xFC, 0x0F)
        a.jne("not_0f")
        a.emit(0x1E) // push ds
        a.emit(0x31, 0xC0, 0x8E, 0xD8)
        a.emit(0xA0); a.emitWord(0x0449) // al=mode
        a.emit(0xB4, 0x50) // ah=80
        a.emit(0xB7, 0x00) // bh=0
        a.emit(0x1F) // pop ds
        a.emit(0xCF) // iret
        a.label("not_0f")

        // AH=00h set mode
        a.emit(0x80, 0xFC, 0x00)
        a.jne("not_00")
        a.emit(0x50, 0x52, 0x1E) // push ax,dx,ds
        a.emit(0xBA); a.emitWord(SOFT_MODE_PORT)
        a.emit(0xEE) // out dx,al
        a.emit(0x31, 0xC0, 0x8E, 0xD8)
        a.emit(0xA2); a.emitWord(0x0449) // [449]=al
        a.emit(0x3C, 0x13)
        a.je("mode13_bda")
        a.emit(0xC7, 0x06); a.emitWord(0x044A); a.emitWord(80)
        a.emit(0xC7, 0x06); a.emitWord(0x044C); a.emitWord(0x1000)
        a.jmp("mode_bda_done")
        a.label("mode13_bda")
        a.emit(0xC7, 0x06); a.emitWord(0x044A); a.emitWord(40)
        a.emit(0xC7, 0x06); a.emitWord(0x044C); a.emitWord(0x3E80)
        a.label("mode_bda_done")
        a.emit(0xC7, 0x06); a.emitWord(0x0450); a.emitWord(0)
        a.emit(0x1F, 0x5A, 0x58)
        a.emit(0xCF)
        a.label("not_00")

        // AH=02h set cursor position (DH=row, DL=col)
        a.emit(0x80, 0xFC, 0x02)
        a.jne("not_02")
        a.emit(0x1E)
        a.emit(0x50) // push ax (save)
        a.emit(0x31, 0xC0, 0x8E, 0xD8)
        a.emit(0x88, 0x16); a.emitWord(0x0450) // [450]=dl
        a.emit(0x88, 0x36); a.emitWord(0x0451) // [451]=dh
        a.emit(0x58, 0x1F)
        a.emit(0xCF)
        a.label("not_02")

        // AH=03h get cursor
        a.emit(0x80, 0xFC, 0x03)
        a.jne("not_03")
        a.emit(0x1E)
        a.emit(0x31, 0xC0, 0x8E, 0xD8)
        a.emit(0x8A, 0x16); a.emitWord(0x0450) // dl
        a.emit(0x8A, 0x36); a.emitWord(0x0451) // dh
        a.emit(0xB5, 0x00) // ch=0
        a.emit(0xB1, 0x07) // cl=7
        a.emit(0x1F)
        a.emit(0xCF)
        a.label("not_03")

        // AH=0Eh teletype
        a.emit(0x80, 0xFC, 0x0E)
        a.jne("not_0e")
        a.emit(0x50, 0x52, 0x53) // push ax,dx,bx
        a.emit(0xBA); a.emitWord(SOFT_ATTR_PORT)
        a.emit(0x88, 0xD8) // mov al,bl
        a.emit(0xEE)
        a.emit(0x58) // pop ax (char)
        a.emit(0x50) // push ax again
        a.emit(0xBA); a.emitWord(SOFT_TTY_PORT)
        a.emit(0xEE)
        a.emit(0x5B, 0x5A, 0x58)
        a.emit(0xCF)
        a.label("not_0e")

        // AH=1Ah — Display Combination Code (Wolf VL_VideoID / FindPS2)
        // AX=1A00 → AL=1Ah, BL=08h (VGA + color), BH=00h
        a.emit(0x80, 0xFC, 0x1A)
        a.jne("not_1a")
        a.emit(0x3C, 0x00) // AL==0 get DCC
        a.jne("not_1a_get")
        a.emit(0xB0, 0x1A) // AL = function supported
        a.emit(0xB3, 0x08) // BL = VGA color DCC
        a.emit(0xB7, 0x00) // BH = no inactive
        a.emit(0xCF)
        a.label("not_1a_get")
        a.emit(0xCF) // other 1Ah subfns: accept
        a.label("not_1a")

        // AH=12h BL=10h — EGA info (optional; FindEGA if 1Ah missing)
        a.emit(0x80, 0xFC, 0x12)
        a.jne("not_12")
        a.emit(0x80, 0xFB, 0x10) // BL==10h?
        a.jne("not_12")
        a.emit(0xB3, 0x00) // BL≠10 → EGA BIOS present
        a.emit(0xB1, 0x09) // CL = switches (color display)
        a.emit(0xB7, 0x00) // BH = 64K+ mem config
        a.emit(0xCF)
        a.label("not_12")

        // AH=10h AL=01h — set overscan/border colour (BH) via ATC reg 11h
        a.emit(0x80, 0xFC, 0x10)
        a.jne("not_10")
        a.emit(0x3C, 0x01)
        a.jne("not_10")
        a.emit(0x50, 0x52) // push ax, dx
        a.emit(0xBA); a.emitWord(0x03DA)
        a.emit(0xEC) // in al,dx — reset ATC flip-flop
        a.emit(0xBA); a.emitWord(0x03C0)
        a.emit(0xB0, 0x31) // index 11h | PAS
        a.emit(0xEE)
        a.emit(0x88, 0xF8) // mov al,bh
        a.emit(0xEE)
        a.emit(0x5A, 0x58)
        a.emit(0xCF)
        a.label("not_10")

        // default
        a.emit(0xCF)

        val rom = ByteArray(2048)
        val raw = a.finish()
        require(raw.size <= 2047) { "VGA BIOS too large: ${raw.size}" }
        raw.copyInto(rom)
        var sum = 0
        for (i in 0 until 2047) sum = (sum + (rom[i].toInt() and 0xFF)) and 0xFF
        rom[2047] = ((0 - sum) and 0xFF).toByte()
        return rom
    }

    /** Tiny 8086 assembler with short-jump fixups. */
    private class Asm {
        private val buf = ArrayList<Int>(256)
        private val labels = HashMap<String, Int>()
        private val fixups = ArrayList<Fixup>()

        val pc: Int get() = buf.size

        fun emit(vararg bytes: Int) {
            for (b in bytes) buf.add(b and 0xFF)
        }

        fun emitWord(w: Int) {
            emit(w and 0xFF, (w ushr 8) and 0xFF)
        }

        fun label(name: String) {
            labels[name] = buf.size
        }

        fun jne(name: String) {
            emit(0x75, 0x00)
            fixups.add(Fixup(buf.size - 1, name))
        }

        fun je(name: String) {
            emit(0x74, 0x00)
            fixups.add(Fixup(buf.size - 1, name))
        }

        fun jmp(name: String) {
            emit(0xEB, 0x00)
            fixups.add(Fixup(buf.size - 1, name))
        }

        fun finish(): ByteArray {
            for (f in fixups) {
                val target = labels[f.label] ?: error("undefined label ${f.label}")
                val rel = target - (f.at + 1)
                require(rel in -128..127) { "jump to ${f.label} out of range ($rel)" }
                buf[f.at] = rel and 0xFF
            }
            return ByteArray(buf.size) { i -> buf[i].toByte() }
        }

        private data class Fixup(val at: Int, val label: String)
    }
}
