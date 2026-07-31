package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice

// Intel 8255 Programmable Peripheral Interface at ports 0x60-0x63. On the IBM PC/XT
// it carries the keyboard data port, the system configuration DIP switch bank (SW1),
// speaker/timer gating, and parity/I-O-check status.
//
// Port A (0x60): keyboard scan code input (when PB7 low), or SW1 (when PB7 high).
// Port B (0x61): output control (speaker gate/data, switch nibble select, keyboard
//                clock/clear, parity/I-O-check enables).
// Port C (0x62): status input - a SW1 nibble (selected by PB3), timer-2 output,
//                cassette, and parity/I-O channel error bits.
// Port 0x63:     8255 mode/control word (port direction programming).
//
// The keyboard controller (Phase 7) feeds scan codes in via [keyboardData] and pulses
// IRQ1; POST reads the DIP switches to learn the video type, floppy count, and (on the
// 5150) memory size.
class Ppi8255(
    private val pit: Pit8253? = null,
    private val pic: Pic8259? = null,
) : IoDevice {

    // SW1 equipment configuration byte (the low byte of the INT 11h equipment word):
    //   bit0   : IPL diskette drive(s) installed (1 = yes)
    //   bit1   : 8087 math coprocessor present
    //   bit2-3 : planar RAM banks (XT: 64/128/192/256 KB on the motherboard)
    //   bit4-5 : initial video mode (01 = CGA 40x25, 10 = CGA 80x25, 11 = MDA 80x25)
    //   bit6-7 : number of diskette drives minus one
    // Default: CGA 80x25, one floppy, IPL present, no coprocessor, 256 KB banks.
    var sw1: Int = 0b0010_1101

    /**
     * Apply motherboard DIP-derived fields (8087, RAM banks, video), preserving floppy bits.
     */
    fun configureMotherboard(config: com.trugath.k8086.config.MotherboardConfig) {
        val floppyBits = sw1 and 0xC1 // bit0 + bits 6–7
        sw1 = (config.sw1WithoutFloppies() and 0x3E) or floppyBits
    }

    /**
     * Update SW1 floppy-related bits for [count] drives (0–4).
     * bit0 = IPL diskette installed; bits 6–7 = drive count minus one (when count ≥ 1).
     * Video / coprocessor / RAM-bank bits are preserved.
     */
    fun configureFloppyDrives(count: Int) {
        val n = count.coerceIn(0, 4)
        sw1 = sw1 and 0x3E // clear bit0 and bits 6–7
        if (n > 0) {
            sw1 = sw1 or 0x01
            sw1 = sw1 or (((n - 1) and 0x03) shl 6)
        }
    }

    fun floppyDriveCount(): Int =
        if ((sw1 and 0x01) == 0) 0 else ((sw1 shr 6) and 0x03) + 1

    private var portB = 0

    // Latched keyboard scan code, delivered on port A when PB7 is low. The keyboard
    // model writes here and raises IRQ1.
    var keyboardData: Int = 0

    // Invoked on the rising edge of PB7, which the INT 9 handler uses to clear the
    // current scan code and let the keyboard controller present the next one.
    var onKeyboardClear: (() -> Unit)? = null

    fun keyboardClearRequested(): Boolean = (portB and 0x80) != 0 || (portB and 0x40) == 0

    override fun ioReadByte(port: Int): Int = when (port and 3) {
        0 -> { // 0x60 - Port A
            if ((portB and 0x80) != 0) sw1 // PB7 high: read SW1 directly
            else keyboardData
        }
        1 -> portB // 0x61 - Port B (reads back last written value)
        2 -> readPortC() // 0x62 - Port C
        else -> 0 // 0x63 - control (write only)
    }

    private fun readPortC(): Int {
        // Low nibble: the SW1 nibble selected by PB3 (0 = switches 1-4, 1 = 5-8).
        val nibble = if ((portB and 0x08) != 0) (sw1 shr 4) and 0x0F else sw1 and 0x0F
        var value = nibble
        // Bit 5: timer-2 (speaker) output. Bits 4/6/7: cassette, I/O-channel check,
        // RAM parity error - all reported clear (no errors) on a healthy machine.
        if (pit != null && pit.timer2Output()) value = value or 0x20
        return value and 0xFF
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (port and 3) {
            1 -> { // 0x61 - Port B
                val prevB = portB
                portB = v
                // PB0 gates timer-2 counting; PB1 enables the speaker data line.
                pit?.setGate2((v and 0x01) != 0)
                // The rising edge of PB7 is the keyboard acknowledge: it clears the
                // currently latched scan code and lets the controller present the next
                // queued code. Clearing only on this edge (not on the PB7-low restore
                // write the INT 9 handler issues immediately after) avoids wiping a
                // freshly delivered code, which otherwise reached INT 9 as a stray 0x00.
                if ((v and 0x80) != 0 && (prevB and 0x80) == 0) {
                    keyboardData = 0
                    onKeyboardClear?.invoke()
                }
            }
            3 -> { /* 0x63 - 8255 control word: port-direction programming, no state to model */ }
            // Ports A and C are inputs; writes are ignored.
        }
    }

    fun portBValue(): Int = portB
}
