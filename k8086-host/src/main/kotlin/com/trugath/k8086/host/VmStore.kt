package com.trugath.k8086.host

import com.trugath.k8086.protocol.CardSpecDto
import com.trugath.k8086.protocol.FloppySpec
import com.trugath.k8086.protocol.GraphicsKind
import com.trugath.k8086.protocol.HardDiskSpec
import com.trugath.k8086.protocol.InitialVideoKind
import com.trugath.k8086.protocol.MotherboardSpec
import com.trugath.k8086.protocol.VmDefinition
import com.trugath.k8086.protocol.VmId
import java.io.File
import java.util.Properties

/**
 * Simple properties-based VM store under [rootDir]/vms/<id>/vm.properties`.
 */
class VmStore(private val rootDir: File = defaultRoot()) {
    private val vmsDir: File get() = File(rootDir, "vms").also { it.mkdirs() }

    /** Root directory for this store (also used for networks). */
    fun root(): File = rootDir

    fun listIds(): List<VmId> =
        vmsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "vm.properties").isFile }
            ?.map { VmId(it.name) }
            .orEmpty()

    fun load(id: VmId): VmDefinition? {
        val file = File(File(vmsDir, id.value), "vm.properties")
        if (!file.isFile) return null
        val p = Properties()
        file.inputStream().use { p.load(it) }
        return readProps(p, id)
    }

    fun save(def: VmDefinition) {
        val dir = File(vmsDir, def.id.value).also { it.mkdirs() }
        val file = File(dir, "vm.properties")
        val p = writeProps(def)
        file.outputStream().use { p.store(it, "k8086 VM ${def.name}") }
    }

    fun delete(id: VmId) {
        val dir = File(vmsDir, id.value)
        if (dir.isDirectory) dir.deleteRecursively()
    }

    private fun writeProps(def: VmDefinition): Properties = Properties().apply {
        setProperty("id", def.id.value)
        setProperty("name", def.name)
        setProperty("u18RomPath", def.u18RomPath)
        setProperty("u19RomPath", def.u19RomPath)
        setProperty("mb.cpu", def.motherboard.cpu)
        setProperty("mb.baseMemoryKb", def.motherboard.baseMemoryKb.toString())
        setProperty("mb.mathCoprocessor", def.motherboard.mathCoprocessor.toString())
        setProperty("mb.initialVideo", def.motherboard.initialVideo.name)
        setProperty("mb.postLoop", def.motherboard.postLoop.toString())
        setProperty("graphics", def.graphics.name)
        setProperty("enableCom1", def.enableCom1.toString())
        setProperty("floppy.enabled", def.floppy.enabled.toString())
        setProperty("floppy.images", def.floppy.driveImages.joinToString("\u0001"))
        setProperty("floppy.useInt13Shim", def.floppy.useInt13Shim.toString())
        setProperty("hd.enabled", def.hardDisk.enabled.toString())
        setProperty("hd.imagePath", def.hardDisk.imagePath.orEmpty())
        setProperty("hd.secondImagePath", def.hardDisk.secondImagePath.orEmpty())
        setProperty("hd.provisionBytes", def.hardDisk.provisionBytes.toString())
        setProperty("hd.bootFromDisk", def.hardDisk.bootFromDisk.toString())
        setProperty("hd.ioBase", def.hardDisk.ioBase.toString())
        setProperty("hd.irq", def.hardDisk.irq.toString())
        setProperty("hd.dmaChannel", def.hardDisk.dmaChannel.toString())
        setProperty("hd.useInt13Shim", def.hardDisk.useInt13Shim.toString())
        setProperty("hd.cylinders", def.hardDisk.cylinders?.toString().orEmpty())
        setProperty("hd.heads", def.hardDisk.heads?.toString().orEmpty())
        setProperty("hd.sectorsPerTrack", def.hardDisk.sectorsPerTrack?.toString().orEmpty())
        setProperty("cards.count", def.cards.size.toString())
        def.cards.forEachIndexed { i, c ->
            setProperty("cards.$i.jarPath", c.jarPath)
            setProperty("cards.$i.enabled", c.enabled.toString())
            setProperty("cards.$i.config", c.config.entries.joinToString("\u0001") { "${it.key}=${it.value}" })
        }
    }

    private fun readProps(p: Properties, id: VmId): VmDefinition {
        fun prop(key: String, default: String = "") = p.getProperty(key, default)
        fun bool(key: String, default: Boolean = false) = prop(key, default.toString()).toBoolean()
        fun intOrNull(key: String): Int? = prop(key).takeIf { it.isNotBlank() }?.toIntOrNull()

        val cardCount = prop("cards.count", "0").toIntOrNull() ?: 0
        val cards = (0 until cardCount).map { i ->
            val cfg = prop("cards.$i.config")
                .split('\u0001')
                .filter { it.isNotBlank() && it.contains('=') }
                .associate {
                    val eq = it.indexOf('=')
                    it.substring(0, eq) to it.substring(eq + 1)
                }
            CardSpecDto(
                jarPath = prop("cards.$i.jarPath"),
                enabled = bool("cards.$i.enabled", true),
                config = cfg,
            )
        }
        val images = prop("floppy.images").split('\u0001').filter { it.isNotBlank() }
        return VmDefinition(
            id = id,
            name = prop("name", id.value),
            u18RomPath = prop("u18RomPath"),
            u19RomPath = prop("u19RomPath"),
            motherboard = MotherboardSpec(
                cpu = prop("mb.cpu", "8088"),
                baseMemoryKb = prop("mb.baseMemoryKb", "640").toIntOrNull() ?: 640,
                mathCoprocessor = bool("mb.mathCoprocessor"),
                initialVideo = runCatching {
                    InitialVideoKind.valueOf(prop("mb.initialVideo", InitialVideoKind.CGA_80x25.name))
                }.getOrDefault(InitialVideoKind.CGA_80x25),
                postLoop = bool("mb.postLoop"),
            ),
            graphics = runCatching {
                GraphicsKind.valueOf(prop("graphics", GraphicsKind.CGA.name))
            }.getOrDefault(GraphicsKind.CGA),
            enableCom1 = bool("enableCom1", true),
            floppy = FloppySpec(
                enabled = bool("floppy.enabled", true),
                driveImages = images,
                useInt13Shim = bool("floppy.useInt13Shim", true),
            ),
            hardDisk = HardDiskSpec(
                enabled = bool("hd.enabled"),
                imagePath = prop("hd.imagePath").ifBlank { null },
                secondImagePath = prop("hd.secondImagePath").ifBlank { null },
                provisionBytes = prop("hd.provisionBytes", "10607616").toLongOrNull() ?: 10_607_616L,
                bootFromDisk = bool("hd.bootFromDisk"),
                ioBase = prop("hd.ioBase", "800").toIntOrNull() ?: 0x320,
                irq = prop("hd.irq", "5").toIntOrNull() ?: 5,
                dmaChannel = prop("hd.dmaChannel", "3").toIntOrNull() ?: 3,
                useInt13Shim = bool("hd.useInt13Shim"),
                cylinders = intOrNull("hd.cylinders"),
                heads = intOrNull("hd.heads"),
                sectorsPerTrack = intOrNull("hd.sectorsPerTrack"),
            ),
            cards = cards,
        )
    }

    companion object {
        fun defaultRoot(): File =
            File(System.getProperty("user.home"), ".k8086")
    }
}
