package com.trugath.k8086.cards.de220

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ne2000CoreTest {
    private val mac = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56)

    @Test
    fun softResetSetsIsrRst() {
        val core = Ne2000Core(mac)
        core.ioWrite(0x1F, 0x00) // RESET
        val isr = core.ioRead(0x07)
        assertTrue(isr and Ne2000Core.ISR_RST != 0)
        core.ioWrite(0x00, 0x21) // stop page0
        assertEquals(0x21, core.ioRead(0x00) and 0x3F)
    }

    @Test
    fun config3Page3RoundTrip() {
        val core = Ne2000Core(mac)
        core.setConfig3Irq(5)
        core.ioWrite(0x00, 0xC1) // stop page 3
        val v = core.ioRead(0x0D)
        assertEquals(5, core.irqFromConfig3())
        core.ioWrite(0x0D, (v and 0xF0) or 0x01) // IRQ 3 index
        core.ioWrite(0x00, 0x21)
        assertEquals(3, core.irqFromConfig3())
    }

    @Test
    fun remoteDmaReadsPromMac() {
        val core = Ne2000Core(mac)
        core.ioWrite(0x00, 0x22) // start page0
        core.ioWrite(0x0A, 12) // RBCR0
        core.ioWrite(0x0B, 0) // RBCR1
        core.ioWrite(0x08, 0) // RSAR0
        core.ioWrite(0x09, 0) // RSAR1
        core.ioWrite(0x00, 0x0A) // remote read
        val out = ByteArray(6)
        for (i in 0 until 6) {
            // PROM stores duplicated bytes; WispOS does `in ax` then takes AL of each word —
            // our 8-bit DATA returns one byte per read; skip duplicate by reading pairs.
            val lo = core.ioRead(0x10)
            core.ioRead(0x10) // duplicate
            out[i] = lo.toByte()
        }
        assertTrue(out.contentEquals(mac))
    }

    @Test
    fun transmitSetsPtxAndInvokesCallback() {
        var sent: ByteArray? = null
        val core = Ne2000Core(mac, onTransmit = { sent = it })
        // Write a tiny frame into TX page 0x40
        val page = 0x40
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        core.ioWrite(0x00, 0x22)
        core.ioWrite(0x0A, payload.size) // RBCR
        core.ioWrite(0x0B, 0)
        core.ioWrite(0x08, 0)
        core.ioWrite(0x09, page) // RSAR = page<<8
        core.ioWrite(0x00, 0x12) // remote write
        for (b in payload) core.ioWrite(0x10, b.toInt())
        core.ioWrite(0x04, page) // TPSR
        core.ioWrite(0x05, payload.size)
        core.ioWrite(0x06, 0)
        core.ioWrite(0x00, 0x26) // start + TXP
        assertTrue(sent != null && sent!!.size >= payload.size)
        assertTrue(core.ioRead(0x07) and Ne2000Core.ISR_PTX != 0)
    }

    @Test
    fun receiveFrameSetsPrx() {
        val core = Ne2000Core(mac)
        core.ioWrite(0x00, 0x21)
        core.ioWrite(0x01, 0x46) // PSTART
        core.ioWrite(0x02, 0x60) // PSTOP
        core.ioWrite(0x03, 0x46) // BNRY
        core.ioWrite(0x00, 0x61) // page1 stop
        core.ioWrite(0x07, 0x47) // CURR
        core.ioWrite(0x00, 0x22) // start
        val frame = ByteArray(64) { it.toByte() }
        core.receiveFrame(frame)
        assertTrue(core.ioRead(0x07) and Ne2000Core.ISR_PRX != 0)
    }

    @Test
    fun factoryClaimsIoAndIrq() {
        val f = De220CardFactory()
        val claims = f.resourceClaims(mapOf("base" to "0x300", "irq" to "3"))
        assertEquals(2, claims.size)
        assertEquals(0x300, claims[0].start)
        assertEquals(0x31F, claims[0].endInclusive)
        assertEquals(3, claims[1].start)
    }
}
