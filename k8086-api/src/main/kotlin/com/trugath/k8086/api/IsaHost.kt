package com.trugath.k8086.api

/**
 * Ethernet attachment for an ISA NIC card: guest TX and host-injected RX frames.
 */
interface NicPort {
    /** Deliver a raw Ethernet frame from the guest NIC to the virtual network. */
    fun sendFrame(frame: ByteArray)

    /** Register the handler invoked when the hub delivers a frame to this NIC. */
    fun setReceiveHandler(handler: (ByteArray) -> Unit)

    fun close()
}

/** Drops TX; never delivers RX. Used when no virtual network is configured. */
object NullNicPort : NicPort {
    override fun sendFrame(frame: ByteArray) {}
    override fun setReceiveHandler(handler: (ByteArray) -> Unit) {}
    override fun close() {}
}

/**
 * Services the host emulator exposes to an attached [IsaCard].
 *
 * IRQ lines are edge-triggered on the XT PIC: pulse [raiseIrq] when an interrupt
 * should fire. [lowerIrq] clears a pending request bit if the card needs to withdraw
 * an edge that was not yet acknowledged (normally unused).
 */
interface IsaHost {
    fun mapIo(device: IoDevice, ports: IntRange)
    fun mapIo(device: IoDevice, ports: Iterable<Int>)
    fun unmapIo(ports: IntRange)

    fun mapMemory(region: MemoryRegion)

    /** Map an option ROM. [base] must be 2K-aligned; bytes must start with 55 AA. */
    fun mapOptionRom(bytes: ByteArray, base: Int)

    fun raiseIrq(irq: Int)
    fun lowerIrq(irq: Int)

    /** Request a non-maskable interrupt (CPU INT 2) on the next instruction boundary. */
    fun requestNmi()

    fun claimDmaChannel(channel: Int): DmaChannel

    fun addTickable(tick: (cpuCycles: Int) -> Unit)

    /**
     * True when card audio should emit silence but keep the host audio device fed
     * (user mute, unfocused console, pause). Default false for older card JARs.
     */
    fun isAudioMuted(): Boolean = false

    /**
     * True when card audio must not call into the host audio device at all (e.g. turbo).
     * [SourceDataLine.write] blocks when the buffer is full and would re-introduce pacing.
     * Default false for older card JARs.
     */
    fun isAudioOutputSuspended(): Boolean = false

    fun cpuRead8(addr: Int): Int
    fun cpuWrite8(addr: Int, value: Int)

    /**
     * Attach this card's NIC to a virtual network.
     * Empty or unknown [networkId] yields [NullNicPort].
     */
    fun attachNic(networkId: String, mac: ByteArray): NicPort = NullNicPort
}
