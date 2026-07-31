package com.trugath.k8086.bus

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.cpu.IO_PORT_COUNT

// Routes I/O port accesses to registered devices. Ports with no registered device
// fall back to the CPU's flat ioPorts array (see Emulator8086), which keeps simple
// scratch ports working without a dedicated device model.
class IoBus {
    private val devices = arrayOfNulls<IoDevice>(IO_PORT_COUNT)
    private val owners = arrayOfNulls<String>(IO_PORT_COUNT)

    fun map(device: IoDevice, ports: Iterable<Int>, owner: String? = null) {
        for (p in ports) {
            require(p in 0 until IO_PORT_COUNT) { "I/O port out of range: 0x${p.toString(16)}" }
            val existing = devices[p]
            if (existing != null && existing !== device) {
                val who = owners[p] ?: "unknown"
                throw IllegalStateException(
                    "I/O port 0x${p.toString(16)} already mapped to '$who'"
                )
            }
            devices[p] = device
            if (owner != null) owners[p] = owner
        }
    }

    fun map(device: IoDevice, vararg ports: Int) = map(device, ports.asIterable())

    fun unmap(ports: Iterable<Int>) {
        for (p in ports) {
            if (p in 0 until IO_PORT_COUNT) {
                devices[p] = null
                owners[p] = null
            }
        }
    }

    fun deviceFor(port: Int): IoDevice? =
        if (port in 0 until IO_PORT_COUNT) devices[port] else null

    fun ownerFor(port: Int): String? =
        if (port in 0 until IO_PORT_COUNT) owners[port] else null
}

// A source of maskable hardware interrupts (the 8259A PIC). The CPU polls this
// between instructions when interrupts are enabled.
interface InterruptSource {
    // Highest-priority pending interrupt vector (8..0x0F on the XT), or -1 if none.
    fun pendingVector(): Int
    // Called by the CPU once it has begun servicing the returned vector, so the
    // PIC can move the request from "requested" to "in service".
    fun acknowledge(vector: Int)
}
