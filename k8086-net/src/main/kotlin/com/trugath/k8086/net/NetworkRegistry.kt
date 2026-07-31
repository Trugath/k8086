package com.trugath.k8086.net

import com.trugath.k8086.api.NicPort
import com.trugath.k8086.api.NullNicPort
import com.trugath.k8086.protocol.NetworkApi
import com.trugath.k8086.protocol.NetworkDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * Live virtual networks: persists definitions and owns [VirtualNetworkHub] instances.
 */
class NetworkRegistry(
    private val store: NetworkStore = NetworkStore(),
) : NetworkApi {
    private val hubs = ConcurrentHashMap<String, VirtualNetworkHub>()

    init {
        store.ensureDefault()
        for (def in store.listAll()) {
            hubs[def.id] = VirtualNetworkHub(def)
        }
    }

    override fun listNetworks(): List<NetworkDefinition> =
        hubs.values.map { it.definition }.sortedBy { it.name.lowercase() }

    override fun getNetwork(id: String): NetworkDefinition? = hubs[id]?.definition ?: store.load(id)

    override fun createNetwork(definition: NetworkDefinition): NetworkDefinition {
        require(definition.id.isNotBlank()) { "Network id required" }
        require(!hubs.containsKey(definition.id)) { "Network already exists: ${definition.id}" }
        store.save(definition)
        hubs[definition.id] = VirtualNetworkHub(definition)
        return definition
    }

    override fun updateNetwork(definition: NetworkDefinition): NetworkDefinition {
        val hub = hubs[definition.id] ?: error("Unknown network: ${definition.id}")
        store.save(definition)
        hub.updateDefinition(definition)
        return definition
    }

    override fun deleteNetwork(id: String) {
        require(id != NetworkStore.DEFAULT_ID) { "Cannot delete the default network" }
        hubs.remove(id)?.close()
        store.delete(id)
    }

    /** Attach a guest NIC to [networkId]; unknown/blank → [NullNicPort]. */
    fun attachNic(networkId: String, mac: ByteArray): NicPort {
        if (networkId.isBlank()) return NullNicPort
        val hub = hubs[networkId] ?: return NullNicPort
        return hub.attach(mac)
    }

    fun close() {
        hubs.values.forEach { it.close() }
        hubs.clear()
    }
}
