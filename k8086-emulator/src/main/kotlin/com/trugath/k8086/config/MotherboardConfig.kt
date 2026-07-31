package com.trugath.k8086.config

import com.trugath.k8086.api.CpuModel

/**
 * IBM 5155 / 5160 motherboard options (DIP SW1 + conventional RAM size).
 *
 * SW1 is read by POST via the 8255; [baseMemoryKb] caps how far POST’s memory
 * count walks through conventional RAM (below A0000h).
 */
data class MotherboardConfig(
    /** CPU socket: XT stock is 8088; 8086 is selectable for the same ISA. */
    val cpu: CpuModel = CpuModel.I8088,
    /** Conventional RAM in KB (64…640). XT stock was often 256; 640 is typical for DOS. */
    val baseMemoryKb: Int = 640,
    /** Report 8087 present (SW1 bit 1) and enable the software numeric coprocessor. */
    val mathCoprocessor: Boolean = false,
    /** SW1 bits 4–5 initial video mode. */
    val initialVideo: InitialVideoMode = InitialVideoMode.CGA_80x25,
    /** Factory continuous-POST loop (rarely useful for normal use). */
    val postLoop: Boolean = false,
) {
    init {
        require(baseMemoryKb in MIN_MEMORY_KB..MAX_MEMORY_KB) {
            "baseMemoryKb must be $MIN_MEMORY_KB..$MAX_MEMORY_KB (got $baseMemoryKb)"
        }
    }

    /** End address (exclusive) of installed conventional RAM. */
    fun conventionalMemoryEnd(): Int = (baseMemoryKb * 1024).coerceIn(64 * 1024, 0xA0000)

    /**
     * SW1 byte as read by the CPU / INT 11h low byte, excluding floppy bits
     * (applied later from drive count via [com.trugath.k8086.chipset.Ppi8255.configureFloppyDrives]).
     *
     * bit1 = 8087; bits2–3 = motherboard RAM banks; bits4–5 = video.
     */
    fun sw1WithoutFloppies(): Int {
        var v = 0
        if (mathCoprocessor) v = v or 0x02
        v = v or ((motherboardRamBankBits() and 0x03) shl 2)
        v = v or ((initialVideo.sw1Bits and 0x03) shl 4)
        // postLoop is a physical SW1-1 factory mode; we keep normal IPL bit handling via floppies.
        return v and 0xFF
    }

    /** XT SW3/SW4 bank-enable encoding in equipment bits 2–3. */
    fun motherboardRamBankBits(): Int = when {
        baseMemoryKb <= 64 -> 0
        baseMemoryKb <= 128 -> 1
        baseMemoryKb <= 192 -> 2
        else -> 3 // 256 KB banks fully enabled; POST counts expansion above
    }

    companion object {
        const val MIN_MEMORY_KB = 64
        const val MAX_MEMORY_KB = 640
        val MEMORY_PRESETS_KB = listOf(64, 128, 256, 512, 640)
    }
}

/** Initial video mode reported in SW1 / INT 11h (bits 4–5). */
enum class InitialVideoMode(val sw1Bits: Int, val label: String) {
    SPECIAL_OR_NONE(0b00, "None / card with BIOS (EGA/VGA)"),
    CGA_40x25(0b01, "CGA 40×25"),
    CGA_80x25(0b10, "CGA 80×25"),
    MDA_80x25(0b11, "MDA 80×25"),
}
