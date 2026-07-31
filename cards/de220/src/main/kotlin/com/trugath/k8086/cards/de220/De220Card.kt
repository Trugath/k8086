package com.trugath.k8086.cards.de220

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.NicPort
import com.trugath.k8086.api.NullNicPort
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class De220CardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.de220",
        name = "D-Link DE-220",
        description = "NE2000-compatible 8-bit ISA Ethernet (DE-220PT) with virtual-network NAT.",
        category = "Network",
        fields = listOf(
            ConfigField(
                "base", "I/O base", ConfigFieldType.HEX_INT, "0x300",
                "NE2000 register base (32 ports)", affectsResources = true,
            ),
            ConfigField(
                "irq", "IRQ line", ConfigFieldType.IRQ, "3",
                "XT IRQ (CONFIG3)", min = 2, max = 7, affectsResources = true,
            ),
            ConfigField(
                "mac", "MAC address", ConfigFieldType.STRING, "52:54:00:12:34:56",
                "Station address burned into PROM",
            ),
            ConfigField(
                "network", "Virtual network", ConfigFieldType.NETWORK, "default",
                "Attach to a host virtual network (NAT gateway)",
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0x300
        val irq = parseHex(config["irq"]) ?: 3
        val mac = parseMac(config["mac"] ?: "52:54:00:12:34:56")
        val network = config["network"]?.trim().orEmpty().ifBlank { "default" }
        return De220Card(base, irq, mac, network)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0x300
        val irq = parseHex(config["irq"]) ?: 3
        val id = descriptor().id
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, base, base + 0x1F, id),
            ResourceClaim(ResourceKind.IRQ, irq, irq, id),
        )
    }
}

/**
 * D-Link DE-220PT style NE2000 clone: I/O window, page-3 CONFIG3, PROM MAC, virtual NIC.
 */
class De220Card(
    private val base: Int,
    private val irq: Int,
    private val mac: ByteArray,
    private val networkId: String,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.de220"
    override val name = "DE-220 @ 0x${base.toString(16)} IRQ$irq"

    private lateinit var host: IsaHost
    private var nic: NicPort = NullNicPort
    private lateinit var core: Ne2000Core
    private var currentIrq = irq

    override fun attach(host: IsaHost) {
        this.host = host
        currentIrq = irq
        core = Ne2000Core(
            mac = mac,
            onIrq = {
                host.raiseIrq(core.irqFromConfig3().also { currentIrq = it })
            },
            onTransmit = { frame -> nic.sendFrame(frame) },
        )
        core.setConfig3Irq(irq)

        nic = host.attachNic(networkId, mac)
        nic.setReceiveHandler { frame -> core.receiveFrame(frame) }

        host.mapIo(object : IoDevice {
            override fun ioReadByte(port: Int): Int = core.ioRead(port - base)
            override fun ioWriteByte(port: Int, value: Int) = core.ioWrite(port - base, value)
        }, base until (base + 0x20))

        host.addTickable { cycles -> core.tick(cycles) }
    }

    override fun detach() {
        nic.close()
        nic = NullNicPort
    }
}

internal fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}

internal fun parseMac(s: String): ByteArray {
    val parts = s.split(':', '-').map { it.trim() }
    require(parts.size == 6) { "Invalid MAC: $s" }
    return ByteArray(6) { i -> parts[i].toInt(16).toByte() }
}
