package com.trugath.k8086.net

import com.trugath.k8086.protocol.NetworkDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkHubTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun storeSeedsAndPersistsDefault() {
        val store = NetworkStore(temp)
        store.ensureDefault()
        val def = store.load("default")
        assertNotNull(def)
        assertEquals("10.0.2.2", def!!.gatewayIp)
        assertTrue(def.dhcpEnabled)
    }

    @Test
    fun registryCrud() {
        val reg = NetworkRegistry(NetworkStore(temp))
        assertTrue(reg.listNetworks().any { it.id == "default" })
        val custom = NetworkDefinition(
            id = "lab",
            name = "Lab",
            gatewayIp = "192.168.100.1",
            subnetMask = "255.255.255.0",
            dhcpEnabled = false,
        )
        reg.createNetwork(custom)
        assertEquals("192.168.100.1", reg.getNetwork("lab")!!.gatewayIp)
        reg.updateNetwork(custom.copy(dhcpEnabled = true, dhcpStartIp = "192.168.100.10", dhcpEndIp = "192.168.100.20"))
        assertTrue(reg.getNetwork("lab")!!.dhcpEnabled)
        reg.deleteNetwork("lab")
        assertEquals(null, reg.getNetwork("lab"))
        reg.close()
    }

    @Test
    fun dhcpDiscoverGetsOffer() {
        val reg = NetworkRegistry(NetworkStore(temp))
        val mac = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56)
        val port = reg.attachNic("default", mac)
        val latch = CountDownLatch(1)
        var offer: ByteArray? = null
        port.setReceiveHandler { frame ->
            if (frame.size > 14 && isDhcpOfferOrAck(frame)) {
                offer = frame
                latch.countDown()
            }
        }
        port.sendFrame(buildDhcpDiscover(mac))
        assertTrue(latch.await(2, TimeUnit.SECONDS), "expected DHCP Offer")
        assertNotNull(offer)
        port.close()
        reg.close()
    }

    @Test
    fun arpForGatewayGetsReply() {
        val reg = NetworkRegistry(NetworkStore(temp))
        val mac = byteArrayOf(0x52, 0x54, 0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val port = reg.attachNic("default", mac)
        val latch = CountDownLatch(1)
        var reply: ByteArray? = null
        port.setReceiveHandler { frame ->
            if (frame.size >= 42) {
                val ethType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
                val op = ((frame[20].toInt() and 0xFF) shl 8) or (frame[21].toInt() and 0xFF)
                if (ethType == 0x0806 && op == 2) {
                    reply = frame
                    latch.countDown()
                }
            }
        }
        port.sendFrame(buildArpRequest(mac, byteArrayOf(10, 0, 2, 15), byteArrayOf(10, 0, 2, 2)))
        assertTrue(latch.await(2, TimeUnit.SECONDS), "expected ARP reply")
        assertNotNull(reply)
        // Target protocol should be requester's IP; sender protocol = gateway
        assertTrue(reply!!.copyOfRange(28, 32).contentEquals(byteArrayOf(10, 0, 2, 2)))
        port.close()
        reg.close()
    }

    @Test
    fun icmpEchoToGatewayGetsReply() {
        val reg = NetworkRegistry(NetworkStore(temp))
        val mac = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56)
        val srcIp = byteArrayOf(10, 0, 2, 15)
        val gwIp = byteArrayOf(10, 0, 2, 2)
        val port = reg.attachNic("default", mac)
        val latch = CountDownLatch(1)
        var reply: ByteArray? = null
        port.setReceiveHandler { frame ->
            if (frame.size < 34) return@setReceiveHandler
            val ethType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
            if (ethType != 0x0800) return@setReceiveHandler
            val ihl = (frame[14].toInt() and 0x0F) * 4
            if (frame[14 + 9].toInt() and 0xFF != 1) return@setReceiveHandler
            val icmp = frame.copyOfRange(14 + ihl, frame.size)
            if (icmp.isNotEmpty() && (icmp[0].toInt() and 0xFF) == 0) {
                reply = frame
                latch.countDown()
            }
        }
        port.sendFrame(buildIcmpEcho(mac, srcIp, gwIp, id = 0x1234, seq = 1))
        assertTrue(latch.await(2, TimeUnit.SECONDS), "expected ICMP echo reply from gateway")
        assertNotNull(reply)
        val frame = reply!!
        val ihl = (frame[14].toInt() and 0x0F) * 4
        val icmp = frame.copyOfRange(14 + ihl, frame.size)
        assertEquals(0, icmp[0].toInt() and 0xFF)
        assertEquals(0x12, icmp[4].toInt() and 0xFF)
        assertEquals(0x34, icmp[5].toInt() and 0xFF)
        assertEquals(0x00, icmp[6].toInt() and 0xFF)
        assertEquals(0x01, icmp[7].toInt() and 0xFF)
        port.close()
        reg.close()
    }

    @Test
    fun dhcpDisabledIgnoresDiscover() {
        val store = NetworkStore(temp)
        store.ensureDefault()
        store.save(NetworkStore.DEFAULT.copy(dhcpEnabled = false))
        val reg = NetworkRegistry(store)
        val mac = byteArrayOf(0x52, 0x54, 0x00, 0x01, 0x02, 0x03)
        val port = reg.attachNic("default", mac)
        var gotDhcp = false
        port.setReceiveHandler { frame -> if (isDhcpOfferOrAck(frame)) gotDhcp = true }
        port.sendFrame(buildDhcpDiscover(mac))
        Thread.sleep(200)
        assertFalse(gotDhcp)
        port.close()
        reg.close()
    }

    @Test
    fun dnsAQueryToGatewayGetsReply() {
        val reg = NetworkRegistry(NetworkStore(temp))
        val mac = byteArrayOf(0x52, 0x54, 0x00, 0xD0.toByte(), 0x53, 0x01)
        val srcIp = byteArrayOf(10, 0, 2, 15)
        val gwIp = byteArrayOf(10, 0, 2, 2)
        val port = reg.attachNic("default", mac)
        val latch = CountDownLatch(1)
        var reply: ByteArray? = null
        port.setReceiveHandler { frame ->
            if (frame.size < 42) return@setReceiveHandler
            val ethType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
            if (ethType != 0x0800) return@setReceiveHandler
            val ihl = (frame[14].toInt() and 0x0F) * 4
            if (frame[14 + 9].toInt() and 0xFF != 17) return@setReceiveHandler
            val udpOff = 14 + ihl
            val srcPort = ((frame[udpOff].toInt() and 0xFF) shl 8) or (frame[udpOff + 1].toInt() and 0xFF)
            if (srcPort != 53) return@setReceiveHandler
            val dns = frame.copyOfRange(udpOff + 8, frame.size)
            if (dns.size >= 12 && (dns[2].toInt() and 0x80) != 0) {
                reply = dns
                latch.countDown()
            }
        }
        port.sendFrame(buildDnsAQuery(mac, srcIp, gwIp, "localhost", id = 0xBEEF, srcPort = 0xC001))
        assertTrue(latch.await(3, TimeUnit.SECONDS), "expected DNS A reply from gateway")
        assertNotNull(reply)
        val dns = reply!!
        assertEquals(0xBE, dns[0].toInt() and 0xFF)
        assertEquals(0xEF, dns[1].toInt() and 0xFF)
        assertEquals(0, dns[3].toInt() and 0x0F) // NOERROR
        assertTrue(((dns[6].toInt() and 0xFF) shl 8) or (dns[7].toInt() and 0xFF) >= 1)
        // Last 4 bytes of first answer should be 127.0.0.1
        assertEquals(127, dns[dns.size - 4].toInt() and 0xFF)
        assertEquals(0, dns[dns.size - 3].toInt() and 0xFF)
        assertEquals(0, dns[dns.size - 2].toInt() and 0xFF)
        assertEquals(1, dns[dns.size - 1].toInt() and 0xFF)
        port.close()
        reg.close()
    }

    private fun isDhcpOfferOrAck(frame: ByteArray): Boolean {
        if (frame.size < 282) return false
        val ethType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
        if (ethType != 0x0800) return false
        val ihl = (frame[14].toInt() and 0x0F) * 4
        val proto = frame[14 + 9].toInt() and 0xFF
        if (proto != 17) return false
        val udpOff = 14 + ihl
        if (frame.size < udpOff + 8) return false
        val dstPort = ((frame[udpOff + 2].toInt() and 0xFF) shl 8) or (frame[udpOff + 3].toInt() and 0xFF)
        if (dstPort != 68) return false
        val boot = frame.copyOfRange(udpOff + 8, frame.size)
        if (boot.size < 240) return false
        // option 53
        var i = 240
        while (i < boot.size) {
            val code = boot[i].toInt() and 0xFF
            i++
            if (code == 0xFF) break
            if (code == 0) continue
            if (i >= boot.size) break
            val len = boot[i].toInt() and 0xFF
            i++
            if (code == 53 && len >= 1) {
                val t = boot[i].toInt() and 0xFF
                return t == 2 || t == 5
            }
            i += len
        }
        return false
    }

    private fun buildDnsAQuery(
        sha: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        name: String,
        id: Int,
        srcPort: Int,
    ): ByteArray {
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
        header[2] = 0x01
        header[5] = 1
        val dns = header + ByteArray(qname.size) { qname[it] } + byteArrayOf(0, 1, 0, 1)
        val ipUdp = NetUtil.udpPacket(srcIp, dstIp, srcPort, 53, dns)
        return NetUtil.ethFrame(NetUtil.GATEWAY_MAC, sha, 0x0800, ipUdp)
    }

    private fun buildArpRequest(sha: ByteArray, spa: ByteArray, tpa: ByteArray): ByteArray {
        val p = ByteArray(28)
        p[0] = 0; p[1] = 1
        p[2] = 0x08; p[3] = 0x00
        p[4] = 6; p[5] = 4
        p[6] = 0; p[7] = 1
        System.arraycopy(sha, 0, p, 8, 6)
        System.arraycopy(spa, 0, p, 14, 4)
        System.arraycopy(tpa, 0, p, 24, 4)
        return NetUtil.ethFrame(NetUtil.BROADCAST_MAC, sha, 0x0806, p)
    }

    private fun buildIcmpEcho(
        sha: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        id: Int,
        seq: Int,
    ): ByteArray {
        val icmp = ByteArray(16)
        icmp[0] = 8 // echo request
        icmp[4] = ((id ushr 8) and 0xFF).toByte()
        icmp[5] = (id and 0xFF).toByte()
        icmp[6] = ((seq ushr 8) and 0xFF).toByte()
        icmp[7] = (seq and 0xFF).toByte()
        icmp[8] = 'H'.code.toByte()
        icmp[9] = 'I'.code.toByte()
        icmp[10] = 'N'.code.toByte()
        icmp[11] = 'G'.code.toByte()
        val csum = NetUtil.checksum16(icmp)
        icmp[2] = ((csum ushr 8) and 0xFF).toByte()
        icmp[3] = (csum and 0xFF).toByte()
        val ip = NetUtil.ipv4Header(srcIp, dstIp, 1, icmp.size)
        return NetUtil.ethFrame(NetUtil.GATEWAY_MAC, sha, 0x0800, ip + icmp)
    }

    private fun buildDhcpDiscover(chaddr: ByteArray): ByteArray {
        val boot = ByteArray(240)
        boot[0] = 1 // BOOTREQUEST
        boot[1] = 1
        boot[2] = 6
        boot[4] = 0x12; boot[5] = 0x34; boot[6] = 0x56; boot[7] = 0x78 // xid
        System.arraycopy(chaddr, 0, boot, 28, 6)
        boot[236] = 99; boot[237] = 130.toByte(); boot[238] = 83; boot[239] = 99
        val opts = byteArrayOf(53, 1, 1, 55, 4, 1, 3, 6, 15, 0xFF.toByte())
        val payload = boot + opts
        val ipUdp = NetUtil.udpPacket(
            srcIp = byteArrayOf(0, 0, 0, 0),
            dstIp = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            srcPort = 68,
            dstPort = 67,
            payload = payload,
        )
        return NetUtil.ethFrame(NetUtil.BROADCAST_MAC, chaddr, 0x0800, ipUdp)
    }
}
