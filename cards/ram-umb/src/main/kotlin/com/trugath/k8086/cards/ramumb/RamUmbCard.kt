package com.trugath.k8086.cards.ramumb

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class RamUmbCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.ram-umb",
        name = "UMB RAM Expansion",
        description = "Maps a contiguous RAM window into the adapter region (default E000:0, 64 KB).",
        category = "Memory",
        fields = listOf(
            ConfigField(
                "base", "Base address", ConfigFieldType.HEX_INT, "0xE0000",
                "Paragraph-aligned base", affectsResources = true,
            ),
            ConfigField(
                "size", "Size (bytes)", ConfigFieldType.HEX_INT, "0x10000",
                "Multiple of 16; must fit below 1 MB", affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0xE0000
        val size = parseHex(config["size"]) ?: 0x10000
        return RamUmbCard(base, size)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0xE0000
        val size = parseHex(config["size"]) ?: 0x10000
        if (size <= 0) return emptyList()
        return listOf(
            ResourceClaim(ResourceKind.MEMORY, base, base + size - 1, descriptor().id),
        )
    }
}

/**
 * Upper Memory Block RAM expansion — maps a contiguous RAM window into the
 * adapter region (default E000:0000, 64 KB). Useful for DOS TSRs / UMB experiments.
 *
 * Config: `base=0xE0000`, `size=0x10000`
 */
class RamUmbCard(
    private val base: Int,
    private val size: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.ram-umb"
    override val name = "UMB RAM Expansion (${size / 1024} KB @ ${base.toString(16)})"

    override fun attach(host: IsaHost) {
        require(base and 0xF == 0) { "UMB base must be paragraph-aligned" }
        require(size > 0 && size % 16 == 0) { "UMB size must be a positive multiple of 16" }
        require(base + size <= 0x100000) { "UMB must fit below 1 MB" }
        host.mapMemory(MemoryRegion.Ram(base, size, ByteArray(size)))
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
