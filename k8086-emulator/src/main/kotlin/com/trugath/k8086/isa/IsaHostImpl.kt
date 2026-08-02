package com.trugath.k8086.isa

import com.trugath.k8086.Machine
import com.trugath.k8086.api.DmaChannel
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.api.NicPort
import com.trugath.k8086.cpu.RAM_SIZE

/** Spec for loading one expansion-card JAR. */
data class CardSpec(
    val jarPath: String,
    val config: Map<String, String> = emptyMap(),
)

class IsaHostImpl(
    private val machine: Machine,
    private val cardId: String,
) : IsaHost {
    override fun mapIo(device: IoDevice, ports: IntRange) {
        mapIo(device, ports.toList())
    }

    override fun mapIo(device: IoDevice, ports: Iterable<Int>) {
        machine.ioBus.map(device, ports, owner = cardId)
    }

    override fun unmapIo(ports: IntRange) {
        machine.ioBus.unmap(ports)
    }

    override fun mapMemory(region: MemoryRegion) {
        machine.cpu.memoryBus.map(region, cardId)
    }

    override fun extendConventionalMemory(endExclusive: Int) {
        require(endExclusive and 0xF == 0) {
            "conventional memory end 0x${endExclusive.toString(16)} must be paragraph-aligned"
        }
        require(endExclusive in 64 * 1024..0xA0000) {
            "conventional memory end must be 64K..A0000h (got 0x${endExclusive.toString(16)})"
        }
        val current = machine.cpu.conventionalMemoryEnd
        require(endExclusive >= current) {
            "extendConventionalMemory cannot shrink (current=0x${current.toString(16)}, " +
                "requested=0x${endExclusive.toString(16)})"
        }
        machine.cpu.setConventionalMemoryEnd(endExclusive)
    }

    override fun conventionalMemoryEnd(): Int = machine.cpu.conventionalMemoryEnd

    override fun mapOptionRom(bytes: ByteArray, base: Int) {
        require(base and 0x7FF == 0) {
            "Option ROM base 0x${base.toString(16)} must be 2K-aligned"
        }
        require(bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x55 && (bytes[1].toInt() and 0xFF) == 0xAA) {
            "Option ROM at 0x${base.toString(16)} must start with signature 55 AA"
        }
        val len = bytes.size
        require(base + len <= RAM_SIZE) { "Option ROM exceeds address space" }
        mapMemory(MemoryRegion.Rom(base, len, bytes.copyOf()))
    }

    override fun raiseIrq(irq: Int) {
        machine.pic.raiseIrq(irq)
    }

    override fun lowerIrq(irq: Int) {
        machine.pic.lowerIrq(irq)
    }

    override fun requestNmi() {
        machine.cpu.requestNmi()
    }

    override fun claimDmaChannel(channel: Int): DmaChannel {
        return machine.claimDmaChannel(channel, cardId)
    }

    override fun addTickable(tick: (cpuCycles: Int) -> Unit) {
        machine.addTickable(tick)
    }

    override fun isAudioMuted(): Boolean = machine.feedAudioSilence()

    override fun isAudioOutputSuspended(): Boolean = machine.isAudioOutputSuspended()

    override fun cpuRead8(addr: Int): Int = machine.cpu.readPhysByte(addr)

    override fun cpuWrite8(addr: Int, value: Int) = machine.cpu.writePhysByte(addr, value)

    override fun attachNic(networkId: String, mac: ByteArray): NicPort =
        machine.attachNic(networkId, mac)
}
