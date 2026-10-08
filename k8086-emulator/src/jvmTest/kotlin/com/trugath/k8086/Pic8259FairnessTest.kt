package com.trugath.k8086

import com.trugath.k8086.chipset.Pic8259
import kotlin.test.Test
import kotlin.test.assertEquals

class Pic8259FairnessTest {
    private fun primedPic(): Pic8259 {
        val pic = Pic8259()
        // ICW1–ICW4: edge, single, 8086 mode, vectors at 08h
        pic.ioWriteByte(0x20, 0x13)
        pic.ioWriteByte(0x21, 0x08)
        pic.ioWriteByte(0x21, 0x09)
        pic.ioWriteByte(0x21, 0x00) // unmask all
        return pic
    }

    @Test
    fun irq0BeatsIrq1ByDefault() {
        val pic = primedPic()
        pic.raiseIrq(0)
        pic.raiseIrq(1)
        assertEquals(0x08, pic.pendingVector())
    }

    @Test
    fun irq1WinsAfterRepeatedIrq0Starvation() {
        val pic = primedPic()
        pic.raiseIrq(0)
        pic.raiseIrq(1)
        // First looks still prefer IRQ0 (counter 1..FAIRNESS_AFTER).
        repeat(Pic8259.IRQ1_FAIRNESS_AFTER) {
            assertEquals(0x08, pic.pendingVector())
        }
        assertEquals(0x09, pic.pendingVector())
    }

    @Test
    fun acknowledgeIrq1ResetsFairness() {
        val pic = primedPic()
        pic.raiseIrq(0)
        pic.raiseIrq(1)
        repeat(Pic8259.IRQ1_FAIRNESS_AFTER) { pic.pendingVector() }
        assertEquals(0x09, pic.pendingVector())
        pic.acknowledge(0x09)
        pic.raiseIrq(1) // still pending with IRQ0
        pic.clearInService()
        // Fairness counter cleared — IRQ0 wins again until the next streak.
        assertEquals(0x08, pic.pendingVector())
    }
}
