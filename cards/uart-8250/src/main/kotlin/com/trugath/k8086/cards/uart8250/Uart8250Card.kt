package com.trugath.k8086.cards.uart8250

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind

class Uart8250CardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.uart-8250",
        name = "NS8250 Serial Port",
        description = "ISA NS8250 UART (default COM2 @ 0x2F8 IRQ3). Use base=0x3F8 irq=4 when motherboard COM1 is off.",
        category = "I/O",
        fields = listOf(
            ConfigField(
                "base", "I/O base", ConfigFieldType.HEX_INT, "0x2F8",
                "UART base (0x3F8 COM1 / 0x2F8 COM2)", affectsResources = true,
            ),
            ConfigField(
                "irq", "IRQ line", ConfigFieldType.IRQ, "3",
                "IRQ4 for COM1, IRQ3 for COM2", min = 2, max = 7, affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0x2F8
        val irq = parseHex(config["irq"]) ?: 3
        return Uart8250Card(base, irq)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0x2F8
        val irq = parseHex(config["irq"]) ?: 3
        val id = descriptor().id
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, base, base + 7, id),
            ResourceClaim(ResourceKind.IRQ, irq, irq, id),
        )
    }
}

/**
 * Card-local NS8250 (same behaviour as motherboard COM1) using [IsaHost] IRQs.
 *
 * Config: `base=0x2F8`, `irq=3`
 */
class Uart8250Card(
    private val basePort: Int,
    private val irq: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.uart-8250"
    override val name = "NS8250 @ 0x${basePort.toString(16)} IRQ$irq"

    override fun attach(host: IsaHost) {
        require(irq in 2..7) { "IRQ must be 2..7, got $irq" }
        host.mapIo(Uart8250Device(host, basePort, irq), basePort until basePort + 8)
    }
}

/** Minimal NS8250 with instant baud, loopback, and IRQ via [IsaHost]. */
internal class Uart8250Device(
    private val host: IsaHost,
    private val basePort: Int,
    private val irq: Int,
) : IoDevice {
    private var thr = 0
    private var rbr = 0
    private var ier = 0
    private var iir = IIR_NO_INT
    private var lcr = 0
    private var mcr = 0
    private var lsr = LSR_THRE or LSR_TEMT
    private var msr = MSR_CTS or MSR_DSR or MSR_DCD
    private var scratch = 0
    private var divisor = 1

    override fun ioReadByte(port: Int): Int {
        val off = port - basePort
        return when (off) {
            0 -> {
                if ((lcr and LCR_DLAB) != 0) divisor and 0xFF
                else {
                    val v = rbr
                    lsr = lsr and LSR_DR.inv()
                    updateInterrupts()
                    v and 0xFF
                }
            }
            1 -> if ((lcr and LCR_DLAB) != 0) (divisor shr 8) and 0xFF else ier and 0x0F
            2 -> {
                val v = iir
                if ((iir and 0x06) == IIR_THRE) {
                    iir = IIR_NO_INT
                    if ((lsr and LSR_DR) == 0 || (ier and IER_RDA) == 0) {
                        host.lowerIrq(irq)
                    } else {
                        recomputeIir()
                        if ((iir and IIR_NO_INT) == 0) host.raiseIrq(irq)
                        else host.lowerIrq(irq)
                    }
                }
                v
            }
            3 -> lcr and 0xFF
            4 -> mcr and 0x1F
            5 -> lsr and 0xFF
            6 -> msr and 0xFF
            7 -> scratch and 0xFF
            else -> 0xFF
        }
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (port - basePort) {
            0 -> {
                if ((lcr and LCR_DLAB) != 0) {
                    divisor = (divisor and 0xFF00) or v
                } else {
                    thr = v
                    lsr = lsr or LSR_THRE or LSR_TEMT
                    if ((mcr and MCR_LOOP) != 0) {
                        rbr = v
                        lsr = lsr or LSR_DR
                    }
                    updateInterrupts()
                }
            }
            1 -> {
                if ((lcr and LCR_DLAB) != 0) {
                    divisor = (divisor and 0x00FF) or (v shl 8)
                } else {
                    ier = v and 0x0F
                    updateInterrupts()
                }
            }
            2 -> { /* FCR */ }
            3 -> lcr = v
            4 -> {
                mcr = v and 0x1F
                if ((mcr and MCR_LOOP) != 0) {
                    var mirrored = 0
                    if ((mcr and MCR_DTR) != 0) mirrored = mirrored or MSR_DSR
                    if ((mcr and MCR_RTS) != 0) mirrored = mirrored or MSR_CTS
                    if ((mcr and MCR_OUT1) != 0) mirrored = mirrored or MSR_RI
                    if ((mcr and MCR_OUT2) != 0) mirrored = mirrored or MSR_DCD
                    msr = mirrored
                } else {
                    msr = MSR_CTS or MSR_DSR or MSR_DCD
                }
            }
            5 -> { }
            6 -> { }
            7 -> scratch = v
        }
    }

    private fun updateInterrupts() {
        recomputeIir()
        if ((iir and IIR_NO_INT) == 0) host.raiseIrq(irq)
        else host.lowerIrq(irq)
    }

    private fun recomputeIir() {
        iir = when {
            (ier and IER_RDA) != 0 && (lsr and LSR_DR) != 0 -> IIR_RDA
            (ier and IER_THRE) != 0 && (lsr and LSR_THRE) != 0 -> IIR_THRE
            (ier and IER_RLS) != 0 && (lsr and LSR_ERROR) != 0 -> IIR_RLS
            else -> IIR_NO_INT
        }
    }

    companion object {
        const val IER_RDA = 0x01
        const val IER_THRE = 0x02
        const val IER_RLS = 0x04
        const val IIR_NO_INT = 0x01
        const val IIR_THRE = 0x02
        const val IIR_RDA = 0x04
        const val IIR_RLS = 0x06
        const val LCR_DLAB = 0x80
        const val MCR_DTR = 0x01
        const val MCR_RTS = 0x02
        const val MCR_OUT1 = 0x04
        const val MCR_OUT2 = 0x08
        const val MCR_LOOP = 0x10
        const val LSR_DR = 0x01
        const val LSR_OE = 0x02
        const val LSR_PE = 0x04
        const val LSR_FE = 0x08
        const val LSR_BI = 0x10
        const val LSR_THRE = 0x20
        const val LSR_TEMT = 0x40
        const val LSR_ERROR = LSR_OE or LSR_PE or LSR_FE or LSR_BI
        const val MSR_CTS = 0x10
        const val MSR_DSR = 0x20
        const val MSR_RI = 0x40
        const val MSR_DCD = 0x80
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
