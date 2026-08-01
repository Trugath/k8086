package com.trugath.k8086.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsMessagesTest {
    @Test
    fun parseAQuery() {
        val q = buildQuery("localhost", id = 0xABCD)
        val parsed = DnsMessages.parseQuery(q)!!
        assertEquals("localhost", parsed.name)
        assertEquals(1, parsed.type)
        assertEquals(1, parsed.clazz)
        assertEquals(0xABCD, parsed.id)
        assertEquals(q.size, parsed.payloadEnd)
    }

    @Test
    fun parseMultiLabelQuery() {
        val q = buildQuery("example.com", id = 1)
        assertEquals("example.com", DnsMessages.parseQuery(q)!!.name)
    }

    @Test
    fun rejectResponseAsQuery() {
        val q = buildQuery("x", id = 1)
        val resp = DnsMessages.buildAResponse(q, DnsMessages.parseQuery(q)!!, byteArrayOf(127, 0, 0, 1))
        assertNull(DnsMessages.parseQuery(resp))
    }

    @Test
    fun buildAResponseRoundTrip() {
        val q = buildQuery("localhost", id = 0x42)
        val question = DnsMessages.parseQuery(q)!!
        val resp = DnsMessages.buildAResponse(q, question, byteArrayOf(127, 0, 0, 1))
        assertEquals(0x81, resp[2].toInt() and 0xFF)
        assertEquals(0x80, resp[3].toInt() and 0xFF)
        assertEquals(1, ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF))
        // Answer RDATA is last 4 bytes of the 16-byte RR (pointer+type+class+ttl+len+addr)
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), resp.copyOfRange(resp.size - 4, resp.size))
        // Compressed name pointer to offset 12
        assertEquals(0xC0, resp[question.payloadEnd].toInt() and 0xFF)
        assertEquals(12, resp[question.payloadEnd + 1].toInt() and 0xFF)
    }

    @Test
    fun buildNxDomain() {
        val q = buildQuery("no.such.host", id = 7)
        val question = DnsMessages.parseQuery(q)!!
        val nx = DnsMessages.buildNxDomain(q, question)
        assertEquals(3, nx[3].toInt() and 0x0F)
        assertEquals(0, ((nx[6].toInt() and 0xFF) shl 8) or (nx[7].toInt() and 0xFF))
        assertTrue(nx.size == question.payloadEnd)
    }

    private fun buildQuery(name: String, id: Int): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        val qname = ArrayList<Byte>()
        for (lab in labels) {
            qname += lab.length.toByte()
            for (b in lab.toByteArray(Charsets.US_ASCII)) qname += b
        }
        qname += 0
        val header = ByteArray(12)
        header[0] = ((id ushr 8) and 0xFF).toByte()
        header[1] = (id and 0xFF).toByte()
        header[2] = 0x01 // RD
        header[5] = 1 // QDCOUNT
        val tail = byteArrayOf(0, 1, 0, 1) // A IN
        return header + ByteArray(qname.size) { qname[it] } + tail
    }
}