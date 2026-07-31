package com.trugath.k8086.net

import com.trugath.k8086.protocol.NetworkDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal DHCP server (Discover → Offer, Request → Ack) for a virtual NAT network.
 */
internal class DhcpServer(
    private val definition: NetworkDefinition,
    private val gatewayIp: ByteArray,
    private val subnetMask: ByteArray,
    private val gatewayMac: ByteArray,
) {
    private val leases = ConcurrentHashMap<String, ByteArray>() // macStr -> ip
    private val used = ConcurrentHashMap.newKeySet<Int>()
    private val poolStart = NetUtil.ipToInt(NetUtil.parseIp(definition.dhcpStartIp))
    private val poolEnd = NetUtil.ipToInt(NetUtil.parseIp(definition.dhcpEndIp))

    fun handleUdp(
        srcMac: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        udpPayload: ByteArray,
        emit: (ByteArray) -> Unit,
    ): Boolean {
        if (!definition.dhcpEnabled) return false
        if (dstPort != 67 || udpPayload.size < 240) return false
        if ((udpPayload[0].toInt() and 0xFF) != 1) return false // BOOTREQUEST

        val xid = udpPayload.copyOfRange(4, 8)
        val chaddr = udpPayload.copyOfRange(28, 34)
        val options = parseOptions(udpPayload)
        val msgType = options[53]?.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        val clientMacKey = NetUtil.macToString(chaddr)

        val yiaddr = when (msgType) {
            1 -> allocate(clientMacKey) // Discover
            3 -> {
                val requested = options[50]
                if (requested != null && requested.size == 4) {
                    bind(clientMacKey, requested)
                    requested
                } else {
                    leases[clientMacKey] ?: allocate(clientMacKey)
                }
            }
            else -> return false
        } ?: return false

        val replyType = if (msgType == 1) 2 else 5 // Offer / Ack
        val reply = buildReply(replyType, xid, yiaddr, chaddr, srcMac)
        emit(reply)
        return true
    }

    private fun allocate(macKey: String): ByteArray? {
        leases[macKey]?.let { return it }
        var ip = poolStart
        while (ip <= poolEnd) {
            if (used.add(ip)) {
                val addr = NetUtil.intToIp(ip)
                leases[macKey] = addr
                return addr
            }
            ip++
        }
        return null
    }

    private fun bind(macKey: String, ip: ByteArray): ByteArray {
        val v = NetUtil.ipToInt(ip)
        used.add(v)
        leases[macKey] = ip
        return ip
    }

    private fun buildReply(
        msgType: Int,
        xid: ByteArray,
        yiaddr: ByteArray,
        chaddr: ByteArray,
        dstMac: ByteArray,
    ): ByteArray {
        val boot = ByteArray(240)
        boot[0] = 2 // BOOTREPLY
        boot[1] = 1 // Ethernet
        boot[2] = 6 // hlen
        System.arraycopy(xid, 0, boot, 4, 4)
        System.arraycopy(yiaddr, 0, boot, 16, 4)
        System.arraycopy(gatewayIp, 0, boot, 20, 4) // siaddr
        System.arraycopy(chaddr, 0, boot, 28, 6)
        // magic cookie
        boot[236] = 99; boot[237] = 130.toByte(); boot[238] = 83; boot[239] = 99

        val opts = ArrayList<Byte>()
        fun opt(code: Int, vararg data: Int) {
            opts += code.toByte()
            opts += data.size.toByte()
            data.forEach { opts += it.toByte() }
        }
        fun optBytes(code: Int, data: ByteArray) {
            opts += code.toByte()
            opts += data.size.toByte()
            data.forEach { opts += it }
        }
        opt(53, msgType)
        optBytes(54, gatewayIp) // server id
        optBytes(1, subnetMask)
        optBytes(3, gatewayIp) // router
        optBytes(6, gatewayIp) // DNS
        opt(51, 0x00, 0x01, 0x51, 0x80) // lease 1 day
        opts += 0xFF.toByte()

        val payload = boot + opts.toByteArray()
        val ipUdp = NetUtil.udpPacket(
            srcIp = gatewayIp,
            dstIp = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            srcPort = 67,
            dstPort = 68,
            payload = payload,
        )
        return NetUtil.ethFrame(dstMac, gatewayMac, 0x0800, ipUdp)
    }

    private fun parseOptions(boot: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        if (boot.size < 240) return out
        var i = 240
        while (i < boot.size) {
            val code = boot[i].toInt() and 0xFF
            i++
            if (code == 0xFF) break
            if (code == 0) continue
            if (i >= boot.size) break
            val len = boot[i].toInt() and 0xFF
            i++
            if (i + len > boot.size) break
            out[code] = boot.copyOfRange(i, i + len)
            i += len
        }
        return out
    }
}
