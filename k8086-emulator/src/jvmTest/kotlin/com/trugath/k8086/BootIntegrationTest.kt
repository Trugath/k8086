package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

// End-to-end boot test: run the XT system ROMs through POST and boot the
// rmDOS floppy image all the way to the COMMAND prompt. This exercises the full
// machine (CPU + PIC + PIT + PPI + DMA + CGA + FDC + keyboard) as an integration test.
class BootIntegrationTest {
    @Test
    fun bootsRmDosToPrompt() {
        TestAssets.assumeRomsPresent()
        TestAssets.assumeFloppyPresent()
        val u18 = TestAssets.u18
        val u19 = TestAssets.u19
        val floppy = TestAssets.floppy

        val machine = Machine(u18.absolutePath, u19.absolutePath, showVideo = false)
        machine.cpu.loadSystemRoms(u18.absolutePath, u19.absolutePath)
        machine.cpu.setupBootDisks(floppy.absolutePath, null)
        val cpu = machine.cpu

        // Reads the 80x25 CGA text buffer at 0xB8000 into rows of ASCII.
        fun screenText(): List<String> = (0 until 25).map { row ->
            buildString {
                for (col in 0 until 80) {
                    val ch = cpu.readPhysByte(0xB8000 + (row * 80 + col) * 2)
                    append(if (ch in 32..126) ch.toChar() else ' ')
                }
            }
        }

        var reachedPrompt = false
        var steps = 0L
        while (steps < 80_000_000L) {
            if (cpu.isHalted()) {
                if (!cpu.interruptsEnabled()) break
                machine.pollPostResumeF1()
                machine.pit.tickCpuCycles(15)
                machine.cga!!.tickCpuCycles(15)
                val v = machine.pic.pendingVector()
                if (v >= 0) { machine.pic.acknowledge(v); cpu.serviceInterrupt(v) }
                steps++
                // Idle at the DOS prompt often HLT-waits in BIOS INT 16h; still sample VRAM.
                if (steps % 50_000L == 0L && screenText().any { it.contains("A:>") }) {
                    reachedPrompt = true
                    break
                }
                continue
            }
            // POST may stop at "ERROR (RESUME = F1 KEY)"; Machine injects F1 from CGA text.
            machine.pollPostResumeF1()

            if (!cpu.step()) break
            machine.pit.tickCpuCycles(15)
            machine.cga!!.tickCpuCycles(15)
            if (cpu.isTrapPending()) cpu.serviceInterrupt(1)
            cpu.updateTrapPending()
            if (cpu.interruptsEnabled() && !cpu.prefixActive() && !cpu.trapFlagSet()) {
                val v = machine.pic.pendingVector()
                if (v >= 0) { machine.pic.acknowledge(v); cpu.serviceInterrupt(v) }
            }
            steps++

            // Check for the DOS prompt every so often (scanning VRAM each step is costly).
            if (steps % 50_000L == 0L && screenText().any { it.contains("A:>") }) {
                reachedPrompt = true
                break
            }
        }

        val screen = screenText()
        val promptOnScreen = screen.any { it.contains("A:>") }
        File("build").mkdirs()
        File("build/boot-screen.txt").writeText(
            "steps=$steps reachedPrompt=$reachedPrompt promptOnScreen=$promptOnScreen final=" +
                String.format("%04X:%04X", cpu.getReg16(REG_CS), cpu.getIp()) + "\n" +
                "=== CGA text screen (80x25) ===\n" + screen.joinToString("\n") + "\n"
        )

        val joined = screen.joinToString("\n")
        assertTrue(joined.contains("rmDOS"), "kernel banner should appear; screen was:\n$joined")
        // Assert final VRAM: early-exit sampling can race the last paint under guest FDC.
        assertTrue(
            reachedPrompt || promptOnScreen,
            "should reach the A:> DOS prompt; screen was:\n$joined",
        )
    }
}
