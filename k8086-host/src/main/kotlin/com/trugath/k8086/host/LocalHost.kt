package com.trugath.k8086.host

import com.trugath.k8086.Machine
import com.trugath.k8086.MachineOptions
import com.trugath.k8086.isa.CardSpec
import com.trugath.k8086.net.NetworkRegistry
import com.trugath.k8086.net.NetworkStore
import com.trugath.k8086.protocol.ConsoleFrame
import com.trugath.k8086.protocol.CpuDebugState
import com.trugath.k8086.protocol.HostApi
import com.trugath.k8086.protocol.MemoryDump
import com.trugath.k8086.protocol.NetworkApi
import com.trugath.k8086.protocol.PrintJob
import com.trugath.k8086.protocol.VmDefinition
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmMetrics
import com.trugath.k8086.protocol.VmState
import com.trugath.k8086.protocol.VmSummary
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process multi-VM host: persists definitions, runs each VM on its own worker thread.
 */
class LocalHost(
    private val store: VmStore = VmStore(),
    private val networks: NetworkRegistry = NetworkRegistry(NetworkStore(store.root())),
) : HostApi {
    private val definitions = ConcurrentHashMap<VmId, VmDefinition>()
    private val runtimes = ConcurrentHashMap<VmId, VmRuntime>()
    private val claimedPaths = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "k8086-vm").apply { isDaemon = true }
    }

    init {
        for (id in store.listIds()) {
            store.load(id)?.let { definitions[id] = it }
        }
    }

    override fun listVms(): List<VmSummary> =
        definitions.values.map { summaryOf(it) }.sortedBy { it.name.lowercase() }

    override fun getDefinition(id: VmId): VmDefinition? = definitions[id]

    override fun createVm(definition: VmDefinition): VmSummary {
        require(!definitions.containsKey(definition.id)) { "VM already exists: ${definition.id}" }
        val snapped = withRomSnapshots(definition)
        definitions[snapped.id] = snapped
        store.save(snapped)
        return summaryOf(snapped)
    }

    override fun updateVm(definition: VmDefinition): VmSummary {
        val rt = runtimes[definition.id]
        require(rt == null || rt.state.get() == VmState.Stopped || rt.state.get() == VmState.Error) {
            "Stop the VM before updating its definition"
        }
        require(definitions.containsKey(definition.id)) { "Unknown VM: ${definition.id}" }
        val snapped = withRomSnapshots(definition)
        definitions[snapped.id] = snapped
        store.save(snapped)
        return summaryOf(snapped)
    }

    /**
     * Materialize U18/U19 into `vms/<id>/roms/` when the definition still points at
     * external source paths (or the snapshots are missing). Snapshot paths are what
     * get persisted and used at boot.
     */
    private fun withRomSnapshots(definition: VmDefinition): VmDefinition {
        val (canon18, canon19) = VmRomSnapshots.canonicalFiles(store, definition.id)
        val canonFd = VmRomSnapshots.fdromFile(store, definition.id)
        val src18 = File(definition.u18RomPath)
        val src19 = File(definition.u19RomPath)
        val srcFd = definition.hardDisk.fixedDiskRomPath?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        val refresh = !VmRomSnapshots.sameFile(src18, canon18) ||
            !VmRomSnapshots.sameFile(src19, canon19) ||
            !canon18.isFile ||
            !canon19.isFile ||
            (srcFd != null && !VmRomSnapshots.sameFile(srcFd, canonFd))
        val (u18, u19) = if (refresh) {
            VmRomSnapshots.materialize(store, definition.id, src18, src19, sourceFdrom = srcFd)
        } else {
            // Existing VMs may lack fdrom.bin (older snapshots); pull it in if available.
            VmRomSnapshots.ensureFdrom(
                store,
                definition.id,
                hintBeside = src18,
                explicitSource = srcFd,
            )
            canon18.absolutePath to canon19.absolutePath
        }
        val fdSnap = VmRomSnapshots.fdromFile(store, definition.id)
        val hd = if (fdSnap.isFile) {
            definition.hardDisk.copy(fixedDiskRomPath = fdSnap.absolutePath)
        } else {
            definition.hardDisk.copy(fixedDiskRomPath = null)
        }
        return definition.copy(u18RomPath = u18, u19RomPath = u19, hardDisk = hd)
    }

    override fun deleteVm(id: VmId) {
        stopVm(id)
        definitions.remove(id)
        store.delete(id)
    }

    override fun startVm(id: VmId) {
        val def = definitions[id] ?: error("Unknown VM: $id")
        val existing = runtimes[id]
        if (existing != null && existing.state.get() == VmState.Running) return

        val paths = diskPathsOf(def)
        for (p in paths) {
            if (claimedPaths.contains(p)) {
                error("Disk image already in use by another VM: $p")
            }
        }

        val rt = VmRuntime(def)
        rt.state.set(VmState.Starting)
        rt.heldPaths.addAll(paths)
        claimedPaths.addAll(paths)
        runtimes[id] = rt

        rt.future = executor.submit {
            try {
                val setup = SetupMapper.toMachineSetup(def)
                val machine = Machine(
                    def.u18RomPath,
                    def.u19RomPath,
                    MachineOptions.forHost(setup),
                )
                machine.nicAttach = { networkId, mac -> networks.attachNic(networkId, mac) }
                rt.machine = machine
                val cardSpecs = def.cards.filter { it.enabled }.map { CardSpec(it.jarPath, it.config) }
                if (cardSpecs.isNotEmpty()) machine.loadCards(cardSpecs)
                machine.prepareBoot(setup)
                rt.state.set(VmState.Running)
                machine.run()
                rt.state.set(VmState.Stopped)
            } catch (t: Throwable) {
                rt.state.set(VmState.Error)
                rt.errorMessage = t.message ?: t.toString()
            } finally {
                // Release definition paths and any hot-mounted floppies from changeFloppy.
                releasePaths(rt.heldPaths)
                rt.heldPaths.clear()
                rt.machine = null
                if (rt.state.get() == VmState.Stopping) rt.state.set(VmState.Stopped)
            }
        }
    }

    override fun stopVm(id: VmId) {
        val rt = runtimes[id] ?: return
        val machine = rt.machine
        if (machine != null) {
            rt.state.set(VmState.Stopping)
            machine.clearBreakpoints()
            machine.requestStop()
            rt.future?.get(30, TimeUnit.SECONDS)
        } else {
            rt.future?.cancel(true)
            rt.state.set(VmState.Stopped)
        }
    }

    override fun metrics(id: VmId): VmMetrics? {
        val def = definitions[id] ?: return null
        val rt = runtimes[id]
        val machine = rt?.machine
        val floppyCount = if (def.floppy.enabled) {
            maxOf(def.floppy.driveImages.size, machine?.floppyDriveCount() ?: 0).coerceAtLeast(1)
        } else {
            0
        }
        val paths = (0 until floppyCount).map { drive ->
            if (machine != null) machine.floppyImagePath(drive)
            else def.floppy.driveImages.getOrNull(drive)
        }
        return VmMetrics(
            id = id,
            state = effectiveState(rt),
            instructionCount = machine?.instructionCount() ?: 0L,
            uptimeMs = machine?.uptimeMs() ?: 0L,
            floppyPaths = paths,
        )
    }

    override fun pollConsoleFrame(id: VmId): ConsoleFrame? {
        val snap = runtimes[id]?.machine?.copyFramebuffer() ?: return null
        return ConsoleFrame(snap.width, snap.height, snap.argb)
    }

    override fun pollPrintJobs(id: VmId): List<PrintJob> {
        val machine = runtimes[id]?.machine ?: return emptyList()
        return machine.drainCompletedPrintJobs().map { job ->
            PrintJob(
                vmId = id,
                text = String(job.bytes, CP437),
                rawBytes = job.bytes,
                capturedAtMs = job.capturedAtMs,
            )
        }
    }

    override fun sendScanCode(id: VmId, code: Int) {
        runtimes[id]?.machine?.enqueueScanCode(code)
    }

    override fun sendCtrlAltDelete(id: VmId) {
        runtimes[id]?.machine?.sendCtrlAltDelete()
    }

    override fun changeFloppy(id: VmId, drive: Int, path: String?) {
        val rt = runtimes[id] ?: error("VM not running: $id")
        val machine = rt.machine ?: error("VM not running: $id")
        val absNew = path?.let { File(it).absolutePath }
        if (absNew != null) {
            if (!claimedPaths.add(absNew) && absNew !in rt.heldPaths) {
                error("Disk image already in use: $absNew")
            }
            rt.heldPaths.add(absNew)
        }
        val old = machine.floppyImagePath(drive)?.let { File(it).absolutePath }
        machine.changeFloppy(drive, path)
        if (old != null && old != absNew) {
            claimedPaths.remove(old)
            rt.heldPaths.remove(old)
        }
    }

    override fun setConsoleFocused(id: VmId, focused: Boolean) {
        runtimes[id]?.machine?.setConsoleFocused(focused)
    }

    override fun setAudioMuted(id: VmId, muted: Boolean) {
        runtimes[id]?.machine?.setAudioMuted(muted)
    }

    override fun isAudioMuted(id: VmId): Boolean =
        runtimes[id]?.machine?.isAudioMuted() ?: false

    override fun pauseVm(id: VmId) {
        val machine = runtimes[id]?.machine ?: return
        machine.requestPause()
    }

    override fun resumeVm(id: VmId) {
        val machine = runtimes[id]?.machine ?: return
        machine.resume()
    }

    override fun isPaused(id: VmId): Boolean =
        runtimes[id]?.machine?.isPaused() == true

    override fun setTurbo(id: VmId, enabled: Boolean) {
        runtimes[id]?.machine?.setTurbo(enabled)
    }

    override fun isTurbo(id: VmId): Boolean =
        runtimes[id]?.machine?.isTurbo() == true

    override fun getCpuDebugState(id: VmId): CpuDebugState? {
        val snap = runtimes[id]?.machine?.cpuDebugState() ?: return null
        return CpuDebugState(
            ax = snap.ax, bx = snap.bx, cx = snap.cx, dx = snap.dx,
            sp = snap.sp, bp = snap.bp, si = snap.si, di = snap.di,
            es = snap.es, cs = snap.cs, ss = snap.ss, ds = snap.ds,
            ip = snap.ip, flags = snap.flags,
            linearCsIp = snap.linearCsIp,
            halted = snap.halted,
            instructionCount = snap.instructionCount,
            nextBytes = snap.nextBytes,
            nextLength = snap.nextLength,
        )
    }

    override fun readGuestMemory(id: VmId, address: Int, length: Int): MemoryDump? {
        val machine = runtimes[id]?.machine ?: return null
        return MemoryDump(address and 0xFFFFFF, machine.readGuestMemory(address, length))
    }

    override fun stepVm(id: VmId): Boolean {
        val machine = runtimes[id]?.machine ?: return false
        return machine.stepOnce()
    }

    override fun addBreakpoint(id: VmId, linearAddress: Int) {
        runtimes[id]?.machine?.addBreakpoint(linearAddress)
    }

    override fun removeBreakpoint(id: VmId, linearAddress: Int) {
        runtimes[id]?.machine?.removeBreakpoint(linearAddress)
    }

    override fun listBreakpoints(id: VmId): List<Int> =
        runtimes[id]?.machine?.listBreakpoints() ?: emptyList()

    override fun network(): NetworkApi = networks

    fun close() {
        for (id in runtimes.keys.toList()) {
            try {
                stopVm(id)
            } catch (_: Exception) {
            }
        }
        networks.close()
        executor.shutdownNow()
    }

    private fun summaryOf(def: VmDefinition): VmSummary {
        val rt = runtimes[def.id]
        return VmSummary(
            id = def.id,
            name = def.name,
            state = effectiveState(rt),
            errorMessage = rt?.errorMessage,
        )
    }

    private fun effectiveState(rt: VmRuntime?): VmState {
        if (rt == null) return VmState.Stopped
        val state = rt.state.get()
        if (state == VmState.Running && rt.machine?.isPaused() == true) return VmState.Paused
        return state
    }

    private fun diskPathsOf(def: VmDefinition): Set<String> = buildSet {
        if (def.floppy.enabled) {
            def.floppy.driveImages.forEach { add(File(it).absolutePath) }
        }
        if (def.hardDisk.enabled) {
            def.hardDisk.imagePath?.let { add(File(it).absolutePath) }
            def.hardDisk.secondImagePath?.let { add(File(it).absolutePath) }
        }
    }

    private fun releasePaths(paths: Set<String>) {
        paths.forEach { claimedPaths.remove(it) }
    }

    private class VmRuntime(val definition: VmDefinition) {
        val state = AtomicReference(VmState.Stopped)
        /** Absolute disk paths this VM currently holds in [claimedPaths] (boot + hot mounts). */
        val heldPaths = ConcurrentHashMap.newKeySet<String>()
        @Volatile var machine: Machine? = null
        @Volatile var future: Future<*>? = null
        @Volatile var errorMessage: String? = null
    }

    companion object {
        private val CP437: Charset = Charset.forName("IBM437")
    }
}
