package com.trugath.k8086.cards.lpt

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class LptCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.lpt",
        name = "Parallel Port",
        description = "Centronics-compatible parallel port (default LPT2 @ 0x278). Status forced ready/selected.",
        category = "I/O",
        fields = listOf(
            ConfigField(
                "base", "I/O base", ConfigFieldType.HEX_INT, "0x278",
                "0x378 LPT1 / 0x278 LPT2", affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0x278
        return LptCard(base)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0x278
        return listOf(ResourceClaim(ResourceKind.IO_PORT, base, base + 2, descriptor().id))
    }
}

/** Minimal LPT stub: data latch + floating-ready status + control. */
class LptCard(
    private val basePort: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.lpt"
    override val name = "LPT @ 0x${basePort.toString(16)}"

    override fun attach(host: IsaHost) {
        host.mapIo(LptDevice(basePort), basePort..basePort + 2)
    }
}

internal class LptDevice(
    private val basePort: Int,
) : IoDevice {
    private var data = 0
    private var control = 0x0C // /INIT high

    override fun ioReadByte(port: Int): Int = when (port - basePort) {
        0 -> data and 0xFF
        1 -> STATUS_READY
        2 -> control and 0x0F
        else -> 0xFF
    }

    override fun ioWriteByte(port: Int, value: Int) {
        when (port - basePort) {
            0 -> data = value and 0xFF
            2 -> control = value and 0x0F
        }
    }

    companion object {
        /** Busy=0, ACK=1, paper empty=0, select=1, error=1 → ready/selected. */
        const val STATUS_READY = 0xDF
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
