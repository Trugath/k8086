package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.bus.InterruptSource

// Intel 8259A Programmable Interrupt Controller. The IBM PC/XT (and thus the 5155)
// has a single master 8259A at ports 0x20/0x21 handling IRQ0-IRQ7, mapped by the
// BIOS to interrupt vectors 0x08-0x0F.
//
// This models the pieces the XT BIOS and DOS rely on: the ICW1-ICW4 init sequence,
// the OCW1 interrupt mask, OCW2 end-of-interrupt, OCW3 read-register select, and
// fixed-priority resolution of pending edge-triggered requests (IRQ0 highest).
class Pic8259 : IoDevice, InterruptSource {
    private var imr = 0xFF          // interrupt mask register (1 = masked)
    private var irr = 0             // interrupt request register
    private var isr = 0             // in-service register
    private var vectorOffset = 0x08 // ICW2: base vector for IRQ0

    // ICW init sequence state.
    private var initStep = 0        // 0 = idle; 1 = expecting ICW2; 2 = ICW3; 3 = ICW4
    private var icw1 = 0
    private var readIsr = false     // OCW3: 0 = read IRR, 1 = read ISR

    fun lowerIrq(irq: Int) {
        if (irq in 0..7) irr = irr and (1 shl irq).inv()
    }

    // Raise (assert) an IRQ line. Edge-triggered: sets the request bit.
    fun raiseIrq(irq: Int) {
        if (irq in 0..7) irr = irr or (1 shl irq)
    }

    override fun ioReadByte(port: Int): Int = when (port and 1) {
        0 -> if (readIsr) isr else irr   // 0x20
        else -> imr                       // 0x21
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        if ((port and 1) == 0) {
            // Command port 0x20.
            when {
                (v and 0x10) != 0 -> { // ICW1: begin initialization
                    icw1 = v
                    imr = 0
                    isr = 0
                    irr = 0
                    initStep = 1 // next data write is ICW2
                }
                (v and 0x08) != 0 -> { // OCW3
                    // bit1 selects read register (bit0 = read enable).
                    if ((v and 0x02) != 0) readIsr = (v and 0x01) != 0
                }
                else -> { // OCW2 (EOI group)
                    val eoi = v and 0x20
                    if (eoi != 0) {
                        if ((v and 0x40) != 0) {
                            // Specific EOI: clear the named level.
                            isr = isr and (1 shl (v and 0x07)).inv()
                        } else {
                            // Non-specific EOI: clear the highest-priority in-service bit.
                            val bit = lowestSetBit(isr)
                            if (bit >= 0) isr = isr and (1 shl bit).inv()
                        }
                    }
                }
            }
        } else {
            // Data port 0x21.
            when (initStep) {
                1 -> { // ICW2: vector base (low 3 bits ignored)
                    vectorOffset = v and 0xF8
                    initStep = if ((icw1 and 0x02) == 0) 2 else 3 // single vs cascade
                }
                2 -> { // ICW3 (cascade config, ignored on the single-PIC XT)
                    initStep = 3
                }
                3 -> { // ICW4
                    initStep = 0 // initialization complete
                }
                else -> { // OCW1: interrupt mask
                    imr = v
                }
            }
        }
    }

    // Highest-priority requested, unmasked interrupt not blocked by an in-service
    // interrupt of equal-or-higher priority. Returns its CPU vector, or -1.
    override fun pendingVector(): Int {
        val requestable = irr and imr.inv()
        if (requestable == 0) return -1
        val higherInService = isr
        for (irq in 0..7) {
            val bit = 1 shl irq
            if ((requestable and bit) != 0) {
                // Block if an equal or higher priority level is already in service.
                if ((higherInService and ((bit shl 1) - 1)) != 0) return -1
                return vectorOffset + irq
            }
        }
        return -1
    }

    override fun acknowledge(vector: Int) {
        val irq = vector - vectorOffset
        if (irq in 0..7) {
            val bit = 1 shl irq
            irr = irr and bit.inv()
            isr = isr or bit
        }
    }

    private fun lowestSetBit(x: Int): Int {
        if (x == 0) return -1
        for (i in 0..7) if ((x and (1 shl i)) != 0) return i
        return -1
    }

    /** Clear stuck in-service bits (e.g. CAD warm-boot jumps out of INT 9 without EOI). */
    fun clearInService() {
        isr = 0
    }

    // Test/inspection helpers.
    fun maskRegister(): Int = imr
    fun requestRegister(): Int = irr
    fun inServiceRegister(): Int = isr
    fun vectorBase(): Int = vectorOffset
}
