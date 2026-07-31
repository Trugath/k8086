package com.trugath.k8086

import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Boot rmDOS and Ctrl+Alt+Del repeatedly. Relies on honest chipset behaviour:
 * cycle-table timing (no floor), keyboard BAT on PB6, BIOS ICW1 clearing stuck
 * IRQ1 — not host-side PIC quiesce hacks.
 */
class WarmBootCadFromDosTest {
    @Test
    fun ctrlAltDelFromDosPromptAvoidsPost101() {
        TestAssets.assumeRomsPresent()
        TestAssets.assumeFloppyPresent()
        val machine = Machine(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath, showVideo = false)
        machine.cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)
        machine.cpu.setupBootDisks(TestAssets.floppy.absolutePath, null)
        val cpu = machine.cpu

        var irqLineLatched = false

        fun flag() = cpu.readPhysByte(0x472) or (cpu.readPhysByte(0x473) shl 8)
        fun noticeWarmBoot() {
            val f = flag()
            val cs = cpu.getReg16(REG_CS)
            val ip = cpu.getIp()
            val atCadEntry = cs == 0xF000 && (ip == 0xEA82 || ip == 0xE05B)
            if (f == 0x1234 && atCadEntry) {
                if (!machine.keyboard.inReset) {
                    machine.keyboard.inReset = true
                    machine.keyboard.reset()
                    machine.cga?.allowBdaRowsRestamp()
                }
            } else if (cs < 0xF000) {
                machine.keyboard.inReset = false
            }
        }
        fun pollIrq(ssShadow: Boolean = false) {
            val canTake = cpu.interruptsEnabled() && !cpu.prefixActive() && !cpu.trapFlagSet() && !ssShadow
            if (!canTake) {
                irqLineLatched = false
            } else if (irqLineLatched) {
                val v = machine.pic.pendingVector()
                if (v >= 0) {
                    machine.pic.acknowledge(v)
                    cpu.serviceInterrupt(v)
                }
            }
            irqLineLatched = cpu.interruptsEnabled() && !cpu.prefixActive() && !cpu.trapFlagSet() &&
                machine.pic.pendingVector() >= 0
        }
        fun stepOne() {
            if (cpu.serviceNmiIfPending()) {
                machine.tickDevices(Machine.peripheralCyclesFor(cpu.lastInstructionCycles))
                noticeWarmBoot()
                return
            }
            if (cpu.isHalted()) {
                machine.tickDevices(Machine.AVG_CYCLES_PER_INSTRUCTION)
                pollIrq()
                noticeWarmBoot()
                return
            }
            if (!cpu.step()) return
            machine.tickDevices(Machine.peripheralCyclesFor(cpu.lastInstructionCycles))
            if (cpu.isTrapPending()) cpu.serviceInterrupt(1)
            cpu.updateTrapPending()
            val ss = cpu.pollSsInterruptShadow()
            if (!ss && cpu.serviceNmiIfPending()) irqLineLatched = false else pollIrq(ss)
            noticeWarmBoot()
        }
        fun screen(): String = buildString {
            for (i in 0 until 80 * 25) {
                val ch = cpu.readPhysByte(0xB8000 + i * 2)
                append(if (ch in 32..126) ch.toChar() else ' ')
            }
        }
        fun printableCount(): Int {
            var n = 0
            for (i in 0 until 80 * 25) {
                val ch = cpu.readPhysByte(0xB8000 + i * 2)
                if (ch in 33..126) n++
            }
            return n
        }

        val prompt = "A:>"

        fun cadAndWaitForPrompt(label: String) {
            machine.keyboard.sendCtrlAltDelete()
            var sawEntry = false
            var saw101 = false
            var saw301 = false
            var screenCleared = false
            var promptGone = false
            var backToPrompt = false
            for (i in 0 until 40_000_000) {
                machine.pollPostResumeF1()
                stepOne()
                if (cpu.getReg16(REG_CS) == 0xF000 && cpu.getIp() == 0xE05B) sawEntry = true
                if (i % 50_000 == 0) {
                    val s = screen()
                    if (s.contains("101")) saw101 = true
                    if (s.contains("301")) saw301 = true
                    if (sawEntry && printableCount() < 8) screenCleared = true
                    if (screenCleared && !s.contains(prompt)) promptGone = true
                    if (promptGone && s.contains(prompt)) {
                        backToPrompt = true
                        break
                    }
                    if (saw101 || saw301) break
                }
            }
            assertTrue(sawEntry, "$label: must enter POST at F000:E05B")
            assertFalse(saw101, "$label: must not report interrupt failure 101")
            assertFalse(saw301, "$label: must not report keyboard error 301")
            assertTrue(promptGone, "$label: warm POST must clear the old DOS screen")
            assertTrue(backToPrompt, "$label: must reach rmDOS prompt again")
            assertEquals(0x1234, flag(), "$label: warm flag must remain 0x1234")
        }

        var steps = 0L
        var prompted = false
        while (steps < 80_000_000L) {
            machine.pollPostResumeF1()
            stepOne()
            steps++
            if (steps % 200_000L == 0L && screen().contains(prompt)) {
                prompted = true
                break
            }
        }
        assertTrue(prompted, "should reach rmDOS prompt before CAD")

        cadAndWaitForPrompt("CAD#1")
        cadAndWaitForPrompt("CAD#2")
        cadAndWaitForPrompt("CAD#3")
    }
}
