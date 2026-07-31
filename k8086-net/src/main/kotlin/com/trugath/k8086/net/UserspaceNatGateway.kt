package com.trugath.k8086.net

import com.trugath.k8086.protocol.NetworkDefinition
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-side gateway: ARP, DHCP, ICMP echo, and userspace TCP/UDP NAT.
 */
internal class UserspaceNatGateway(
    private val definition: NetworkDefinition,
    private val emitToLan: (dstMac: ByteArray?, frame: ByteArray) -> Unit,
) {
    val gatewayIp: ByteArray = NetUtil.parseIp(definition.gatewayIp)
    val subnetMask: ByteArray = NetUtil.parseIp(definition.subnetMask)
    val gatewayMac: ByteArray = NetUtil.GATEWAY_MAC.copyOf()

    private val dhcp = DhcpServer(definition, gatewayIp, subnetMask, gatewayMac)
    private val udpNats = ConcurrentHashMap<String, UdpNat>()
    private val tcpNats = ConcurrentHashMap<String, TcpNat>()
    private val nextIdent = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "k8086-nat-${definition.id}").apply { isDaemon = true }
    }

    fun handleFrame(srcMac: ByteArray, frame: ByteArray) {
        if (closed.get() || frame.size < 14) return
        val ethType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
        when (ethType) {
            0x0806 -> handleArp(srcMac, frame)
            0x0800 -> handleIpv4(srcMac, frame)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        udpNats.values.forEach { it.close() }
        tcpNats.values.forEach { it.close() }
        udpNats.clear()
        tcpNats.clear()
        executor.shutdownNow()
    }

    private fun handleArp(srcMac: ByteArray, frame: ByteArray) {
        if (frame.size < 42) return
        val op = ((frame[20].toInt() and 0xFF) shl 8) or (frame[21].toInt() and 0xFF)
        if (op != 1) return // request
        val tpa = frame.copyOfRange(38, 42)
        if (!tpa.contentEquals(gatewayIp)) return
        val sha = gatewayMac
        val spa = gatewayIp
        val tha = frame.copyOfRange(22, 28) // sender HW
        val spaSender = frame.copyOfRange(28, 32)
        val reply = NetUtil.arpReply(sha, spa, tha, spaSender)
        emitToLan(srcMac, reply)
    }

    private fun handleIpv4(srcMac: ByteArray, frame: ByteArray) {
        if (frame.size < 34) return
        val ihl = (frame[14].toInt() and 0x0F) * 4
        if (ihl < 20 || frame.size < 14 + ihl) return
        val proto = frame[14 + 9].toInt() and 0xFF
        val srcIp = frame.copyOfRange(14 + 12, 14 + 16)
        val dstIp = frame.copyOfRange(14 + 16, 14 + 20)
        val payload = frame.copyOfRange(14 + ihl, frame.size)

        // DHCP to broadcast / gateway
        if (proto == 17 && payload.size >= 8) {
            val dstPort = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
            val srcPort = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val udpPayload = payload.copyOfRange(8, payload.size)
            if (dhcp.handleUdp(srcMac, srcIp, srcPort, dstPort, udpPayload) { reply ->
                    emitToLan(srcMac, reply)
                }
            ) {
                return
            }
        }

        // Local to gateway
        if (dstIp.contentEquals(gatewayIp)) {
            when (proto) {
                1 -> handleIcmpToGateway(srcMac, srcIp, payload)
                17 -> { /* ignore non-DHCP UDP to gateway */ }
                6 -> { /* no TCP services on gateway yet */ }
            }
            return
        }

        // Route off-subnet (or any non-local) via NAT
        when (proto) {
            1 -> handleIcmpOutbound(srcMac, srcIp, dstIp, payload)
            17 -> handleUdpOutbound(srcMac, srcIp, dstIp, payload)
            6 -> handleTcpOutbound(srcMac, srcIp, dstIp, payload)
        }
    }

    private fun handleIcmpToGateway(srcMac: ByteArray, srcIp: ByteArray, icmp: ByteArray) {
        if (icmp.isEmpty() || (icmp[0].toInt() and 0xFF) != 8) return // echo request
        val replyPayload = NetUtil.icmpEchoReply(icmp)
        val ip = NetUtil.ipv4Header(gatewayIp, srcIp, 1, replyPayload.size, nextIdent.getAndIncrement())
        emitToLan(srcMac, NetUtil.ethFrame(srcMac, gatewayMac, 0x0800, ip + replyPayload))
    }

    private fun handleIcmpOutbound(srcMac: ByteArray, srcIp: ByteArray, dstIp: ByteArray, icmp: ByteArray) {
        if (icmp.isEmpty() || (icmp[0].toInt() and 0xFF) != 8) return
        executor.execute {
            try {
                val reachable = InetAddress.getByAddress(dstIp).isReachable(1500)
                if (!reachable) return@execute
                val replyPayload = NetUtil.icmpEchoReply(icmp)
                val ip = NetUtil.ipv4Header(dstIp, srcIp, 1, replyPayload.size, nextIdent.getAndIncrement())
                emitToLan(srcMac, NetUtil.ethFrame(srcMac, gatewayMac, 0x0800, ip + replyPayload))
            } catch (_: Exception) {
            }
        }
    }

    private fun handleUdpOutbound(srcMac: ByteArray, srcIp: ByteArray, dstIp: ByteArray, udp: ByteArray) {
        if (udp.size < 8) return
        val srcPort = ((udp[0].toInt() and 0xFF) shl 8) or (udp[1].toInt() and 0xFF)
        val dstPort = ((udp[2].toInt() and 0xFF) shl 8) or (udp[3].toInt() and 0xFF)
        val payload = udp.copyOfRange(8, udp.size)
        val key = "${NetUtil.ipToString(srcIp)}:$srcPort"
        val nat = udpNats.computeIfAbsent(key) {
            UdpNat(srcMac, srcIp, srcPort).also { startUdpReader(it) }
        }
        try {
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), dstPort)
            nat.socket.send(packet)
        } catch (_: Exception) {
        }
    }

    private fun startUdpReader(nat: UdpNat) {
        executor.execute {
            val buf = ByteArray(2048)
            try {
                while (!closed.get() && !nat.socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    nat.socket.receive(packet)
                    val payload = buf.copyOf(packet.length)
                    val remote = packet.address.address
                    val ipUdp = NetUtil.udpPacket(
                        srcIp = remote,
                        dstIp = nat.guestIp,
                        srcPort = packet.port,
                        dstPort = nat.guestPort,
                        payload = payload,
                    )
                    emitToLan(nat.guestMac, NetUtil.ethFrame(nat.guestMac, gatewayMac, 0x0800, ipUdp))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleTcpOutbound(srcMac: ByteArray, srcIp: ByteArray, dstIp: ByteArray, tcp: ByteArray) {
        if (tcp.size < 20) return
        val srcPort = ((tcp[0].toInt() and 0xFF) shl 8) or (tcp[1].toInt() and 0xFF)
        val dstPort = ((tcp[2].toInt() and 0xFF) shl 8) or (tcp[3].toInt() and 0xFF)
        val dataOffset = ((tcp[12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || tcp.size < dataOffset) return
        val flags = tcp[13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val key = "${NetUtil.ipToString(srcIp)}:$srcPort-${NetUtil.ipToString(dstIp)}:$dstPort"
        val existing = tcpNats[key]
        if (existing == null) {
            if (!syn) return
            val nat = TcpNat(srcMac, srcIp, srcPort, dstIp, dstPort)
            tcpNats[key] = nat
            executor.execute { runTcpBridge(nat, key) }
            // Synthesize SYN-ACK immediately after connect attempt starts in bridge
            return
        }
        // Deliver payload bytes to host socket
        if (tcp.size > dataOffset) {
            try {
                existing.socket?.getOutputStream()?.write(tcp, dataOffset, tcp.size - dataOffset)
            } catch (_: Exception) {
            }
        }
        if (flags and 0x01 != 0) { // FIN
            existing.close()
            tcpNats.remove(key)
        }
    }

    private fun runTcpBridge(nat: TcpNat, key: String) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(InetAddress.getByAddress(nat.remoteIp), nat.remotePort), 3000)
            nat.socket = sock
            // SYN-ACK to guest
            emitTcp(nat, flags = 0x12, seq = 1, ack = 1, payload = ByteArray(0))
            val buf = ByteArray(2048)
            val input = sock.getInputStream()
            var seq = 1
            while (!closed.get() && !sock.isClosed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                seq += n
                emitTcp(nat, flags = 0x18, seq = seq - n, ack = 1, payload = buf.copyOf(n))
            }
            emitTcp(nat, flags = 0x11, seq = seq, ack = 1, payload = ByteArray(0)) // FIN+ACK
        } catch (_: Exception) {
            emitTcp(nat, flags = 0x14, seq = 0, ack = 0, payload = ByteArray(0)) // RST
        } finally {
            nat.close()
            tcpNats.remove(key)
        }
    }

    private fun emitTcp(nat: TcpNat, flags: Int, seq: Int, ack: Int, payload: ByteArray) {
        val hdrLen = 20
        val tcp = ByteArray(hdrLen + payload.size)
        tcp[0] = ((nat.remotePort ushr 8) and 0xFF).toByte()
        tcp[1] = (nat.remotePort and 0xFF).toByte()
        tcp[2] = ((nat.guestPort ushr 8) and 0xFF).toByte()
        tcp[3] = (nat.guestPort and 0xFF).toByte()
        tcp[4] = ((seq ushr 24) and 0xFF).toByte()
        tcp[5] = ((seq ushr 16) and 0xFF).toByte()
        tcp[6] = ((seq ushr 8) and 0xFF).toByte()
        tcp[7] = (seq and 0xFF).toByte()
        tcp[8] = ((ack ushr 24) and 0xFF).toByte()
        tcp[9] = ((ack ushr 16) and 0xFF).toByte()
        tcp[10] = ((ack ushr 8) and 0xFF).toByte()
        tcp[11] = (ack and 0xFF).toByte()
        tcp[12] = 0x50 // data offset 5
        tcp[13] = flags.toByte()
        tcp[14] = 0xFF.toByte(); tcp[15] = 0xFF.toByte() // window
        System.arraycopy(payload, 0, tcp, hdrLen, payload.size)
        // TCP checksum with pseudo-header
        val pseudo = ByteArray(12 + tcp.size)
        System.arraycopy(nat.remoteIp, 0, pseudo, 0, 4)
        System.arraycopy(nat.guestIp, 0, pseudo, 4, 4)
        pseudo[9] = 6
        pseudo[10] = ((tcp.size ushr 8) and 0xFF).toByte()
        pseudo[11] = (tcp.size and 0xFF).toByte()
        System.arraycopy(tcp, 0, pseudo, 12, tcp.size)
        val csum = NetUtil.checksum16(pseudo)
        tcp[16] = ((csum ushr 8) and 0xFF).toByte()
        tcp[17] = (csum and 0xFF).toByte()
        val ip = NetUtil.ipv4Header(nat.remoteIp, nat.guestIp, 6, tcp.size, nextIdent.getAndIncrement())
        emitToLan(nat.guestMac, NetUtil.ethFrame(nat.guestMac, gatewayMac, 0x0800, ip + tcp))
    }

    private class UdpNat(
        val guestMac: ByteArray,
        val guestIp: ByteArray,
        val guestPort: Int,
    ) {
        val socket = DatagramSocket()
        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class TcpNat(
        val guestMac: ByteArray,
        val guestIp: ByteArray,
        val guestPort: Int,
        val remoteIp: ByteArray,
        val remotePort: Int,
    ) {
        @Volatile
        var socket: Socket? = null
        fun close() {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }
}
