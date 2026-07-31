package com.trugath.k8086.cards.de220

/**
 * DP8390 / NE2000 core used by the DE-220 card: registers, PROM, remote DMA, TX/RX ring.
 */
class Ne2000Core(
    private val mac: ByteArray,
    private val onIrq: () -> Unit = {},
    private val onTransmit: (ByteArray) -> Unit = {},
) {
    /** 32 KB address space; NE2000 typically uses 8–16 KB of buffer RAM + PROM. */
    private val mem = ByteArray(0x8000)

    private var cr = 0x21 // stop, page 0
    private var isr = 0
    private var imr = 0
    private var dcr = 0
    private var tcr = 0
    private var rcr = 0
    private var tpsr = 0
    private var tbcr = 0
    private var pstart = 0
    private var pstop = 0
    private var bnry = 0
    private var curr = 0
    private var rsar = 0
    private var rbcr = 0
    private var remoteRemaining = 0
    private var remoteAddr = 0
    private var remoteWrite = false
    private var config3 = 0x03 // IRQ 3 index by default (table[3]=3 → wait, table is {2,3,4,5,9,10,11,12}, index 1 = IRQ3)

    private val page1Par = ByteArray(6)
    private val rxQueue = ArrayDeque<ByteArray>()

    init {
        require(mac.size == 6)
        // PROM: each MAC byte duplicated (standard NE2000)
        for (i in 0 until 6) {
            mem[i * 2] = mac[i]
            mem[i * 2 + 1] = mac[i]
        }
        // Fill rest of first page with PROM pattern
        for (i in 12 until 32) mem[i] = 0x57
        System.arraycopy(mac, 0, page1Par, 0, 6)
        // Default CONFIG3 low nibble index 1 → IRQ 3
        config3 = (config3 and 0xF0) or 0x01
    }

    fun irqFromConfig3(): Int {
        val idx = config3 and 0x0F
        return IRQ_TABLE.getOrElse(idx) { 3 }
    }

    fun setConfig3Irq(irq: Int) {
        val idx = IRQ_TABLE.indexOf(irq).takeIf { it >= 0 } ?: 1
        config3 = (config3 and 0xF0) or (idx and 0x0F)
    }

    fun ioRead(offset: Int): Int {
        val off = offset and 0x1F
        if (off == 0x10) return remoteReadData()
        if (off == 0x1F) return 0 // RESET read
        return when (page()) {
            0 -> readPage0(off)
            1 -> readPage1(off)
            3 -> readPage3(off)
            else -> 0xFF
        }
    }

    fun ioWrite(offset: Int, value: Int) {
        val v = value and 0xFF
        val off = offset and 0x1F
        if (off == 0x10) {
            remoteWriteData(v)
            return
        }
        if (off == 0x1F) {
            softReset()
            return
        }
        if (off == 0x00) {
            writeCr(v)
            return
        }
        when (page()) {
            0 -> writePage0(off, v)
            1 -> writePage1(off, v)
            3 -> writePage3(off, v)
        }
    }

    /** Inject an Ethernet frame into the RX ring (from the virtual network). */
    fun receiveFrame(frame: ByteArray) {
        if (cr and CR_STP != 0) return
        synchronized(rxQueue) {
            if (rxQueue.size < 32) rxQueue.addLast(frame.copyOf())
        }
        drainRx()
    }

    fun tick(@Suppress("UNUSED_PARAMETER") cycles: Int) {
        drainRx()
        maybeRaiseIrq()
    }

    private fun softReset() {
        isr = isr or ISR_RST
        cr = (cr and 0xC0) or CR_STP // keep page, stop
        remoteRemaining = 0
        maybeRaiseIrq()
    }

    private fun page(): Int = (cr ushr 6) and 0x03

    private fun writeCr(v: Int) {
        val prev = cr
        cr = v
        // Clear RST when leaving reset path is done by writing ISR
        if (v and CR_TXP != 0) {
            doTransmit()
            cr = cr and CR_TXP.inv()
        }
        if (v and CR_RD_MASK == CR_RD_READ || v and CR_RD_MASK == CR_RD_WRITE) {
            startRemoteDma(write = (v and CR_RD_MASK) == CR_RD_WRITE)
        }
        if ((prev and CR_STP) != 0 && (v and CR_STA) != 0) {
            // started
        }
    }

    private fun doTransmit() {
        val start = (tpsr and 0xFF) * 256
        val len = tbcr.coerceIn(0, 0x4000)
        if (len <= 0) return
        val end = (start + len).coerceAtMost(mem.size)
        val frame = mem.copyOfRange(start, end)
        onTransmit(frame)
        isr = isr or ISR_PTX
        maybeRaiseIrq()
    }

    private fun startRemoteDma(write: Boolean) {
        remoteWrite = write
        remoteAddr = rsar and 0xFFFF
        remoteRemaining = rbcr and 0xFFFF
        if (remoteRemaining == 0) {
            isr = isr or ISR_RDC
            maybeRaiseIrq()
        }
    }

    private fun remoteReadData(): Int {
        if (remoteRemaining <= 0) return 0xFF
        val addr = remoteAddr and (mem.size - 1)
        val v = mem[addr].toInt() and 0xFF
        remoteAddr = (remoteAddr + 1) and 0xFFFF
        remoteRemaining--
        if (remoteRemaining == 0) {
            isr = isr or ISR_RDC
            maybeRaiseIrq()
        }
        return v
    }

    private fun remoteWriteData(v: Int) {
        if (remoteRemaining <= 0) return
        val addr = remoteAddr and (mem.size - 1)
        // Don't overwrite PROM (page 0)
        if (addr >= 0x20) mem[addr] = v.toByte()
        remoteAddr = (remoteAddr + 1) and 0xFFFF
        remoteRemaining--
        if (remoteRemaining == 0) {
            isr = isr or ISR_RDC
            maybeRaiseIrq()
        }
    }

    private fun drainRx() {
        while (true) {
            val frame = synchronized(rxQueue) { rxQueue.removeFirstOrNull() } ?: break
            if (!writeRxFrame(frame)) {
                synchronized(rxQueue) { rxQueue.addFirst(frame) }
                break
            }
        }
    }

    private fun writeRxFrame(frame: ByteArray): Boolean {
        val startPage = pstart and 0xFF
        val stopPage = pstop and 0xFF
        if (stopPage <= startPage) return false
        val next = ((curr and 0xFF) + 1).let { if (it >= stopPage) startPage else it }
        // Need room: header page + payload pages
        val total = 4 + frame.size
        val pagesNeeded = (total + 255) / 256
        // Simplified: refuse if next would hit bnry
        val bn = bnry and 0xFF
        if (next == bn) return false

        val headerPage = curr and 0xFF
        val status = 0x01 // receive OK
        val nextPacket = ((headerPage + pagesNeeded).let { p ->
            var x = p
            while (x >= stopPage) x = startPage + (x - stopPage)
            x
        }) and 0xFF

        val base = headerPage * 256
        if (base + 4 > mem.size) return false
        mem[base] = status.toByte()
        mem[base + 1] = nextPacket.toByte()
        mem[base + 2] = (total and 0xFF).toByte()
        mem[base + 3] = ((total ushr 8) and 0xFF).toByte()

        var addr = base + 4
        for (b in frame) {
            if (addr >= stopPage * 256) addr = startPage * 256
            if (addr >= mem.size) return false
            mem[addr++] = b
        }
        curr = nextPacket
        isr = isr or ISR_PRX
        maybeRaiseIrq()
        return true
    }

    private fun maybeRaiseIrq() {
        if ((isr and imr) != 0) onIrq()
    }

    private fun readPage0(off: Int): Int = when (off) {
        0x00 -> cr
        0x01 -> cldaLow() // CLDA0 often reads as PSTART after init; expose PSTART
        0x02 -> pstop
        0x03 -> bnry
        0x04 -> tsr()
        0x07 -> isr
        0x0C -> rsr()
        0x0D -> 0
        0x0E -> dcr
        0x0F -> imr
        else -> 0
    }

    private fun writePage0(off: Int, v: Int) {
        when (off) {
            0x01 -> pstart = v
            0x02 -> pstop = v
            0x03 -> bnry = v
            0x04 -> tpsr = v
            0x05 -> tbcr = (tbcr and 0xFF00) or v
            0x06 -> tbcr = (tbcr and 0x00FF) or (v shl 8)
            0x07 -> {
                isr = isr and v.inv() // write-1-to-clear
            }
            0x08 -> rsar = (rsar and 0xFF00) or v
            0x09 -> rsar = (rsar and 0x00FF) or (v shl 8)
            0x0A -> rbcr = (rbcr and 0xFF00) or v
            0x0B -> rbcr = (rbcr and 0x00FF) or (v shl 8)
            0x0C -> rcr = v
            0x0D -> tcr = v
            0x0E -> dcr = v
            0x0F -> {
                imr = v
                maybeRaiseIrq()
            }
        }
    }

    private fun readPage1(off: Int): Int = when (off) {
        0x00 -> cr
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06 -> page1Par[off - 1].toInt() and 0xFF
        0x07 -> curr
        else -> 0
    }

    private fun writePage1(off: Int, v: Int) {
        when (off) {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06 -> page1Par[off - 1] = v.toByte()
            0x07 -> curr = v
        }
    }

    private fun readPage3(off: Int): Int = when (off) {
        0x00 -> cr
        0x0D -> config3
        else -> 0xFF
    }

    private fun writePage3(off: Int, v: Int) {
        when (off) {
            0x0D -> config3 = v
        }
    }

    private fun cldaLow(): Int = pstart
    private fun tsr(): Int = if ((isr and ISR_PTX) != 0) 0x01 else 0
    private fun rsr(): Int = if ((isr and ISR_PRX) != 0) 0x01 else 0

    companion object {
        val IRQ_TABLE = intArrayOf(2, 3, 4, 5, 9, 10, 11, 12)

        const val CR_STP = 0x01
        const val CR_STA = 0x02
        const val CR_TXP = 0x04
        const val CR_RD_MASK = 0x38
        const val CR_RD_READ = 0x08
        const val CR_RD_WRITE = 0x10

        const val ISR_PRX = 0x01
        const val ISR_PTX = 0x02
        const val ISR_RXE = 0x04
        const val ISR_TXE = 0x08
        const val ISR_OVW = 0x10
        const val ISR_CNT = 0x20
        const val ISR_RDC = 0x40
        const val ISR_RST = 0x80
    }
}
