package com.trugath.k8086.cards.gameport

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class GameportCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.gameport",
        name = "Game Port",
        description = "IBM game adapter stub at 0x201 (axes timed out / buttons released).",
        category = "I/O",
        fields = listOf(
            ConfigField(
                "base", "I/O port", ConfigFieldType.HEX_INT, "0x201",
                "Game port address", affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0x201
        return GameportCard(base)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0x201
        return listOf(ResourceClaim(ResourceKind.IO_PORT, base, base, descriptor().id))
    }
}

/** Game port: write starts one-shot; reads report buttons up and axes already timed out. */
class GameportCard(
    private val port: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.gameport"
    override val name = "Game Port @ 0x${port.toString(16)}"

    override fun attach(host: IsaHost) {
        host.mapIo(object : IoDevice {
            override fun ioReadByte(port: Int): Int = 0xF0 // buttons released, axes low
            override fun ioWriteByte(port: Int, value: Int) { /* trigger resistives — instant done */ }
        }, port..port)
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
