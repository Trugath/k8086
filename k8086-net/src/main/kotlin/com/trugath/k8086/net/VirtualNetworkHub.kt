package com.trugath.k8086.net

import com.trugath.k8086.api.NicPort
import com.trugath.k8086.protocol.NetworkDefinition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * L2 hub for one virtual NAT network: guest [NicPort]s plus the host gateway.
 */
class VirtualNetworkHub(
    @Volatile var definition: NetworkDefinition,
) {
    private val ports = CopyOnWriteArrayList<HubNicPort>()
    private val macTable = ConcurrentHashMap<String, HubNicPort>()
    private var gateway: UserspaceNatGateway? = null

    init {
        rebuildGateway()
    }

    @Synchronized
    fun updateDefinition(def: NetworkDefinition) {
        definition = def
        rebuildGateway()
    }

    fun attach(mac: ByteArray): NicPort {
        val port = HubNicPort(this, mac.copyOf())
        ports += port
        macTable[NetUtil.macToString(mac)] = port
        return port
    }

    internal fun onPortClosed(port: HubNicPort) {
        ports.remove(port)
        macTable.entries.removeIf { it.value === port }
    }

    internal fun onGuestTx(from: HubNicPort, frame: ByteArray) {
        if (frame.size < 14) return
        val srcMac = frame.copyOfRange(6, 12)
        macTable[NetUtil.macToString(srcMac)] = from
        val dstMac = frame.copyOfRange(0, 6)
        val broadcast = dstMac.contentEquals(NetUtil.BROADCAST_MAC)

        // Always give the gateway a look (ARP / DHCP / routed IP).
        gateway?.handleFrame(srcMac, frame)

        if (!broadcast) {
            val dstKey = NetUtil.macToString(dstMac)
            if (dstKey == NetUtil.macToString(NetUtil.GATEWAY_MAC)) return
            val target = macTable[dstKey]
            if (target != null && target !== from) {
                target.deliver(frame)
                return
            }
        }
        // Flood other guests (not gateway — gateway already handled).
        for (p in ports) {
            if (p !== from) p.deliver(frame)
        }
    }

    internal fun emitFromGateway(dstMac: ByteArray?, frame: ByteArray) {
        if (dstMac != null) {
            val p = macTable[NetUtil.macToString(dstMac)]
            if (p != null) {
                p.deliver(frame)
                return
            }
        }
        // Flood all guests
        for (p in ports) p.deliver(frame)
    }

    @Synchronized
    fun close() {
        gateway?.close()
        gateway = null
        for (p in ports.toList()) p.close()
        ports.clear()
        macTable.clear()
    }

    private fun rebuildGateway() {
        gateway?.close()
        gateway = UserspaceNatGateway(definition) { dst, frame -> emitFromGateway(dst, frame) }
    }
}

internal class HubNicPort(
    private val hub: VirtualNetworkHub,
    private val mac: ByteArray,
) : NicPort {
    @Volatile
    private var handler: ((ByteArray) -> Unit)? = null
    private val rxQueue = ArrayDeque<ByteArray>()
    private val lock = Any()

    override fun sendFrame(frame: ByteArray) {
        hub.onGuestTx(this, frame.copyOf())
    }

    override fun setReceiveHandler(handler: (ByteArray) -> Unit) {
        this.handler = handler
        // Drain any frames queued before the handler was set.
        while (true) {
            val frame = synchronized(lock) { rxQueue.removeFirstOrNull() } ?: break
            handler(frame)
        }
    }

    override fun close() {
        handler = null
        hub.onPortClosed(this)
    }

    fun deliver(frame: ByteArray) {
        val h = handler
        if (h != null) {
            h(frame.copyOf())
        } else {
            synchronized(lock) {
                if (rxQueue.size < 64) rxQueue.addLast(frame.copyOf())
            }
        }
    }
}
