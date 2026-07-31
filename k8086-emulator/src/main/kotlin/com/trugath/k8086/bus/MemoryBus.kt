package com.trugath.k8086.bus

import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.cpu.RAM_SIZE

/**
 * Guest physical-memory decode: backing RAM plus overlays (ROM / MMIO).
 * More recently mapped overlays win on overlap (fail-fast on conflicting non-RAM).
 *
 * Hot path: ROM/RAM are copied into [backing] at map time, so [read8]/[write8] only
 * walk MMIO (and ROM write-protect) overlays — not every mapped window.
 */
class MemoryBus(val size: Int = RAM_SIZE) {
    val backing = ByteArray(size)

    private data class Overlay(
        val region: MemoryRegion,
        val owner: String,
        val base: Int = region.base,
        val end: Int = region.end,
    )

    /** All overlays (newest first) — used for conflict checks and [isRom]. */
    private val overlays = mutableListOf<Overlay>()

    /**
     * MMIO windows only (newest first). Indexed array avoids ArrayList iterators
     * on every guest byte access.
     */
    private var mmioOverlays: Array<Overlay> = emptyArray()

    /** ROM windows (newest first) for write-protect and [isRom]. */
    private var romOverlays: Array<Overlay> = emptyArray()

    /**
     * Inclusive/exclusive bounding box of all MMIO windows. Guest addresses outside
     * this range skip the MMIO scan (common for conventional RAM / BIOS ROM).
     */
    private var mmioLo = Int.MAX_VALUE
    private var mmioHi = 0

    fun map(region: MemoryRegion, owner: String) {
        require(region.base >= 0 && region.length > 0) { "Invalid memory region" }
        require(region.end <= size) {
            "Memory region end 0x${region.end.toString(16)} exceeds bus size 0x${size.toString(16)}"
        }
        for (existing in overlays) {
            if (overlaps(existing.region, region)) {
                val a = existing.region
                val b = region
                if (a is MemoryRegion.Ram && b !is MemoryRegion.Ram) continue
                if (b is MemoryRegion.Ram && a !is MemoryRegion.Ram) continue
                if (a is MemoryRegion.Ram && b is MemoryRegion.Ram) continue
                throw IllegalStateException(
                    "Memory conflict: '$owner' 0x${region.base.toString(16)}-0x${(region.end - 1).toString(16)} " +
                        "overlaps '${existing.owner}' 0x${a.base.toString(16)}-0x${(a.end - 1).toString(16)}"
                )
            }
        }
        when (region) {
            is MemoryRegion.Ram -> {
                System.arraycopy(region.backing, 0, backing, region.base, region.length)
            }
            is MemoryRegion.Rom -> {
                System.arraycopy(region.backing, 0, backing, region.base, region.length)
            }
            is MemoryRegion.Mmio -> { /* device-backed */ }
        }
        // Newest overlays searched first.
        overlays.add(0, Overlay(region, owner))
        rebuildHotArrays()
    }

    private fun rebuildHotArrays() {
        val mmio = ArrayList<Overlay>(overlays.size)
        val rom = ArrayList<Overlay>(overlays.size)
        var lo = Int.MAX_VALUE
        var hi = 0
        for (o in overlays) {
            when (o.region) {
                is MemoryRegion.Mmio -> {
                    mmio.add(o)
                    if (o.base < lo) lo = o.base
                    if (o.end > hi) hi = o.end
                }
                is MemoryRegion.Rom -> rom.add(o)
                is MemoryRegion.Ram -> { /* already in backing */ }
            }
        }
        mmioOverlays = mmio.toTypedArray()
        romOverlays = rom.toTypedArray()
        mmioLo = lo
        mmioHi = hi
    }

    fun read8(addr: Int): Int {
        if (addr < 0 || addr >= size) return 0xFF
        // Fast path: no MMIO, or address outside all MMIO windows (typical RAM/ROM).
        if (addr >= mmioLo && addr < mmioHi) {
            val mmio = mmioOverlays
            for (i in mmio.indices) {
                val o = mmio[i]
                if (addr >= o.base && addr < o.end) {
                    val r = o.region as MemoryRegion.Mmio
                    return r.device.memReadByte(addr - o.base) and 0xFF
                }
            }
        }
        return backing[addr].toInt() and 0xFF
    }

    fun write8(addr: Int, value: Int) {
        if (addr < 0 || addr >= size) return
        val v = value and 0xFF
        if (addr >= mmioLo && addr < mmioHi) {
            val mmio = mmioOverlays
            for (i in mmio.indices) {
                val o = mmio[i]
                if (addr >= o.base && addr < o.end) {
                    val r = o.region as MemoryRegion.Mmio
                    r.device.memWriteByte(addr - o.base, v)
                    return
                }
            }
        }
        val rom = romOverlays
        for (i in rom.indices) {
            val o = rom[i]
            if (addr >= o.base && addr < o.end) return // write-protected
        }
        backing[addr] = v.toByte()
    }

    fun isRom(addr: Int): Boolean {
        val rom = romOverlays
        for (i in rom.indices) {
            val o = rom[i]
            if (addr >= o.base && addr < o.end) return true
        }
        return false
    }

    private fun overlaps(a: MemoryRegion, b: MemoryRegion): Boolean =
        a.base < b.end && b.base < a.end
}
