package com.trugath.k8086

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.cpu.REG_CS
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * rmDOS boot on the 80286 core. Previously stalled in PSPInit: leftover
 * segment-override holds made consecutive ES: MOV instructions accumulate past
 * the 10-byte limit and spuriously #GP.
 */
class Boot80286IntegrationTest {
    @Test
    fun bootsRmDosToPromptOn80286() {
        TestAssets.assumeRomsPresent()
        TestAssets.assumeFloppyPresent()

        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(cpu = CpuModel.I80286),
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                realtime = false,
            ),
        )
        machine.prepareBoot(listOf(TestAssets.floppy.absolutePath))
        val cpu = machine.cpu

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
                if (v >= 0) {
                    machine.pic.acknowledge(v)
                    cpu.serviceInterrupt(v)
                }
                steps++
                // Idle at the DOS prompt often HLT-waits in BIOS INT 16h; still sample VRAM.
                if (steps % 50_000L == 0L && screenText().any { it.contains("A:>") }) {
                    reachedPrompt = true
                    break
                }
                continue
            }
            machine.pollPostResumeF1()

            if (!cpu.step()) break
            machine.pit.tickCpuCycles(15)
            machine.cga!!.tickCpuCycles(15)
            if (cpu.isTrapPending()) cpu.serviceInterrupt(1)
            cpu.updateTrapPending()
            if (cpu.interruptsEnabled() && !cpu.prefixActive() && !cpu.trapFlagSet()) {
                val v = machine.pic.pendingVector()
                if (v >= 0) {
                    machine.pic.acknowledge(v)
                    cpu.serviceInterrupt(v)
                }
            }
            steps++

            if (steps % 50_000L == 0L && screenText().any { it.contains("A:>") }) {
                reachedPrompt = true
                break
            }
        }

        val screen = screenText()
        val promptOnScreen = screen.any { it.contains("A:>") }
        File("build").mkdirs()
        File("build/boot-80286-screen.txt").writeText(
            "steps=$steps reachedPrompt=$reachedPrompt promptOnScreen=$promptOnScreen final=" +
                String.format("%04X:%04X", cpu.getReg16(REG_CS), cpu.getIp()) + "\n" +
                "=== CGA text screen (80x25) ===\n" + screen.joinToString("\n") + "\n",
        )
        machine.shutdown()

        val joined = screen.joinToString("\n")
        assertTrue(joined.contains("rmDOS"), "kernel banner should appear; screen was:\n$joined")
        // Assert final VRAM: early-exit sampling can race the last paint under guest FDC.
        assertTrue(
            reachedPrompt || promptOnScreen,
            "should reach the A:> DOS prompt; screen was:\n$joined",
        )
    }
}
