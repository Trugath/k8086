package com.trugath.k8086.platform

import com.trugath.k8086.storage.DiskImage
import com.trugath.k8086.storage.MemoryDiskImage
import kotlin.time.TimeSource

internal actual object HostPlatform {
    private val vfs = mutableMapOf<String, ByteArray>()
    private val mark = TimeSource.Monotonic.markNow()

    actual fun currentTimeMillis(): Long =
        (mark.elapsedNow().inWholeMilliseconds)

    actual fun nanoTime(): Long =
        mark.elapsedNow().inWholeNanoseconds

    actual fun sleepNanos(ns: Long) {
        // Cooperative Wasm: the JS host yields via requestAnimationFrame.
    }

    actual fun getenv(name: String): String? = null

    actual fun isFile(path: String): Boolean = vfs.containsKey(normalize(path))

    actual fun readBytes(path: String): ByteArray =
        vfs[normalize(path)]?.copyOf() ?: error("File not mounted: $path")

    actual fun absolutePath(path: String): String = normalize(path)

    actual fun parentPath(path: String): String? {
        val n = normalize(path)
        val i = n.lastIndexOf('/')
        return if (i <= 0) null else n.substring(0, i)
    }

    actual fun joinPath(parent: String, child: String): String {
        val p = normalize(parent).trimEnd('/')
        return "$p/${child.trimStart('/')}"
    }

    actual fun openDisk(path: String): DiskImage {
        val bytes = vfs[normalize(path)] ?: error("Disk not mounted: $path")
        return MemoryDiskImage(bytes)
    }

    actual fun openOrCreateDisk(path: String, provisionBytes: Long): DiskImage {
        val key = normalize(path)
        val existing = vfs[key]
        val bytes = when {
            existing == null || existing.isEmpty() -> ByteArray(provisionBytes.toInt())
            else -> existing
        }
        vfs[key] = bytes
        return MemoryDiskImage(bytes)
    }

    actual fun stderr(message: String) {
        println(message)
    }

    actual fun invokeLater(block: () -> Unit) {
        block()
    }

    actual fun isDisplayHeadless(): Boolean = true

    actual fun mountBytes(path: String, data: ByteArray) {
        vfs[normalize(path)] = data.copyOf()
    }

    private fun normalize(path: String): String = path.replace('\\', '/')
}

internal actual inline fun <T> platformLock(lock: Any, block: () -> T): T = block()
