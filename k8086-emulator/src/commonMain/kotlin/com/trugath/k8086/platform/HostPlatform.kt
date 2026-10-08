package com.trugath.k8086.platform

import com.trugath.k8086.storage.DiskImage

/** Host OS / runtime services that differ between JVM and Wasm. */
internal expect object HostPlatform {
    fun currentTimeMillis(): Long
    fun nanoTime(): Long
    fun sleepNanos(ns: Long)
    fun getenv(name: String): String?
    fun isFile(path: String): Boolean
    fun readBytes(path: String): ByteArray
    fun absolutePath(path: String): String
    fun parentPath(path: String): String?
    fun joinPath(parent: String, child: String): String
    fun openDisk(path: String): DiskImage
    fun openOrCreateDisk(path: String, provisionBytes: Long): DiskImage
    fun stderr(message: String)
    fun invokeLater(block: () -> Unit)
    fun isDisplayHeadless(): Boolean
    /** Mount in-memory bytes at [path] (Wasm VFS; no-op on JVM). */
    fun mountBytes(path: String, data: ByteArray)
}

internal expect inline fun <T> platformLock(lock: Any, block: () -> T): T
