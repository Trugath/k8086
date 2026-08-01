package com.trugath.k8086.net

/**
 * Minimal DNS A-query encode/decode for the userspace NAT gateway DNS proxy.
 */
object DnsMessages {
    data class Question(
        val name: String,
        val type: Int,
        val clazz: Int,
        val id: Int,
        val payloadEnd: Int,
    )

    fun parseQuery(payload: ByteArray): Question? {
        if (payload.size < 12) return null
        val id = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val flags = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null // response
        val qd = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qd < 1) return null
        var i = 12
        val labels = ArrayList<String>()
        while (i < payload.size) {
            val len = payload[i].toInt() and 0xFF
            i++
            if (len == 0) break
            if (len and 0xC0 != 0) return null // compression in QNAME unsupported
            if (i + len > payload.size) return null
            labels += payload.copyOfRange(i, i + len).toString(Charsets.US_ASCII)
            i += len
        }
        if (i + 4 > payload.size) return null
        val type = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
        val clazz = ((payload[i + 2].toInt() and 0xFF) shl 8) or (payload[i + 3].toInt() and 0xFF)
        i += 4
        val name = labels.joinToString(".")
        if (name.isEmpty()) return null
        return Question(name = name, type = type, clazz = clazz, id = id, payloadEnd = i)
    }

    fun buildAResponse(query: ByteArray, question: Question, ipv4: ByteArray): ByteArray {
        require(ipv4.size == 4)
        // Copy question section from original query (header + QNAME + QTYPE/QCLASS)
        val qBytes = query.copyOf(question.payloadEnd)
        qBytes[2] = 0x81.toByte() // QR + RD + RA-ish
        qBytes[3] = 0x80.toByte()
        qBytes[4] = 0; qBytes[5] = 1 // QDCOUNT
        qBytes[6] = 0; qBytes[7] = 1 // ANCOUNT
        qBytes[8] = 0; qBytes[9] = 0
        qBytes[10] = 0; qBytes[11] = 0

        val answer = ByteArray(16)
        // name pointer to offset 12
        answer[0] = 0xC0.toByte()
        answer[1] = 12
        answer[2] = 0; answer[3] = 1 // A
        answer[4] = 0; answer[5] = 1 // IN
        answer[6] = 0; answer[7] = 0; answer[8] = 0; answer[9] = 60 // TTL
        answer[10] = 0; answer[11] = 4 // RDLENGTH
        System.arraycopy(ipv4, 0, answer, 12, 4)
        return qBytes + answer
    }

    fun buildNxDomain(query: ByteArray, question: Question): ByteArray {
        val qBytes = query.copyOf(question.payloadEnd)
        qBytes[2] = 0x81.toByte()
        qBytes[3] = 0x83.toByte() // RCODE=3 NXDOMAIN
        qBytes[4] = 0; qBytes[5] = 1
        qBytes[6] = 0; qBytes[7] = 0
        qBytes[8] = 0; qBytes[9] = 0
        qBytes[10] = 0; qBytes[11] = 0
        return qBytes
    }
}
