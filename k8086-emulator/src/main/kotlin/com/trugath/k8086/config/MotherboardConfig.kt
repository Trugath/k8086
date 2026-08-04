package com.trugath.k8086.config

import com.trugath.k8086.api.CpuModel
import kotlin.math.abs

/**
 * IBM 5155 / 5160 motherboard options (DIP SW1 + conventional RAM size).
 *
 * SW1 is read by POST via the 8255; [baseMemoryKb] caps how far POST’s memory
 * count walks through conventional RAM (below A0000h).
 */
data class MotherboardConfig(
    /** CPU socket: XT stock is 8088; 8086 / 80286 are selectable for the same ISA. */
    val cpu: CpuModel = CpuModel.I8088,
    /**
     * Guest CPU clock in MHz for realtime pacing (and audio/card sample timing).
     * Null uses [CpuClocks.defaultMhz] for [cpu]. XT stock is ~4.77;
     * Intel 80286 grades were 6 / 8 / 10 / 12.5.
     */
    val cpuMhz: Double? = null,
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
        require(cpuMhz == null || cpuMhz > 0.0) { "cpuMhz must be positive (got $cpuMhz)" }
    }

    /** Resolved MHz (explicit [cpuMhz] or the default for [cpu]). */
    fun effectiveCpuMhz(): Double = cpuMhz ?: CpuClocks.defaultMhz(cpu)

    /** Guest cycles/sec for realtime pacing. */
    fun clockHz(): Double = CpuClocks.toHz(effectiveCpuMhz())

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

        fun withCpu(cpu: CpuModel, cpuMhz: Double = CpuClocks.defaultMhz(cpu)): MotherboardConfig =
            MotherboardConfig(cpu = cpu, cpuMhz = cpuMhz)
    }
}

/** Clock-rate presets for realtime pacing (UI / CLI). */
object CpuClocks {
    /** Exact IBM PC/XT crystal. */
    const val XT_MHZ = 4.772727
    const val XT_HZ = 4_772_727.0

    data class Preset(val mhz: Double, val label: String)

    fun presets(cpu: CpuModel): List<Preset> = when (cpu) {
        CpuModel.I8088 -> listOf(Preset(XT_MHZ, "4.77 MHz (XT)"))
        CpuModel.I8086 -> listOf(
            Preset(XT_MHZ, "4.77 MHz"),
            Preset(8.0, "8 MHz"),
            Preset(10.0, "10 MHz"),
        )
        CpuModel.I80286 -> listOf(
            Preset(6.0, "6 MHz"),
            Preset(8.0, "8 MHz (early AT)"),
            Preset(10.0, "10 MHz"),
            Preset(12.5, "12.5 MHz"),
        )
    }

    fun defaultMhz(cpu: CpuModel): Double = when (cpu) {
        CpuModel.I8088 -> XT_MHZ
        CpuModel.I8086 -> 8.0
        CpuModel.I80286 -> 8.0
    }

    /** Map a UI/CLI MHz value to guest cycles/sec (XT uses the exact crystal). */
    fun toHz(mhz: Double): Double =
        if (abs(mhz - XT_MHZ) < 0.02 || abs(mhz - 4.77) < 0.02) XT_HZ else mhz * 1_000_000.0

    fun nearestPreset(cpu: CpuModel, mhz: Double): Preset {
        val opts = presets(cpu)
        return opts.minBy { abs(it.mhz - mhz) }
    }

    fun formatMhz(mhz: Double): String =
        if (abs(mhz - XT_MHZ) < 0.02 || abs(mhz - 4.77) < 0.02) {
            "4.77"
        } else if (mhz == mhz.toLong().toDouble()) {
            mhz.toLong().toString()
        } else {
            mhz.toString()
        }
}

/** Initial video mode reported in SW1 / INT 11h (bits 4–5). */
enum class InitialVideoMode(val sw1Bits: Int, val label: String) {
    SPECIAL_OR_NONE(0b00, "None / card with BIOS (EGA/VGA)"),
    CGA_40x25(0b01, "CGA 40×25"),
    CGA_80x25(0b10, "CGA 80×25"),
    MDA_80x25(0b11, "MDA 80×25"),
}
