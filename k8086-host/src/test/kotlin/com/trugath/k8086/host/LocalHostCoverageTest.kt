package com.trugath.k8086.host

import com.trugath.k8086.protocol.FloppySpec
import com.trugath.k8086.protocol.GraphicsKind
import com.trugath.k8086.protocol.HardDiskSpec
import com.trugath.k8086.protocol.MotherboardSpec
import com.trugath.k8086.protocol.VmDefinition
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocalHostCoverageTest {
    @TempDir
    lateinit var temp: File

    private var host: LocalHost? = null

    @AfterEach
    fun tearDown() {
        host?.close()
        host = null
    }

    @Test
    fun createDuplicateAndUpdateAndReloadFromStore() {
        val store = VmStore(temp)
        host = LocalHost(store)
        val id = VmId(UUID.randomUUID().toString())
        val (u18, u19) = dummyRomPaths()
        val def = sampleDef(id, "alpha", u18 = u18, u19 = u19)
        host!!.createVm(def)
        assertThrows(IllegalArgumentException::class.java) { host!!.createVm(def) }

        host!!.updateVm(def.copy(name = "beta", u18RomPath = u18, u19RomPath = u19))
        assertEquals("beta", host!!.getDefinition(id)!!.name)

        host!!.close()
        host = LocalHost(store)
        assertEquals(1, host!!.listVms().size)
        assertEquals("beta", host!!.listVms().first().name)
    }

    @Test
    fun networkApiSeedsDefault() {
        host = LocalHost(VmStore(temp))
        val net = host!!.network()
        assertTrue(net.listNetworks().isNotEmpty())
        assertNotNull(net.getNetwork("default"))
        assertEquals("10.0.2.2", net.getNetwork("default")!!.gatewayIp)
    }

    @Test
    fun metricsUnknownVmIsNull() {
        host = LocalHost(VmStore(temp))
        assertNull(host!!.metrics(VmId("nope")))
        assertNull(host!!.pollConsoleFrame(VmId("nope")))
    }

    @Test
    fun refusesSharedDiskImageAcrossVms() {
        assumeRoms()
        val floppy = File(temp, "shared.img")
        RandomAccessFile(floppy, "rw").use { it.setLength(1474560) }

        host = LocalHost(VmStore(temp))
        val a = VmId(UUID.randomUUID().toString())
        val b = VmId(UUID.randomUUID().toString())
        host!!.createVm(sampleDef(a, "a", floppy = FloppySpec(true, listOf(floppy.absolutePath))))
        host!!.createVm(sampleDef(b, "b", floppy = FloppySpec(true, listOf(floppy.absolutePath))))

        host!!.startVm(a)
        awaitState(a, VmState.Running)
        assertThrows(IllegalStateException::class.java) { host!!.startVm(b) }
        host!!.stopVm(a)
        awaitState(a, VmState.Stopped)
    }

    @Test
    fun consoleCadAndScanCodesWhileRunning() {
        assumeRoms()
        host = LocalHost(VmStore(temp))
        val id = VmId(UUID.randomUUID().toString())
        host!!.createVm(sampleDef(id, "console"))
        host!!.startVm(id)
        awaitState(id, VmState.Running)
        Thread.sleep(150)

        host!!.sendScanCode(id, 0x1E)
        host!!.sendCtrlAltDelete(id)
        host!!.setConsoleFocused(id, true)
        host!!.setConsoleFocused(id, false)
        // Frame may be null early in POST; just ensure call does not throw.
        host!!.pollConsoleFrame(id)

        val m = host!!.metrics(id)!!
        assertTrue(m.instructionCount > 0)
        assertEquals(VmState.Running, m.state)

        host!!.stopVm(id)
        awaitState(id, VmState.Stopped)
    }

    @Test
    fun changeFloppyOnRunningVm() {
        assumeRoms()
        val a = File(temp, "a.img")
        val b = File(temp, "b.img")
        RandomAccessFile(a, "rw").use { it.setLength(1474560) }
        RandomAccessFile(b, "rw").use { it.setLength(1474560) }

        host = LocalHost(VmStore(temp))
        val id = VmId(UUID.randomUUID().toString())
        host!!.createVm(sampleDef(id, "floppy", floppy = FloppySpec(true, listOf(a.absolutePath))))
        host!!.startVm(id)
        awaitState(id, VmState.Running)
        Thread.sleep(100)

        host!!.changeFloppy(id, 0, b.absolutePath)
        assertEquals(b.absolutePath, host!!.metrics(id)!!.floppyPaths[0])
        host!!.changeFloppy(id, 0, null)
        assertNull(host!!.metrics(id)!!.floppyPaths[0])

        host!!.stopVm(id)
        awaitState(id, VmState.Stopped)
    }

    @Test
    fun hotMountedFloppyReleasedOnStop() {
        assumeRoms()
        val img = File(temp, "hot.img")
        RandomAccessFile(img, "rw").use { it.setLength(1474560) }

        host = LocalHost(VmStore(temp))
        val id = VmId(UUID.randomUUID().toString())
        // Start with an empty drive list so the image is only claimed via changeFloppy.
        host!!.createVm(sampleDef(id, "hot", floppy = FloppySpec(true, emptyList())))
        host!!.startVm(id)
        awaitState(id, VmState.Running)
        Thread.sleep(100)

        host!!.changeFloppy(id, 0, img.absolutePath)
        assertEquals(img.absolutePath, host!!.metrics(id)!!.floppyPaths[0])

        host!!.stopVm(id)
        awaitState(id, VmState.Stopped)

        // Previously leaked in claimedPaths; remount / restart must succeed.
        host!!.startVm(id)
        awaitState(id, VmState.Running)
        Thread.sleep(100)
        host!!.changeFloppy(id, 0, img.absolutePath)
        assertEquals(img.absolutePath, host!!.metrics(id)!!.floppyPaths[0])

        host!!.stopVm(id)
        awaitState(id, VmState.Stopped)
    }

    @Test
    fun updateWhileRunningRejected() {
        assumeRoms()
        host = LocalHost(VmStore(temp))
        val id = VmId(UUID.randomUUID().toString())
        val def = sampleDef(id, "busy")
        host!!.createVm(def)
        host!!.startVm(id)
        awaitState(id, VmState.Running)
        assertThrows(IllegalArgumentException::class.java) {
            host!!.updateVm(def.copy(name = "nope"))
        }
        host!!.stopVm(id)
        awaitState(id, VmState.Stopped)
    }

    @Test
    fun startUnknownVmFails() {
        host = LocalHost(VmStore(temp))
        assertThrows(IllegalStateException::class.java) {
            host!!.startVm(VmId("missing"))
        }
    }

    private fun assumeRoms() {
        assumeTrue(
            File("roms/u18.bin").isFile && File("roms/u19.bin").isFile,
            "System ROMs required",
        )
    }

    private fun awaitState(id: VmId, want: VmState, seconds: Long = 15) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            val s = host!!.listVms().first { it.id == id }
            if (s.state == want) return
            if (s.state == VmState.Error && want != VmState.Error) {
                throw AssertionError("VM Error: ${s.errorMessage}")
            }
            Thread.sleep(40)
        }
        throw AssertionError("Timed out waiting for $want (got ${host!!.listVms().first { it.id == id }.state})")
    }

    private fun dummyRomPaths(): Pair<String, String> {
        val u18 = File(temp, "dummy-u18.bin").also {
            if (!it.exists()) it.writeBytes(ByteArray(32_768) { 0x90.toByte() })
        }
        val u19 = File(temp, "dummy-u19.bin").also {
            if (!it.exists()) it.writeBytes(ByteArray(8_192) { 0x90.toByte() })
        }
        return u18.absolutePath to u19.absolutePath
    }

    private fun sampleDef(
        id: VmId,
        name: String,
        floppy: FloppySpec = FloppySpec(enabled = false),
        u18: String = File("roms/u18.bin").absolutePath,
        u19: String = File("roms/u19.bin").absolutePath,
    ) = VmDefinition(
        id = id,
        name = name,
        u18RomPath = u18,
        u19RomPath = u19,
        motherboard = MotherboardSpec(baseMemoryKb = 256),
        graphics = GraphicsKind.CGA,
        enableCom1 = false,
        floppy = floppy,
        hardDisk = HardDiskSpec(enabled = false),
    )
}
