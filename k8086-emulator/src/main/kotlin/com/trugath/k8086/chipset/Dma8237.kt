package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.cpu.Emulator8086

// Intel 8237 DMA controller at ports 0x00-0x0F plus the XT page registers at
// 0x80-0x8F. Four channels: 0 = DRAM refresh, 1 = spare, 2 = floppy disk,
// 3 = hard disk. The controller programs a 16-bit current address and count per
// channel (loaded low-then-high through a shared byte-pointer flip-flop); combined
// with the channel's 8-bit page register this forms the 20-bit transfer address.
//
// POST programs and reads back the address registers (a controller presence test)
// and sets up channel 0 for memory refresh. The floppy controller (Phase 6) moves
// bytes through channel 2 via [dmaWriteByte]/[dmaReadByte].
internal class Dma8237(private val cpu: Emulator8086) : IoDevice {

    private class Channel {
        var baseAddress = 0
        var currentAddress = 0
        var baseCount = 0
        var currentCount = 0
        var page = 0
        var mode = 0
    }

    private val channels = Array(4) { Channel() }
    private var flipFlop = false      // false = next byte is low, true = high
    private var command = 0
    private var status = 0            // bits 0-3: TC reached; bits 4-7: DRQ pending
    private var mask = 0x0F           // all channels masked at reset
    /** XT port 0x80 scratch / refresh-related latch (decoded but not a page reg). */
    private var port80Scratch = 0

    override fun ioReadByte(port: Int): Int {
        if (port == 0x80) return port80Scratch and 0xFF
        // Page registers.
        pageChannelForPort(port)?.let { return channels[it].page and 0xFF }
        return when (port) {
            in 0x00..0x07 -> {
                val ch = channels[port / 2]
                val value = if (port % 2 == 0) ch.currentAddress else ch.currentCount
                readWithFlipFlop(value)
            }
            0x08 -> {
                val s = status
                status = status and 0xF0 // reading status clears the TC bits
                s
            }
            // Single-channel and all-channel mask: PC clones return the mask nibble.
            0x0A, 0x0F -> mask and 0x0F
            0x0D -> 0 // temporary register
            else -> 0xFF
        }
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        if (port == 0x80) {
            port80Scratch = v
            return
        }
        pageChannelForPort(port)?.let { channels[it].page = v; return }
        when (port) {
            in 0x00..0x07 -> {
                val ch = channels[port / 2]
                if (port % 2 == 0) { // address
                    ch.baseAddress = writeWithFlipFlop(ch.baseAddress, v)
                    ch.currentAddress = ch.baseAddress
                } else { // count
                    ch.baseCount = writeWithFlipFlop(ch.baseCount, v)
                    ch.currentCount = ch.baseCount
                    // Reprogramming a channel's count re-arms it: clear its stale terminal
                    // -count status so a fresh transfer isn't cut short after one byte.
                    status = status and (1 shl (port / 2)).inv()
                }
            }
            0x08 -> command = v
            0x09 -> { // software request: bit2 set → request, clear → release
                val ch = v and 0x03
                val bit = 1 shl (4 + ch)
                status = if ((v and 0x04) != 0) status or bit else status and bit.inv()
            }
            0x0A -> { // single channel mask
                val ch = v and 0x03
                mask = if ((v and 0x04) != 0) mask or (1 shl ch) else mask and (1 shl ch).inv()
            }
            0x0B -> { // mode register
                channels[v and 0x03].mode = v
            }
            0x0C -> flipFlop = false // clear byte-pointer flip-flop
            0x0D -> reset()          // master clear
            0x0E -> mask = 0         // clear all mask bits
            0x0F -> mask = v and 0x0F
        }
    }

    private fun reset() {
        flipFlop = false
        command = 0
        status = 0
        mask = 0x0F
        port80Scratch = 0
    }

    private fun readWithFlipFlop(value: Int): Int {
        val b = if (!flipFlop) value and 0xFF else (value shr 8) and 0xFF
        flipFlop = !flipFlop
        return b
    }

    private fun writeWithFlipFlop(current: Int, byte: Int): Int {
        val result = if (!flipFlop) (current and 0xFF00) or byte
        else (current and 0x00FF) or (byte shl 8)
        flipFlop = !flipFlop
        return result
    }

    // The XT wires page-register ports to specific channels.
    private fun pageChannelForPort(port: Int): Int? = when (port) {
        0x87 -> 0
        0x83 -> 1
        0x81 -> 2
        0x82 -> 3
        in 0x80..0x8F -> -1 // decoded but unassigned (e.g. 0x80 handled above)
        else -> null
    }?.takeIf { it >= 0 }

    // 20-bit physical transfer address for a channel.
    private fun physicalAddress(ch: Int): Int =
        ((channels[ch].page and 0xFF) shl 16) or (channels[ch].currentAddress and 0xFFFF)

    // True once a channel has transferred its full programmed count.
    fun terminalCount(ch: Int): Boolean = (status and (1 shl ch)) != 0

    fun isMasked(ch: Int): Boolean = (mask and (1 shl ch)) != 0

    // Move one byte from a device into memory (device -> memory, e.g. floppy read).
    fun dmaWriteByte(ch: Int, byte: Int) {
        cpu.writePhysByte(physicalAddress(ch), byte and 0xFF)
        advance(ch)
    }

    // Move one byte from memory to a device (memory -> device, e.g. floppy write).
    fun dmaReadByte(ch: Int): Int {
        val b = cpu.readPhysByte(physicalAddress(ch))
        advance(ch)
        return b
    }

    private fun advance(ch: Int) {
        val c = channels[ch]
        val decrement = (c.mode and 0x20) != 0 // mode bit 5: address decrement
        c.currentAddress = (c.currentAddress + if (decrement) -1 else 1) and 0xFFFF
        c.currentCount = (c.currentCount - 1) and 0xFFFF
        if (c.currentCount == 0xFFFF) {
            onTerminalCount(ch)
        }
    }

    private fun onTerminalCount(ch: Int) {
        val c = channels[ch]
        status = status or (1 shl ch)
        val autoinit = (c.mode and 0x10) != 0
        if (autoinit) {
            c.currentAddress = c.baseAddress
            c.currentCount = c.baseCount
        } else {
            // 8237A: TC sets the channel mask unless autoinit.
            mask = mask or (1 shl ch)
        }
    }

    // A DRAM-refresh cycle on channel 0, triggered by PIT counter 1. Each cycle
    // advances the address and decrements the count; when count rolls under,
    // terminal count is latched and (in autoinit mode) address+count reload.
    fun refreshCycle() {
        if (isMasked(0)) return
        val c = channels[0]
        val decrement = (c.mode and 0x20) != 0
        c.currentAddress = (c.currentAddress + if (decrement) -1 else 1) and 0xFFFF
        c.currentCount = (c.currentCount - 1) and 0xFFFF
        if (c.currentCount == 0xFFFF) {
            onTerminalCount(0)
        }
    }

    // Inspection helpers.
    fun currentAddress(ch: Int): Int = channels[ch].currentAddress
    fun currentCount(ch: Int): Int = channels[ch].currentCount
    fun pageRegister(ch: Int): Int = channels[ch].page
}
