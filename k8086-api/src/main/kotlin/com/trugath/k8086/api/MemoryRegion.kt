package com.trugath.k8086.api

/** Guest memory window owned by RAM, ROM, or an MMIO device. */
sealed class MemoryRegion(
    val base: Int,
    val length: Int,
) {
    init {
        require(base >= 0) { "base must be non-negative" }
        require(length > 0) { "length must be positive" }
    }

    val end: Int = base + length

    class Ram(base: Int, length: Int, val backing: ByteArray) : MemoryRegion(base, length) {
        init {
            require(backing.size >= length) { "RAM backing too small" }
        }
    }

    class Rom(base: Int, length: Int, val backing: ByteArray) : MemoryRegion(base, length) {
        init {
            require(backing.size >= length) { "ROM backing too small" }
        }
    }

    class Mmio(base: Int, length: Int, val device: MemoryDevice) : MemoryRegion(base, length)
}

interface MemoryDevice {
    fun memReadByte(offset: Int): Int
    fun memWriteByte(offset: Int, value: Int)
}
