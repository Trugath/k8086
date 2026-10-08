package com.trugath.k8086.platform

/**
 * Mount [data] at [path] in the host VFS. On Wasm this is the only way to supply
 * ROMs and disk images; on JVM the real filesystem is used and this is a no-op.
 */
fun mountVirtualFile(path: String, data: ByteArray) {
    HostPlatform.mountBytes(path, data)
}
