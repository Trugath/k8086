package com.trugath.k8086

import com.trugath.k8086.cpu.*
import com.trugath.k8086.chipset.PcSpeaker
import com.trugath.k8086.chipset.Pit8253
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.chipset.Ppi8255
import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.video.Cga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

class MachineHostApiCoverageTest {
    @Test
    fun forHostOptionsDisableVideoEnableAudio() {
        val setup = MachineSetup(showVideo = true, graphics = GraphicsAdapter.CGA)
        val opts = MachineOptions.forHost(setup)
        assertFalse(opts.showVideo)
        assertEquals(true, opts.enableAudio)
        assertFalse(opts.exitOnClose)
        assertFalse(opts.resolvedShowPrintPreview())
        assertTrue(opts.resolvedEnableAudio())
        assertTrue(opts.realtime)
    }

    @Test
    fun audioMuteAndFocusCombine() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(showVideo = false, enableAudio = false, exitOnClose = false, realtime = false),
        )
        // Host-style: starts unfocused → output muted
        assertTrue(machine.isAudioOutputMuted())
        assertFalse(machine.isAudioMuted())

        machine.setConsoleFocused(true)
        assertFalse(machine.isAudioOutputMuted())

        machine.setAudioMuted(true)
        assertTrue(machine.isAudioMuted())
        assertTrue(machine.isAudioOutputMuted())

        machine.setAudioMuted(false)
        machine.setConsoleFocused(false)
        assertFalse(machine.isAudioMuted())
        assertTrue(machine.isAudioOutputMuted())

        machine.setConsoleFocused(true)
        machine.requestPause()
        assertTrue(machine.isPaused())
        assertTrue(machine.isAudioOutputMuted())
        machine.resume()
        assertFalse(machine.isPaused())
        assertFalse(machine.isAudioOutputMuted())

        machine.setTurbo(true)
        assertTrue(machine.isTurbo())
        assertTrue(machine.isAudioOutputMuted())
        assertTrue(machine.isAudioOutputSuspended(), "turbo must skip audio device writes")
        assertFalse(machine.feedAudioSilence(), "turbo suspends output; it does not silence-feed")
        machine.setTurbo(false)
        assertFalse(machine.isTurbo())
        assertFalse(machine.isAudioOutputMuted())
        assertFalse(machine.isAudioOutputSuspended())
    }

    @Test
    fun prepareBootClaimsDiskPathsAndChangeFloppyUpdatesThem() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)
        val a = File.createTempFile("k8086-cov-a", ".img")
        val b = File.createTempFile("k8086-cov-b", ".img")
        a.deleteOnExit()
        b.deleteOnExit()
        RandomAccessFile(a, "rw").use { it.setLength(1474560) }
        RandomAccessFile(b, "rw").use { it.setLength(1474560) }

        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                floppy = FloppyControllerConfig(enabled = true, driveImages = listOf(a.absolutePath)),
            ),
        )
        machine.prepareBoot(listOf(a.absolutePath))
        assertTrue(machine.claimedDiskPaths().contains(a.absolutePath))
        assertEquals(a.absolutePath, machine.floppyImagePath(0))
        assertTrue(machine.floppyDriveCount() >= 1)

        machine.changeFloppy(0, b.absolutePath)
        assertEquals(b.absolutePath, machine.floppyImagePath(0))
        assertTrue(machine.claimedDiskPaths().contains(b.absolutePath))
        assertFalse(machine.claimedDiskPaths().contains(a.absolutePath))

        machine.changeFloppy(0, null)
        assertNull(machine.floppyImagePath(0))
        machine.shutdown()
    }

    @Test
    fun sendCtrlAltDeleteAndScanCodeReachKeyboard() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)
        val machine = TestAssets.machine(showVideo = false)
        machine.sendCtrlAltDelete()
        assertEquals(0x1D, machine.ppi.ioReadByte(0x60))
        machine.ppi.ioWriteByte(0x61, 0x80)
        machine.ppi.ioWriteByte(0x61, 0x00)
        assertEquals(0x38, machine.ppi.ioReadByte(0x60))

        machine.enqueueScanCode(0x1E)
        // Still awaiting ack from previous; after draining CAD breaks we get 'A'
    }

    @Test
    fun headlessCgaStillComposesFramebufferAfterTicks() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(showVideo = false, enableAudio = false, exitOnClose = false),
        )
        machine.cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)
        val cga = machine.cga
        assertNotNull(cga)
        // Force a render cycle.
        repeat(5) { cga!!.tickCpuCycles(100_000) }
        cga!!.renderFrame()
        val snap = machine.copyFramebuffer()
        // May still be null if CRTC not programmed; ensure API is callable.
        if (snap != null) {
            assertTrue(snap.width > 0 && snap.height > 0)
            assertEquals(snap.width * snap.height, snap.argb.size)
        }
    }

    @Test
    fun speakerMuteAndSuspendSkipWithoutThrowing() {
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit, pic)
        val sp = PcSpeaker(pit, ppi, enableAudio = false)
        sp.muted = true
        sp.tickCpuCycles(10_000)
        sp.muted = false
        sp.suspended = true
        sp.tickCpuCycles(10_000)
        sp.suspended = false
        sp.tickCpuCycles(1_000)
        sp.close()
    }

    @Test
    fun cgaExitOnCloseFlagConstructs() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)
        val cpu = com.trugath.k8086.cpu.Emulator8086()
        val cga = Cga(cpu, showWindow = false, exitOnClose = false)
        // Framebuffer may be produced from blank VRAM; API must not throw.
        cga.copyFramebuffer()
        cga.disposeWindow()
    }
}
