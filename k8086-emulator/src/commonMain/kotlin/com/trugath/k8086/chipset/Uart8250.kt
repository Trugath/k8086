package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice

/**
 * Minimal NS8250 UART for COM1 (0x3F8–0x3FF) on IRQ4.
 *
 * Instant baud (THR empties immediately). Supports divisor latch, loopback,
 * and host RX injection (serial mouse packets, tests) via a small RX FIFO.
 */
class Uart8250(
    private val pic: Pic8259,
    private val irq: Int = 4,
    private val basePort: Int = 0x3F8,
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
    /** Bytes waiting behind [rbr] (present when [LSR_DR] is set). */
    private val rxFifo = ArrayDeque<Int>()

    /** Invoked for each host-visible TX byte (THR write, not loopback-only). */
    var onTransmit: ((Int) -> Unit)? = null

    fun enqueueRx(value: Int) {
        if ((mcr and MCR_LOOP) != 0) return
        val v = value and 0xFF
        if ((lsr and LSR_DR) == 0) {
            rbr = v
            lsr = lsr or LSR_DR
        } else if (rxFifo.size < RX_FIFO_CAP) {
            rxFifo.addLast(v)
        } else {
            lsr = lsr or LSR_OE
        }
        updateInterrupts()
    }

    override fun ioReadByte(port: Int): Int {
        val off = port - basePort
        return when (off) {
            0 -> {
                if ((lcr and LCR_DLAB) != 0) divisor and 0xFF
                else {
                    val v = rbr
                    if (rxFifo.isNotEmpty()) {
                        rbr = rxFifo.removeFirst()
                        // Keep DR set; next byte already in RBR.
                    } else {
                        lsr = lsr and LSR_DR.inv()
                    }
                    updateInterrupts()
                    v and 0xFF
                }
            }
            1 -> if ((lcr and LCR_DLAB) != 0) (divisor shr 8) and 0xFF else ier and 0x0F
            2 -> {
                val v = iir
                // Reading IIR acknowledges a pending THRE interrupt identity.
                if ((iir and 0x06) == IIR_THRE) {
                    // Keep LSR.THRE set; drop the interrupt view until next TX.
                    iir = IIR_NO_INT
                    if ((lsr and LSR_DR) == 0 || (ier and IER_RDA) == 0) {
                        pic.lowerIrq(irq)
                    } else {
                        recomputeIir()
                        if ((iir and IIR_NO_INT) == 0) pic.raiseIrq(irq)
                        else pic.lowerIrq(irq)
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
                        enqueueRxFromLoopback(v)
                    } else {
                        onTransmit?.invoke(v)
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
            2 -> { /* FCR on 16550 — ignore */ }
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
            5 -> { /* LSR read-only */ }
            6 -> { /* MSR mostly read-only */ }
            7 -> scratch = v
        }
    }

    private fun updateInterrupts() {
        recomputeIir()
        if ((iir and IIR_NO_INT) == 0) pic.raiseIrq(irq)
        else pic.lowerIrq(irq)
    }

    private fun recomputeIir() {
        iir = when {
            (ier and IER_RDA) != 0 && (lsr and LSR_DR) != 0 -> IIR_RDA
            (ier and IER_THRE) != 0 && (lsr and LSR_THRE) != 0 -> IIR_THRE
            (ier and IER_RLS) != 0 && (lsr and LSR_ERROR) != 0 -> IIR_RLS
            else -> IIR_NO_INT
        }
    }

    fun divisorLatch(): Int = divisor and 0xFFFF
    fun lineStatus(): Int = lsr and 0xFF
    fun interruptId(): Int = iir and 0xFF
    fun rxFifoSize(): Int = rxFifo.size + if ((lsr and LSR_DR) != 0) 1 else 0

    private fun enqueueRxFromLoopback(value: Int) {
        val v = value and 0xFF
        if ((lsr and LSR_DR) == 0) {
            rbr = v
            lsr = lsr or LSR_DR
        } else if (rxFifo.size < RX_FIFO_CAP) {
            rxFifo.addLast(v)
        } else {
            lsr = lsr or LSR_OE
        }
    }

    companion object {
        const val RX_FIFO_CAP = 64

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
