package com.trugath.k8086

import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MachineDebugTest {
    private fun headlessMachine() = Machine(
        "roms/missing-u18.bin",
        "roms/missing-u19.bin",
        MachineOptions(
            graphics = GraphicsAdapter.CGA,
            showVideo = false,
            enableAudio = false,
            exitOnClose = false,
            enableCom1 = false,
            realtime = false,
            floppy = FloppyControllerConfig(enabled = false),
        ),
    )

    private fun placeNops(machine: Machine, count: Int = 4) {
        machine.cpu.setReg16(REG_CS, 0x1000)
        machine.cpu.setIp(0)
        for (i in 0 until count) {
            machine.cpu.writePhysByte(0x10000 + i, 0x90)
        }
    }

    @Test
    fun stepOnceAdvancesIpWhilePaused() {
        val machine = headlessMachine()
        placeNops(machine)
        assertFalse(machine.stepOnce(), "step requires pause")
        machine.requestPause()
        assertTrue(machine.stepOnce())
        assertEquals(1, machine.cpu.getIp())
        assertTrue(machine.isPaused())
        assertEquals(1L, machine.instructionCount())
    }

    @Test
    fun cpuDebugStateAndMemoryDumpReflectGuest() {
        val machine = headlessMachine()
        placeNops(machine)
        machine.cpu.setReg16(REG_SS, 0x2000)
        machine.cpu.setReg16(REG_SP, 0x0100)
        val snap = machine.cpuDebugState()
        assertEquals(0x1000, snap.cs)
        assertEquals(0, snap.ip)
        assertEquals(0x10000, snap.linearCsIp)
        assertEquals(1, snap.nextLength)
        assertEquals(listOf(0x90), snap.nextBytes)
        val mem = machine.readGuestMemory(0x10000, 4)
        assertEquals(listOf(0x90, 0x90, 0x90, 0x90), mem)
    }

    @Test
    fun breakpointPausesBeforeExecuteThenStepContinues() {
        val machine = headlessMachine()
        placeNops(machine)
        machine.addBreakpoint(0x10000)
        assertEquals(listOf(0x10000), machine.listBreakpoints())

        val done = AtomicBoolean(false)
        val t = thread(name = "machine-debug-bp") {
            machine.run(maxInstructions = 10_000)
            done.set(true)
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline && !machine.isPaused()) {
            Thread.sleep(10)
        }
        assertTrue(machine.isPaused(), "breakpoint should pause the run loop")
        assertEquals(0, machine.cpu.getIp(), "must stop before executing the NOP")
        assertEquals(0L, machine.instructionCount())

        assertTrue(machine.stepOnce())
        assertEquals(1, machine.cpu.getIp())
        assertEquals(1L, machine.instructionCount())

        machine.requestStop()
        t.join(5_000)
        assertTrue(done.get())
    }
}
