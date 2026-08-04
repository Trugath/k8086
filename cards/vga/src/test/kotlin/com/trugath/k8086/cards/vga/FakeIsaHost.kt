package com.trugath.k8086.cards.vga

import com.trugath.k8086.api.DmaChannel
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.MemoryRegion

/**
 * Minimal [IsaHost] for VGA card unit tests: backs BDA/conventional RAM,
 * records IO/MMIO/ROM maps, and runs tickables.
 */
class FakeIsaHost : IsaHost {
    val cpuMem = ByteArray(0x100000)
    val ioDevices = mutableMapOf<Int, IoDevice>()
    val memoryRegions = mutableListOf<MemoryRegion>()
    val optionRoms = mutableListOf<Pair<Int, ByteArray>>()
    val tickables = mutableListOf<(Int) -> Unit>()

    override fun mapIo(device: IoDevice, ports: IntRange) {
        for (p in ports) ioDevices[p and 0xFFFF] = device
    }

    override fun mapIo(device: IoDevice, ports: Iterable<Int>) {
        for (p in ports) ioDevices[p and 0xFFFF] = device
    }

    override fun unmapIo(ports: IntRange) {
        for (p in ports) ioDevices.remove(p and 0xFFFF)
    }

    override fun mapMemory(region: MemoryRegion) {
        memoryRegions.add(region)
    }

    override fun mapOptionRom(bytes: ByteArray, base: Int) {
        optionRoms.add(base to bytes.copyOf())
        for (i in bytes.indices) {
            val addr = base + i
            if (addr in cpuMem.indices) cpuMem[addr] = bytes[i]
        }
    }

    override fun raiseIrq(irq: Int) {}
    override fun lowerIrq(irq: Int) {}
    override fun requestNmi() {}

    override fun claimDmaChannel(channel: Int): DmaChannel =
        object : DmaChannel {
            override val channel: Int = channel
            override fun readByte(): Int = 0
            override fun writeByte(value: Int) {}
            override fun isMasked(): Boolean = true
        }

    override fun addTickable(tick: (cpuCycles: Int) -> Unit) {
        tickables.add(tick)
    }

    override fun cpuRead8(addr: Int): Int {
        val a = addr and 0xFFFFF
        return cpuMem[a].toInt() and 0xFF
    }

    override fun cpuWrite8(addr: Int, value: Int) {
        val a = addr and 0xFFFFF
        cpuMem[a] = (value and 0xFF).toByte()
    }

    fun ioRead(port: Int): Int {
        val p = port and 0xFFFF
        return ioDevices[p]?.ioReadByte(p) ?: 0xFF
    }

    fun ioWrite(port: Int, value: Int) {
        val p = port and 0xFFFF
        ioDevices[p]?.ioWriteByte(p, value and 0xFF)
    }

    fun memRead(phys: Int): Int {
        val a = phys and 0xFFFFF
        for (r in memoryRegions) {
            if (a >= r.base && a < r.end) {
                val off = a - r.base
                return when (r) {
                    is MemoryRegion.Mmio -> r.device.memReadByte(off) and 0xFF
                    is MemoryRegion.Ram -> r.backing[off].toInt() and 0xFF
                    is MemoryRegion.Rom -> r.backing[off].toInt() and 0xFF
                }
            }
        }
        return cpuMem[a].toInt() and 0xFF
    }

    fun memWrite(phys: Int, value: Int) {
        val a = phys and 0xFFFFF
        for (r in memoryRegions) {
            if (a >= r.base && a < r.end) {
                val off = a - r.base
                when (r) {
                    is MemoryRegion.Mmio -> r.device.memWriteByte(off, value and 0xFF)
                    is MemoryRegion.Ram -> r.backing[off] = (value and 0xFF).toByte()
                    is MemoryRegion.Rom -> { /* ignore */ }
                }
                return
            }
        }
        cpuMem[a] = (value and 0xFF).toByte()
    }

    fun tick(cycles: Int) {
        for (t in tickables) t(cycles)
    }

    fun hasIoPort(port: Int): Boolean = ioDevices.containsKey(port and 0xFFFF)

    fun hasMmioAt(base: Int): Boolean =
        memoryRegions.any { it is MemoryRegion.Mmio && it.base == base }
}
