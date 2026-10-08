package com.trugath.k8086.storage

/**
 * Seekable floppy/HDD image backing store (path-backed on JVM, in-memory on Wasm).
 */
interface DiskImage {
    fun length(): Long
    fun seek(pos: Long)
    fun read(): Int
    fun read(buf: ByteArray): Int
    fun write(b: Int)
    fun write(buf: ByteArray)
    fun close()
}

/** Writable in-memory disk image used by the browser demo and tests. */
class MemoryDiskImage(initial: ByteArray) : DiskImage {
    private var data = initial.copyOf()
    private var pos = 0L

    override fun length(): Long = data.size.toLong()

    override fun seek(pos: Long) {
        require(pos >= 0) { "seek $pos" }
        this.pos = pos
    }

    override fun read(): Int {
        if (pos >= data.size) return -1
        val b = data[pos.toInt()].toInt() and 0xFF
        pos++
        return b
    }

    override fun read(buf: ByteArray): Int {
        if (pos >= data.size) return -1
        val n = minOf(buf.size, data.size - pos.toInt())
        data.copyInto(buf, 0, pos.toInt(), pos.toInt() + n)
        pos += n
        return n
    }

    override fun write(b: Int) {
        ensureCapacity(pos + 1)
        data[pos.toInt()] = b.toByte()
        pos++
    }

    override fun write(buf: ByteArray) {
        ensureCapacity(pos + buf.size)
        buf.copyInto(data, pos.toInt())
        pos += buf.size
    }

    override fun close() {}

    private fun ensureCapacity(min: Long) {
        if (min <= data.size) return
        data = data.copyOf(min.toInt())
    }
}
