package com.trugath.k8086.cards.heartbeat

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class HeartbeatCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.heartbeat",
        name = "Heartbeat IRQ",
        description = "Periodic IRQ pulse card for exercising interrupt wiring.",
        category = "Utility",
        fields = listOf(
            ConfigField(
                "port", "I/O port", ConfigFieldType.HEX_INT, "0x310",
                "Control/status port", affectsResources = true,
            ),
            ConfigField(
                "irq", "IRQ line", ConfigFieldType.IRQ, "5",
                "XT spare line 2–7", min = 2, max = 7, affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val port = parseHex(config["port"]) ?: 0x310
        val irq = parseHex(config["irq"]) ?: 5
        return HeartbeatCard(port, irq)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val port = parseHex(config["port"]) ?: 0x310
        val irq = parseHex(config["irq"]) ?: 5
        val id = descriptor().id
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, port, port, id),
            ResourceClaim(ResourceKind.IRQ, irq, irq, id),
        )
    }
}

/**
 * Simple ISA timer card for exercising [IsaHost.raiseIrq] + tickables.
 *
 * I/O at [port] (default 0x310):
 * - write: period in ~1ms units (0 = stop); non-zero starts pulsing [irq]
 * - read: bit0 = armed, bit1 = IRQ pending since last read (clears on read)
 *
 * Config: `port=0x310`, `irq=5`
 */
class HeartbeatCard(
    private val port: Int,
    private val irq: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.heartbeat"
    override val name = "Heartbeat IRQ$irq @ 0x${port.toString(16)}"

    private var periodMs = 0
    private var pending = false
    private var cycleAccum = 0
    private lateinit var host: IsaHost

    override fun attach(host: IsaHost) {
        this.host = host
        require(irq in 2..7) { "IRQ must be 2..7 (XT spare lines), got $irq" }
        host.mapIo(object : IoDevice {
            override fun ioReadByte(port: Int): Int {
                var v = 0
                if (periodMs != 0) v = v or 0x01
                if (pending) {
                    v = v or 0x02
                    pending = false
                }
                return v
            }

            override fun ioWriteByte(port: Int, value: Int) {
                periodMs = value and 0xFF
                cycleAccum = 0
                if (periodMs == 0) pending = false
            }
        }, port..port)
        host.addTickable { cycles -> tick(cycles) }
    }

    private fun tick(cpuCycles: Int) {
        if (periodMs == 0) return
        cycleAccum += cpuCycles
        val threshold = periodMs * 4770
        if (cycleAccum >= threshold) {
            cycleAccum -= threshold
            pending = true
            host.raiseIrq(irq)
        }
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
