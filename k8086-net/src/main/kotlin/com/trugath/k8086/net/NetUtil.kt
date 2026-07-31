package com.trugath.k8086.net

import java.net.InetAddress

internal object NetUtil {
    val BROADCAST_MAC = byteArrayOf(
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )
    val GATEWAY_MAC = byteArrayOf(0x52, 0x55, 0x0A, 0x00, 0x02, 0x02.toByte())

    fun parseIp(text: String): ByteArray =
        InetAddress.getByName(text).address

    fun ipToString(ip: ByteArray): String =
        ip.joinToString(".") { (it.toInt() and 0xFF).toString() }

    fun parseMac(text: String): ByteArray {
        val parts = text.split(':', '-').map { it.trim() }
        require(parts.size == 6) { "Invalid MAC: $text" }
        return ByteArray(6) { i -> parts[i].toInt(16).toByte() }
    }

    fun macToString(mac: ByteArray): String =
        mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

    fun ipToInt(ip: ByteArray): Int =
        ((ip[0].toInt() and 0xFF) shl 24) or
            ((ip[1].toInt() and 0xFF) shl 16) or
            ((ip[2].toInt() and 0xFF) shl 8) or
            (ip[3].toInt() and 0xFF)

    fun intToIp(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    fun sameSubnet(a: ByteArray, b: ByteArray, mask: ByteArray): Boolean {
        for (i in 0 until 4) {
            if ((a[i].toInt() and mask[i].toInt()) != (b[i].toInt() and mask[i].toInt())) return false
        }
        return true
    }

    fun checksum16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    fun ethFrame(dst: ByteArray, src: ByteArray, ethType: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(14 + payload.size)
        System.arraycopy(dst, 0, frame, 0, 6)
        System.arraycopy(src, 0, frame, 6, 6)
        frame[12] = ((ethType ushr 8) and 0xFF).toByte()
        frame[13] = (ethType and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 14, payload.size)
        return if (frame.size < 60) frame + ByteArray(60 - frame.size) else frame
    }

    fun arpReply(
        sha: ByteArray,
        spa: ByteArray,
        tha: ByteArray,
        tpa: ByteArray,
    ): ByteArray {
        val p = ByteArray(28)
        p[0] = 0; p[1] = 1 // HTYPE Ethernet
        p[2] = 0x08; p[3] = 0x00 // PTYPE IPv4
        p[4] = 6; p[5] = 4
        p[6] = 0; p[7] = 2 // REPLY
        System.arraycopy(sha, 0, p, 8, 6)
        System.arraycopy(spa, 0, p, 14, 4)
        System.arraycopy(tha, 0, p, 18, 6)
        System.arraycopy(tpa, 0, p, 24, 4)
        return ethFrame(tha, sha, 0x0806, p)
    }

    fun ipv4Header(
        src: ByteArray,
        dst: ByteArray,
        proto: Int,
        payloadLen: Int,
        ident: Int = 1,
        ttl: Int = 64,
    ): ByteArray {
        val total = 20 + payloadLen
        val hdr = ByteArray(20)
        hdr[0] = 0x45
        hdr[2] = ((total ushr 8) and 0xFF).toByte()
        hdr[3] = (total and 0xFF).toByte()
        hdr[4] = ((ident ushr 8) and 0xFF).toByte()
        hdr[5] = (ident and 0xFF).toByte()
        hdr[8] = ttl.toByte()
        hdr[9] = proto.toByte()
        System.arraycopy(src, 0, hdr, 12, 4)
        System.arraycopy(dst, 0, hdr, 16, 4)
        val csum = checksum16(hdr)
        hdr[10] = ((csum ushr 8) and 0xFF).toByte()
        hdr[11] = (csum and 0xFF).toByte()
        return hdr
    }

    fun icmpEchoReply(requestPayload: ByteArray): ByteArray {
        val out = requestPayload.copyOf()
        out[0] = 0 // Echo Reply
        out[2] = 0; out[3] = 0
        val csum = checksum16(out)
        out[2] = ((csum ushr 8) and 0xFF).toByte()
        out[3] = (csum and 0xFF).toByte()
        return out
    }

    fun udpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val udp = ByteArray(udpLen)
        udp[0] = ((srcPort ushr 8) and 0xFF).toByte()
        udp[1] = (srcPort and 0xFF).toByte()
        udp[2] = ((dstPort ushr 8) and 0xFF).toByte()
        udp[3] = (dstPort and 0xFF).toByte()
        udp[4] = ((udpLen ushr 8) and 0xFF).toByte()
        udp[5] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, udp, 8, payload.size)
        // Optional UDP checksum (0 = unused)
        val ip = ipv4Header(srcIp, dstIp, 17, udpLen)
        return ip + udp
    }

    fun padEthernet(frame: ByteArray): ByteArray =
        if (frame.size < 60) frame + ByteArray(60 - frame.size) else frame
}
