package com.trugath.k8086.net

import com.trugath.k8086.protocol.NetworkDefinition
import java.io.File
import java.util.Properties

/**
 * Properties-based virtual network store under [rootDir]/networks/<id>/network.properties`.
 * Seeds a default NAT network on first use.
 */
class NetworkStore(private val rootDir: File = defaultRoot()) {
    private val networksDir: File get() = File(rootDir, "networks").also { it.mkdirs() }

    fun ensureDefault() {
        if (load(DEFAULT_ID) == null) {
            save(DEFAULT)
        }
    }

    fun listIds(): List<String> =
        networksDir.listFiles()
            ?.filter { it.isDirectory && File(it, "network.properties").isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun listAll(): List<NetworkDefinition> = listIds().mapNotNull { load(it) }

    fun load(id: String): NetworkDefinition? {
        val file = File(File(networksDir, id), "network.properties")
        if (!file.isFile) return null
        val p = Properties()
        file.inputStream().use { p.load(it) }
        return readProps(p, id)
    }

    fun save(def: NetworkDefinition) {
        val dir = File(networksDir, def.id).also { it.mkdirs() }
        val file = File(dir, "network.properties")
        val p = writeProps(def)
        file.outputStream().use { p.store(it, "k8086 network ${def.name}") }
    }

    fun delete(id: String) {
        require(id != DEFAULT_ID) { "Cannot delete the default network" }
        val dir = File(networksDir, id)
        if (dir.isDirectory) dir.deleteRecursively()
    }

    private fun writeProps(def: NetworkDefinition): Properties = Properties().apply {
        setProperty("id", def.id)
        setProperty("name", def.name)
        setProperty("gatewayIp", def.gatewayIp)
        setProperty("subnetMask", def.subnetMask)
        setProperty("dhcpEnabled", def.dhcpEnabled.toString())
        setProperty("dhcpStartIp", def.dhcpStartIp)
        setProperty("dhcpEndIp", def.dhcpEndIp)
    }

    private fun readProps(p: Properties, id: String): NetworkDefinition {
        fun prop(key: String, default: String = "") = p.getProperty(key, default)
        fun bool(key: String, default: Boolean = false) = prop(key, default.toString()).toBoolean()
        return NetworkDefinition(
            id = id,
            name = prop("name", id),
            gatewayIp = prop("gatewayIp", DEFAULT.gatewayIp),
            subnetMask = prop("subnetMask", DEFAULT.subnetMask),
            dhcpEnabled = bool("dhcpEnabled", DEFAULT.dhcpEnabled),
            dhcpStartIp = prop("dhcpStartIp", DEFAULT.dhcpStartIp),
            dhcpEndIp = prop("dhcpEndIp", DEFAULT.dhcpEndIp),
        )
    }

    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = NetworkDefinition(
            id = DEFAULT_ID,
            name = "Default",
            gatewayIp = "10.0.2.2",
            subnetMask = "255.255.255.0",
            dhcpEnabled = true,
            dhcpStartIp = "10.0.2.15",
            dhcpEndIp = "10.0.2.31",
        )

        fun defaultRoot(): File =
            File(System.getProperty("user.home"), ".k8086")
    }
}
