package com.trugath.k8086.cards.emswindow

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.MemoryDevice
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class EmsWindowCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.ems-window",
        name = "EMS Page Frame",
        description = "LIM-style 64 KB page frame with remappable 16 KB windows " +
            "(pair with rmDOS EMM.SYS; write FFh to unmap a window).",
        category = "Memory",
        fields = listOf(
            ConfigField(
                "frame", "Page frame", ConfigFieldType.HEX_INT, "0xD0000",
                "16 KB-aligned frame base", affectsResources = true,
            ),
            ConfigField(
                "port", "I/O base", ConfigFieldType.HEX_INT, "0x260",
                "Four mapping ports at base..base+3", affectsResources = true,
            ),
            ConfigField(
                "pages", "Logical pages", ConfigFieldType.INT, "16",
                "Each page is 16 KB (4..256)", min = 4, max = 256,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val frame = parseHex(config["frame"]) ?: 0xD0000
        val port = parseHex(config["port"]) ?: 0x260
        val pages = parseHex(config["pages"]) ?: 16
        return EmsWindowCard(frame, port, pages)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val frame = parseHex(config["frame"]) ?: 0xD0000
        val port = parseHex(config["port"]) ?: 0x260
        val id = descriptor().id
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, port, port + 3, id),
            ResourceClaim(ResourceKind.MEMORY, frame, frame + 0xFFFF, id),
        )
    }
}

/**
 * Tiny LIM-style expanded-memory window (not a full EMM driver).
 *
 * - 64 KB page frame at [frameBase] (default D000:0), four 16 KB windows
 * - Backing store of [pageCount] × 16 KB logical pages
 * - I/O [portBase]+0..3: write logical page index for window 0..3; read returns mapping
 * - Write `0xFF` to unmap a window (reads float `0xFF`, writes ignored)
 *
 * Config: `frame=0xD0000`, `port=0x260`, `pages=16`
 */
class EmsWindowCard(
    private val frameBase: Int,
    private val portBase: Int,
    private val pageCount: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.ems-window"
    override val name = "EMS Window (${pageCount * 16} KB backing @ ${frameBase.toString(16)})"

    companion object {
        /** Page index written/read when a window is unmapped. */
        const val UNMAPPED = 0xFF
    }

    private val pageSize = 0x4000
    private val backing = ByteArray(pageCount * pageSize)
    private val map = IntArray(4) { it.coerceAtMost(pageCount - 1) }

    override fun attach(host: IsaHost) {
        require(frameBase and 0x3FFF == 0) { "Frame base must be 16 KB-aligned" }
        require(pageCount in 4..256) { "pages must be 4..256" }

        for (w in 0 until 4) {
            val windowBase = frameBase + w * pageSize
            host.mapMemory(
                MemoryRegion.Mmio(
                    windowBase,
                    pageSize,
                    WindowDevice(w),
                ),
            )
        }

        host.mapIo(object : IoDevice {
            override fun ioReadByte(port: Int): Int {
                val w = port - portBase
                return if (w in 0..3) map[w] and 0xFF else 0xFF
            }

            override fun ioWriteByte(port: Int, value: Int) {
                val w = port - portBase
                if (w !in 0..3) return
                val v = value and 0xFF
                map[w] = when {
                    v == UNMAPPED -> UNMAPPED
                    v < pageCount -> v
                    else -> UNMAPPED
                }
            }
        }, portBase..(portBase + 3))
    }

    private inner class WindowDevice(private val window: Int) : MemoryDevice {
        private fun phys(offset: Int): Int? {
            val page = map[window]
            if (page == UNMAPPED || page !in 0 until pageCount) return null
            return page * pageSize + offset
        }

        override fun memReadByte(offset: Int): Int {
            val p = phys(offset) ?: return 0xFF
            return if (p in backing.indices) backing[p].toInt() and 0xFF else 0xFF
        }

        override fun memWriteByte(offset: Int, value: Int) {
            val p = phys(offset) ?: return
            if (p in backing.indices) backing[p] = (value and 0xFF).toByte()
        }
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
