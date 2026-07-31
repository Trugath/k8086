package com.trugath.k8086

import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.MachineSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MachineLifecycleTest {
    @Test
    fun requestStopEndsRunLoop() {
        val u18 = File("roms/u18.bin")
        val u19 = File("roms/u19.bin")
        assumeTrue(u18.isFile && u19.isFile, "System ROMs required")

        val machine = Machine(
            u18.absolutePath,
            u19.absolutePath,
            MachineOptions(
                graphics = GraphicsAdapter.CGA,
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                enableCom1 = false,
                realtime = false,
                floppy = com.trugath.k8086.config.FloppyControllerConfig(enabled = false),
            ),
        )
        machine.prepareBoot(MachineSetup(showVideo = false, enableCom1 = false))

        val done = AtomicBoolean(false)
        val t = thread(name = "machine-lifecycle-test") {
            machine.run()
            done.set(true)
        }
        Thread.sleep(100)
        machine.requestStop()
        t.join(10_000)
        assertTrue(done.get(), "run() should exit after requestStop")
        assertTrue(machine.instructionCount() > 0)
    }

    @Test
    fun pauseHoldsRunLoopUntilResumeThenStop() {
        val u18 = File("roms/u18.bin")
        val u19 = File("roms/u19.bin")
        assumeTrue(u18.isFile && u19.isFile, "System ROMs required")

        val machine = Machine(
            u18.absolutePath,
            u19.absolutePath,
            MachineOptions(
                graphics = GraphicsAdapter.CGA,
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                enableCom1 = false,
                realtime = false,
                floppy = com.trugath.k8086.config.FloppyControllerConfig(enabled = false),
            ),
        )
        machine.prepareBoot(MachineSetup(showVideo = false, enableCom1 = false))
        machine.requestPause()

        val done = AtomicBoolean(false)
        val t = thread(name = "machine-pause-test") {
            machine.run()
            done.set(true)
        }
        Thread.sleep(80)
        assertTrue(machine.isPaused())
        assertFalse(done.get(), "paused run loop must not exit")
        val pausedCount = machine.instructionCount()
        Thread.sleep(50)
        assertEquals(pausedCount, machine.instructionCount(), "paused machine should not execute")

        machine.resume()
        Thread.sleep(50)
        assertTrue(machine.instructionCount() > pausedCount)

        machine.requestStop()
        t.join(10_000)
        assertTrue(done.get())
    }
}
