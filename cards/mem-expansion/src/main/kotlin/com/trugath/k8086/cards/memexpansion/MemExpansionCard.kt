package com.trugath.k8086.cards.memexpansion

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class MemExpansionCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.mem-expansion",
        name = "1MB Memory Expansion",
        description = "Fills conventional RAM toward 640K and maps UMB in the adapter hole " +
            "(default D000–EFFF). Leaves CGA and fdrom regions free.",
        category = "Memory",
        fields = listOf(
            ConfigField(
                "convStart", "Conventional start", ConfigFieldType.HEX_INT, "0x40000",
                "Must equal motherboard conventional end (e.g. 256K → 0x40000)",
                affectsResources = true,
            ),
            ConfigField(
                "convSize", "Conventional size", ConfigFieldType.HEX_INT, "0x60000",
                "Bytes added below A0000h (default 384K → 640K total)",
                affectsResources = true,
            ),
            ConfigField(
                "umbBase", "UMB base", ConfigFieldType.HEX_INT, "0xD0000",
                "Adapter-hole RAM base; 0 disables UMB",
                affectsResources = true,
            ),
            ConfigField(
                "umbSize", "UMB size", ConfigFieldType.HEX_INT, "0x20000",
                "Bytes of UMB (default 128K); 0 disables",
                affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val convStart = parseHex(config["convStart"]) ?: 0x40000
        val convSize = parseHex(config["convSize"]) ?: 0x60000
        val umbBase = parseHex(config["umbBase"]) ?: 0xD0000
        val umbSize = parseHex(config["umbSize"]) ?: 0x20000
        return MemExpansionCard(convStart, convSize, umbBase, umbSize)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val convStart = parseHex(config["convStart"]) ?: 0x40000
        val convSize = parseHex(config["convSize"]) ?: 0x60000
        val umbBase = parseHex(config["umbBase"]) ?: 0xD0000
        val umbSize = parseHex(config["umbSize"]) ?: 0x20000
        val id = descriptor().id
        return buildList {
            if (convSize > 0) {
                add(ResourceClaim(ResourceKind.MEMORY, convStart, convStart + convSize - 1, id))
            }
            if (umbBase != 0 && umbSize > 0) {
                add(ResourceClaim(ResourceKind.MEMORY, umbBase, umbBase + umbSize - 1, id))
            }
        }
    }
}

/**
 * XT-style memory expansion: raise conventional end (INT 12h / POST) and map UMB RAM.
 *
 * Config: `convStart=0x40000`, `convSize=0x60000`, `umbBase=0xD0000`, `umbSize=0x20000`
 */
class MemExpansionCard(
    private val convStart: Int,
    private val convSize: Int,
    private val umbBase: Int,
    private val umbSize: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.mem-expansion"
    override val name: String
        get() {
            val convKb = convSize / 1024
            val umbKb = if (umbBase != 0 && umbSize > 0) umbSize / 1024 else 0
            return "Memory Expansion (+${convKb}K conv" +
                (if (umbKb > 0) ", ${umbKb}K UMB @ ${umbBase.toString(16)}" else "") + ")"
        }

    override fun attach(host: IsaHost) {
        if (convSize > 0) {
            require(convStart and 0xF == 0) { "convStart must be paragraph-aligned" }
            require(convSize and 0xF == 0) { "convSize must be paragraph-aligned" }
            val end = convStart + convSize
            require(end <= 0xA0000) { "conventional fill must end at or below A0000h" }
            val current = host.conventionalMemoryEnd()
            require(convStart == current) {
                "convStart 0x${convStart.toString(16)} must equal current conventional end " +
                    "0x${current.toString(16)} (set motherboard baseMemoryKb accordingly)"
            }
            host.extendConventionalMemory(end)
        }
        if (umbBase != 0 && umbSize > 0) {
            require(umbBase and 0xF == 0) { "umbBase must be paragraph-aligned" }
            require(umbSize > 0 && umbSize % 16 == 0) { "umbSize must be a positive multiple of 16" }
            require(umbBase >= 0xA0000) { "UMB must start at or above A0000h" }
            require(umbBase + umbSize <= 0xF6000) { "UMB must end below system ROM (F6000h)" }
            host.mapMemory(MemoryRegion.Ram(umbBase, umbSize, ByteArray(umbSize)))
        }
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
