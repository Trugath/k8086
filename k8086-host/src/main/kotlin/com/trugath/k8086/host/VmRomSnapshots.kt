package com.trugath.k8086.host

import com.trugath.k8086.protocol.SystemRomDefaults
import com.trugath.k8086.protocol.VmId
import java.io.File

/**
 * Per-VM immutable ROM snapshots under `vms/<id>/roms/{u18,u19,fdrom}.bin`.
 *
 * Create/edit supply *source* paths; the host copies them into the VM directory and
 * persists those absolute snapshot paths in `vm.properties`. [FDROM_NAME] is copied
 * from beside the source U18 (or [SystemRomDefaults.FDROM_RELATIVE]) when present so
 * guest Fixed Disk INT 13h works with HD-enabled VMs.
 */
object VmRomSnapshots {
    const val U18_NAME = "u18.bin"
    const val U19_NAME = "u19.bin"
    const val FDROM_NAME = "fdrom.bin"

    fun romDir(store: VmStore, id: VmId): File =
        File(File(File(store.root(), "vms"), id.value), "roms")

    fun canonicalFiles(store: VmStore, id: VmId): Pair<File, File> {
        val dir = romDir(store, id)
        return File(dir, U18_NAME) to File(dir, U19_NAME)
    }

    fun fdromFile(store: VmStore, id: VmId): File =
        File(romDir(store, id), FDROM_NAME)

    /** Copy [sourceU18]/[sourceU19] into the VM ROM directory; return absolute snapshot paths. */
    fun materialize(
        store: VmStore,
        id: VmId,
        sourceU18: File,
        sourceU19: File,
    ): Pair<String, String> {
        require(sourceU18.isFile) { "U18 ROM not found: ${sourceU18.path}" }
        require(sourceU19.isFile) { "U19 ROM not found: ${sourceU19.path}" }
        val (dst18, dst19) = canonicalFiles(store, id)
        dst18.parentFile.mkdirs()
        copyImmutable(sourceU18, dst18)
        copyImmutable(sourceU19, dst19)
        ensureFdrom(store, id, hintBeside = sourceU18)
        return dst18.absolutePath to dst19.absolutePath
    }

    /**
     * Ensure [FDROM_NAME] exists in the VM ROM dir when a Fixed Disk option ROM is
     * available beside [hintBeside] or at the shipped default path.
     */
    fun ensureFdrom(store: VmStore, id: VmId, hintBeside: File? = null) {
        val dst = fdromFile(store, id)
        if (dst.isFile) return
        val candidates = buildList {
            hintBeside?.parentFile?.let { add(File(it, FDROM_NAME)) }
            add(File(SystemRomDefaults.FDROM_RELATIVE))
            System.getenv("K8086_FDROM")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        }
        val src = candidates.firstOrNull { it.isFile } ?: return
        dst.parentFile.mkdirs()
        copyImmutable(src, dst)
    }

    fun sameFile(a: File, b: File): Boolean =
        a.isFile && b.isFile && a.canonicalFile == b.canonicalFile

    private fun copyImmutable(src: File, dst: File) {
        if (sameFile(src, dst)) {
            dst.setWritable(false)
            return
        }
        if (dst.exists()) {
            dst.setWritable(true)
        }
        src.copyTo(dst, overwrite = true)
        dst.setWritable(false)
    }
}
