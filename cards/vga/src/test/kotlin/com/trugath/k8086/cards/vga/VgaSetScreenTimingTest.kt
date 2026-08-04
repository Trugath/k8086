
package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertTrue

class VgaSetScreenTimingTest {
    @Test
    fun fiveSuccessiveHblanksFitInOneRetrace() {
        val v = VgaCore()
        // Land at start of HBlank on an active line.
        v.frameCycle = (10 * VgaCore.CYCLES_PER_LINE + VgaCore.ACTIVE_LINE_CYCLES).toLong()
        var streak = 0
        // Each guest IN is ~8 CPU clocks; Machine ticks the card by that amount after IN.
        repeat(20) {
            val st = v.status3da()
            v.tickCpuCycles(8)
            val hblank = (st and 0x09) == 0x01
            if (hblank) streak++ else streak = 0
            if (streak >= 5) return
        }
        assertTrue(false, "never got 5 successive HBlanks; last frameCycle=${v.frameCycle}")
    }

    @Test
    fun waitVblSeesVsyncToggle() {
        val v = VgaCore()
        v.frameCycle = 0
        var sawClear = false
        var sawSet = false
        repeat(VgaCore.CYCLES_PER_FRAME / 4) {
            val st = v.status3da()
            v.tickCpuCycles(8)
            if ((st and 8) == 0) sawClear = true
            if (sawClear && (st and 8) != 0) { sawSet = true; return }
        }
        assertTrue(sawSet, "WaitVBL pattern failed clear=$sawClear set=$sawSet")
    }
}
