package com.trugath.k8086.platform

import com.trugath.k8086.storage.DiskImage
import com.trugath.k8086.storage.MemoryDiskImage
import java.io.File
import java.io.RandomAccessFile

internal actual object HostPlatform {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun nanoTime(): Long = System.nanoTime()
    actual fun sleepNanos(ns: Long) {
        if (ns <= 0) return
        val ms = ns / 1_000_000L
        val nano = (ns % 1_000_000L).toInt()
        try {
            Thread.sleep(ms, nano)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
    actual fun getenv(name: String): String? = System.getenv(name)
    actual fun isFile(path: String): Boolean = File(path).isFile
    actual fun readBytes(path: String): ByteArray = File(path).readBytes()
    actual fun absolutePath(path: String): String = File(path).absolutePath
    actual fun parentPath(path: String): String? = File(path).parent
    actual fun joinPath(parent: String, child: String): String = File(parent, child).path
    actual fun openDisk(path: String): DiskImage = RandomAccessDiskImage(RandomAccessFile(path, "rw"))
    actual fun openOrCreateDisk(path: String, provisionBytes: Long): DiskImage {
        val file = File(path)
        val needsProvisioning = !file.exists() || file.length() == 0L
        val raf = RandomAccessFile(file, "rw")
        if (needsProvisioning) raf.setLength(provisionBytes)
        return RandomAccessDiskImage(raf)
    }
    actual fun stderr(message: String) {
        System.err.println(message)
    }
    actual fun invokeLater(block: () -> Unit) {
        javax.swing.SwingUtilities.invokeLater(block)
    }
    actual fun isDisplayHeadless(): Boolean = java.awt.GraphicsEnvironment.isHeadless()
    actual fun mountBytes(path: String, data: ByteArray) {
        // JVM uses the real filesystem.
    }
}

internal actual inline fun <T> platformLock(lock: Any, block: () -> T): T = synchronized(lock, block)

internal class RandomAccessDiskImage(
    private val raf: RandomAccessFile,
) : DiskImage {
    override fun length(): Long = raf.length()
    override fun seek(pos: Long) = raf.seek(pos)
    override fun read(): Int = raf.read()
    override fun read(buf: ByteArray): Int = raf.read(buf)
    override fun write(b: Int) = raf.write(b)
    override fun write(buf: ByteArray) = raf.write(buf)
    override fun close() = raf.close()
}

/** Unused on JVM except as a type; keep import live for wasm-shared [MemoryDiskImage]. */
@Suppress("unused")
private val memoryDiskImageRef: (ByteArray) -> MemoryDiskImage = ::MemoryDiskImage
