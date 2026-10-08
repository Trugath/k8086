package com.trugath.k8086

import com.trugath.k8086.cpu.REG_CS
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MachineBootRunTest {
    @Test
    fun bootRunLoopPassesBiosRefreshWait() {
        TestAssets.assumeRomsPresent()
        TestAssets.assumeFloppyPresent()
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            showVideo = false,
        )
        // Enough instructions to clear the early PIT/refresh wait that previously
        // fell through to HLT with IF=0 and aborted Machine.run().
        machine.boot(
            floppyImage = TestAssets.floppy.absolutePath,
            maxInstructions = 2_000_000L,
        )
        val ip = machine.cpu.getIp()
        val cs = machine.cpu.getReg16(REG_CS)
        assertTrue(
            !(cs == 0xF000 && ip == 0xE0F9),
            "should not stop at post-HLT refresh failure (CS:IP=${"%04X".format(cs)}:${"%04X".format(ip)})",
        )
        val hasText = (0 until 25).any { row ->
            (0 until 80).any { col ->
                val ch = machine.cpu.readPhysByte(0xB8000 + (row * 80 + col) * 2)
                ch in 32..126 && ch != 0x20
            }
        }
        assertTrue(hasText, "POST should have written visible text to CGA memory")
    }
}
