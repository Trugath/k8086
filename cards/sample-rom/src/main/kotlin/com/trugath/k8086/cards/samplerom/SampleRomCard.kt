package com.trugath.k8086.cards.samplerom

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class SampleRomCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.sample-rom",
        name = "Sample Option ROM",
        description = "Demo 512-byte option ROM (55 AA) plus a one-byte scratch I/O latch.",
        category = "Utility",
        fields = listOf(
            ConfigField(
                "base", "ROM base", ConfigFieldType.HEX_INT, "0xC8000",
                "2K-aligned option ROM base", affectsResources = true,
            ),
            ConfigField(
                "port", "I/O port", ConfigFieldType.HEX_INT, "0x300",
                "Scratch latch port", affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0xC8000
        val port = parseHex(config["port"]) ?: 0x300
        return SampleRomCard(base, port)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0xC8000
        val port = parseHex(config["port"]) ?: 0x300
        val id = descriptor().id
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, port, port, id),
            ResourceClaim(ResourceKind.MEMORY, base, base + 511, id),
        )
    }
}

/**
 * Demo ISA card: 512-byte option ROM at [romBase] (55 AA + RETF init) and a
 * one-byte scratch latch at [ioPort].
 */
class SampleRomCard(
    private val romBase: Int,
    private val ioPort: Int,
) : IsaCard {
    override val id: String = "com.trugath.k8086.cards.sample-rom"
    override val name: String = "Sample Option ROM"

    private var latch = 0

    override fun attach(host: IsaHost) {
        host.mapOptionRom(buildOptionRom(), romBase)
        host.mapIo(object : IoDevice {
            override fun ioReadByte(port: Int): Int = latch and 0xFF
            override fun ioWriteByte(port: Int, value: Int) {
                latch = value and 0xFF
            }
        }, ioPort..ioPort)
    }

    companion object {
        /** 55 AA, length=1 (512 bytes), RETF at entry offset 3. */
        fun buildOptionRom(): ByteArray {
            val rom = ByteArray(512)
            rom[0] = 0x55.toByte()
            rom[1] = 0xAA.toByte()
            rom[2] = 0x01
            rom[3] = 0xCB.toByte() // RETF — POST far-calls offset 3
            return rom
        }
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
