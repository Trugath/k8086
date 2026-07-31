package com.trugath.k8086.host

import com.trugath.k8086.protocol.FloppySpec
import com.trugath.k8086.protocol.GraphicsKind
import com.trugath.k8086.protocol.MotherboardSpec
import com.trugath.k8086.protocol.VmDefinition
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocalHostTest {
    @TempDir
    lateinit var temp: File

    private var host: LocalHost? = null

    @AfterEach
    fun tearDown() {
        host?.close()
        host = null
    }

    @Test
    fun persistCreateListAndDelete() {
        val store = VmStore(temp)
        host = LocalHost(store)
        val id = VmId(UUID.randomUUID().toString())
        val (u18Src, u19Src) = writeDummyRoms(temp)
        val def = sampleDef(id, "persist-test", u18Src.absolutePath, u19Src.absolutePath)
        host!!.createVm(def)
        assertEquals(1, host!!.listVms().size)
        val loaded = host!!.getDefinition(id)
        assertNotNull(loaded)
        assertTrue(File(temp, "vms/${id.value}/vm.properties").isFile)
        assertTrue(File(temp, "vms/${id.value}/roms/u18.bin").isFile)
        assertTrue(File(temp, "vms/${id.value}/roms/u19.bin").isFile)
        assertEquals(File(temp, "vms/${id.value}/roms/u18.bin").absolutePath, loaded!!.u18RomPath)
        assertEquals(File(temp, "vms/${id.value}/roms/u19.bin").absolutePath, loaded.u19RomPath)

        host!!.deleteVm(id)
        assertEquals(0, host!!.listVms().size)
        assertTrue(!File(temp, "vms/${id.value}/vm.properties").exists())
    }

    @Test
    fun updateRomsResnapshotsWhenStopped() {
        val store = VmStore(temp)
        host = LocalHost(store)
        val id = VmId(UUID.randomUUID().toString())
        val (u18a, u19a) = writeDummyRoms(temp, prefix = "a")
        host!!.createVm(sampleDef(id, "edit-roms", u18a.absolutePath, u19a.absolutePath))
        val snap18 = File(temp, "vms/${id.value}/roms/u18.bin")
        assertEquals(0x90.toByte(), snap18.readBytes()[0])

        val (u18b, u19b) = writeDummyRoms(temp, prefix = "b", fill = 0xCD.toByte())
        host!!.updateVm(host!!.getDefinition(id)!!.copy(u18RomPath = u18b.absolutePath, u19RomPath = u19b.absolutePath))
        assertEquals(0xCD.toByte(), snap18.readBytes()[0])
        assertEquals(snap18.absolutePath, host!!.getDefinition(id)!!.u18RomPath)
    }

    @Test
    fun startStopProducesMetricsWhenRomsPresent() {
        val u18 = File("roms/u18.bin")
        val u19 = File("roms/u19.bin")
        assumeTrue(u18.isFile && u19.isFile, "System ROMs required")

        val store = VmStore(temp)
        host = LocalHost(store)
        val id = VmId(UUID.randomUUID().toString())
        host!!.createVm(sampleDef(id, "run-test", u18.absolutePath, u19.absolutePath))

        host!!.startVm(id)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var running = false
        while (System.nanoTime() < deadline) {
            val state = host!!.listVms().first().state
            if (state == VmState.Running) {
                running = true
                break
            }
            if (state == VmState.Error) {
                val err = host!!.listVms().first().errorMessage
                throw AssertionError("VM entered Error: $err")
            }
            Thread.sleep(50)
        }
        assertTrue(running, "VM did not reach Running")

        Thread.sleep(200)
        val metrics = host!!.metrics(id)
        assertNotNull(metrics)
        assertTrue(metrics!!.instructionCount > 0, "expected instructions executed")

        host!!.stopVm(id)
        val stoppedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < stoppedDeadline) {
            if (host!!.listVms().first().state == VmState.Stopped) break
            Thread.sleep(50)
        }
        assertEquals(VmState.Stopped, host!!.listVms().first().state)
    }

    @Test
    fun pauseStepAndDebugStateWhenRomsPresent() {
        val u18 = File("roms/u18.bin")
        val u19 = File("roms/u19.bin")
        assumeTrue(u18.isFile && u19.isFile, "System ROMs required")

        val store = VmStore(temp)
        host = LocalHost(store)
        val id = VmId(UUID.randomUUID().toString())
        host!!.createVm(sampleDef(id, "debug-test", u18.absolutePath, u19.absolutePath))
        host!!.startVm(id)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val state = host!!.listVms().first().state
            if (state == VmState.Running || state == VmState.Paused) break
            if (state == VmState.Error) {
                throw AssertionError("VM entered Error: ${host!!.listVms().first().errorMessage}")
            }
            Thread.sleep(50)
        }

        host!!.pauseVm(id)
        Thread.sleep(50)
        assertTrue(host!!.isPaused(id))

        val before = host!!.getCpuDebugState(id)
        assertNotNull(before)
        assertTrue(before!!.nextLength >= 1)
        assertEquals(before.nextLength, before.nextBytes.size)

        val ipBefore = before.ip
        assertTrue(host!!.stepVm(id))
        val after = host!!.getCpuDebugState(id)
        assertNotNull(after)
        assertTrue(after!!.instructionCount >= before.instructionCount)
        assertTrue(
            after.ip != ipBefore || after.instructionCount > before.instructionCount,
            "step should execute an instruction",
        )

        val dump = host!!.readGuestMemory(id, after.linearCsIp, 16)
        assertNotNull(dump)
        assertEquals(16, dump!!.bytes.size)

        host!!.addBreakpoint(id, after.linearCsIp)
        assertTrue(host!!.listBreakpoints(id).contains(after.linearCsIp and 0xFFFFFF))
        host!!.removeBreakpoint(id, after.linearCsIp)
        assertTrue(host!!.listBreakpoints(id).isEmpty())

        host!!.stopVm(id)
    }

    private fun writeDummyRoms(
        dir: File,
        prefix: String = "src",
        fill: Byte = 0x90.toByte(),
    ): Pair<File, File> {
        val u18 = File(dir, "$prefix-u18.bin").also { it.writeBytes(ByteArray(32_768) { fill }) }
        val u19 = File(dir, "$prefix-u19.bin").also { it.writeBytes(ByteArray(8_192) { fill }) }
        return u18 to u19
    }

    private fun sampleDef(
        id: VmId,
        name: String,
        u18: String,
        u19: String,
    ) = VmDefinition(
        id = id,
        name = name,
        u18RomPath = u18,
        u19RomPath = u19,
        motherboard = MotherboardSpec(baseMemoryKb = 256),
        graphics = GraphicsKind.CGA,
        enableCom1 = false,
        floppy = FloppySpec(enabled = false),
    )
}
