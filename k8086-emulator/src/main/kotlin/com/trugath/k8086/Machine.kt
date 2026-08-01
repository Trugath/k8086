package com.trugath.k8086

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.api.DmaChannel
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.NicPort
import com.trugath.k8086.api.NullNicPort
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.bus.InterruptSource
import com.trugath.k8086.bus.IoBus
import com.trugath.k8086.chipset.Dma8237
import com.trugath.k8086.chipset.Keyboard
import com.trugath.k8086.chipset.PcSpeaker
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.chipset.Pit8253
import com.trugath.k8086.chipset.Ppi8255
import com.trugath.k8086.chipset.ShutdownPort
import com.trugath.k8086.chipset.Uart8250
import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.cpu.Emulator80286
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.MathCoprocessor8087
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_BP
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.REG_SI
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.XT_HARD_DISK_BYTES
import com.trugath.k8086.cpu.changeFloppyImage
import com.trugath.k8086.cpu.closeDisks
import com.trugath.k8086.cpu.diskImage
import com.trugath.k8086.cpu.loadSystemRoms
import com.trugath.k8086.cpu.setupBootDisks
import com.trugath.k8086.isa.CardSpec
import com.trugath.k8086.isa.IsaSlotLoader
import com.trugath.k8086.storage.Fdc765
import com.trugath.k8086.storage.FixedDiskBios
import com.trugath.k8086.storage.FloppyInt13
import com.trugath.k8086.storage.HdGeometry
import com.trugath.k8086.storage.HdInt13
import com.trugath.k8086.storage.Wd1003
import com.trugath.k8086.video.Cga
import com.trugath.k8086.video.FramebufferSnapshot
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Why [Machine.run] returned. */
enum class RunStopReason {
    /** Still running / never entered the loop. */
    NONE,
    /** Guest wrote "Shutdown" to port 0x8900. */
    SHUTDOWN_PORT,
    /** Host called [Machine.requestStop] for a reason other than the shutdown port. */
    HOST_STOP,
    /** CS:IP linear address became 0 or [Emulator8086.step] failed. */
    GUEST_FAULT,
    /** [Machine.run] hit its instruction ceiling. */
    MAX_INSTRUCTIONS,
    /** CGA text buffer matched [MachineOptions.cgaExpect]. */
    CGA_EXPECT,
}

/**
 * Options for optional system adapters. Core chipset (PIC/PIT/PPI/DMA/keyboard) is always present.
 */
data class MachineOptions(
    val motherboard: MotherboardConfig = MotherboardConfig(),
    val graphics: GraphicsAdapter = GraphicsAdapter.CGA,
    val showVideo: Boolean = true,
    /** Open a PC-speaker audio line. Defaults to [showVideo] && CGA when null. */
    val enableAudio: Boolean? = null,
    /** When [showVideo] is true, closing the CGA window exits the JVM (single-instance CLI). */
    val exitOnClose: Boolean = true,
    val enableCom1: Boolean = true,
    /** When set, COM1 TX bytes are appended to this file. */
    val serialLogPath: String? = null,
    val floppy: FloppyControllerConfig = FloppyControllerConfig(enabled = true),
    val hardDisk: HardDiskControllerConfig = HardDiskControllerConfig(enabled = false),
    /**
     * Pace [Machine.run] to ~4.77 MHz wall time. Independent of audio; needed for
     * headless workstation VMs where the speaker line is closed.
     */
    val realtime: Boolean = true,
    /** When set, [Machine.run] stops with [RunStopReason.CGA_EXPECT] once CGA text contains this. */
    val cgaExpect: String? = null,
    /** Instruction stride between CGA expect polls (BootIntegrationTest uses 200_000). */
    val cgaExpectEvery: Long = 200_000L,
    /** Start with turbo (free-run) enabled — skips realtime pacing. */
    val turbo: Boolean = false,
) {
    fun resolvedEnableAudio(): Boolean =
        enableAudio ?: (showVideo && graphics == GraphicsAdapter.CGA)

    companion object {
        fun fromSetup(setup: MachineSetup) = MachineOptions(
            motherboard = setup.motherboard,
            graphics = setup.graphics,
            showVideo = setup.showVideo,
            enableCom1 = setup.enableCom1,
            floppy = setup.floppy,
            hardDisk = setup.hardDisk,
        )

        /** Headless options for a managed multi-VM host worker. */
        fun forHost(setup: MachineSetup) = fromSetup(setup).copy(
            showVideo = false,
            enableAudio = true,
            exitOnClose = false,
            realtime = true,
        )
    }
}

// The IBM 5155 Portable PC as a whole: an 8088 CPU core plus the XT chipset wired
// onto its I/O bus and interrupt line. Expansion cards load via [loadCards].
class Machine(
    private val u18RomPath: String,
    private val u19RomPath: String,
    private val options: MachineOptions = MachineOptions(),
) {
    constructor(
        u18RomPath: String,
        u19RomPath: String,
        showVideo: Boolean = true,
        enableCom1: Boolean = true,
    ) : this(
        u18RomPath,
        u19RomPath,
        MachineOptions(
            showVideo = showVideo,
            enableCom1 = enableCom1,
            hardDisk = HardDiskControllerConfig(enabled = false),
        ),
    )

    internal val cpu: Emulator8086 = when (options.motherboard.cpu) {
        CpuModel.I8088 -> Emulator8088()
        CpuModel.I8086 -> Emulator8086()
        CpuModel.I80286 -> Emulator80286()
    }
    val ioBus = IoBus()

    val pic = Pic8259()
    val pit = Pit8253(pic)
    val ppi = Ppi8255(pit, pic)
    internal val dma = Dma8237(cpu)

    internal val cga: Cga? = if (options.graphics == GraphicsAdapter.CGA) {
        Cga(cpu, showWindow = options.showVideo, exitOnClose = options.exitOnClose)
    } else {
        null
    }
    internal val fdc: Fdc765? = if (options.floppy.enabled) Fdc765(cpu, pic, dma) else null
    /** Host INT 13h floppy path (optional; guest BIOS uses FDC when shim is off). */
    internal val floppyInt13: FloppyInt13? =
        if (options.floppy.enabled && options.floppy.useInt13Shim) FloppyInt13(cpu) else null
    val keyboard = Keyboard(pic, ppi)
    val speaker = PcSpeaker(pit, ppi, enableAudio = options.resolvedEnableAudio())
    val uart: Uart8250? = if (options.enableCom1) Uart8250(pic) else null
    val shutdownPort = ShutdownPort { requestShutdownFromPort() }

    private var serialLogStream: OutputStream? = null

    @Volatile
    private var stopReason: RunStopReason = RunStopReason.NONE

    /**
     * POST error resume: auto-press F1 once when CGA shows the classic prompt
     * (`RESUME =`, matching IBM `ERROR. (RESUME = "F1" KEY)` and rmDOS
     * `ERROR (RESUME = F1 KEY)`). Not tied to F000:E842 so alternate BIOS
     * wait loops still work under headless / CI.
     */
    private var postResumeF1Pressed = false
    private var postResumePollCounter = 0L

    private val hdGeometryOverrides = arrayOf(
        if (options.hardDisk.cylinders != null || options.hardDisk.heads != null || options.hardDisk.sectorsPerTrack != null) {
            HdGeometry(
                cylinders = options.hardDisk.cylinders ?: 306,
                heads = options.hardDisk.heads ?: 4,
                sectorsPerTrack = options.hardDisk.sectorsPerTrack ?: 17,
            )
        } else null,
        null,
    )

    internal val wd1003: Wd1003? = if (options.hardDisk.enabled) {
        Wd1003(
            cpu = cpu,
            pic = pic,
            dma = dma,
            ioBase = options.hardDisk.ioBase,
            irq = options.hardDisk.irq,
            dmaChannel = options.hardDisk.dmaChannel,
            geometryOverrides = hdGeometryOverrides,
        )
    } else null

    /** Legacy direct-image INT 13h shim (only when [HardDiskControllerConfig.useInt13Shim]). */
    internal val hdInt13: HdInt13? = if (options.hardDisk.enabled && options.hardDisk.useInt13Shim) {
        HdInt13(
            cpu,
            HdInt13.GeometryOverrides(
                options.hardDisk.cylinders,
                options.hardDisk.heads,
                options.hardDisk.sectorsPerTrack,
            ),
        )
    } else null

    internal val fixedDiskBios: FixedDiskBios? =
        if (options.hardDisk.enabled &&
            !options.hardDisk.useInt13Shim &&
            options.hardDisk.useHostFixedDiskBios &&
            wd1003 != null
        ) {
            FixedDiskBios(cpu, wd1003)
        } else null

    private var interruptSource: InterruptSource? = null
    private val tickableList = mutableListOf<(Int) -> Unit>()
    /** Snapshot for the hot loop — avoids ArrayList iterators every instruction. */
    private var tickables: Array<(Int) -> Unit> = emptyArray()
    /** Coalesce PIT advances (~16 CPU clocks) to cut per-insn counter work. */
    private var pitTickDebt = 0
    private val dmaOwners = HashMap<Int, String>()
    private val slotLoader = IsaSlotLoader()

    /**
     * Virtual-network attach used by NIC cards via [IsaHost.attachNic].
     * Defaults to [NullNicPort]; host / CLI set this before [loadCards].
     */
    @Volatile
    var nicAttach: (networkId: String, mac: ByteArray) -> NicPort = { _, _ -> NullNicPort }

    fun attachNic(networkId: String, mac: ByteArray): NicPort = nicAttach(networkId, mac)

    /**
     * 8088 samples INTR at the end of an instruction for the *next* boundary.
     * An OUT that unmasks a pending IRQ must not be taken before the following
     * instruction (POST's IMR read-back test); latch for one instruction.
     */
    private var irqLineLatched = false
    /**
     * True while CS is (or recently was) in the BIOS segment. Starts true because
     * reset/POST begin at F000; cleared when CS drops below F000, then re-armed
     * by an infrequent poll under DOS (CAD entry into ROM).
     */
    private var watchingWarmBootCs = true
    private var warmBootPollCounter = 0

    /** Host-visible floppy image paths (A:–D:), for toolbar labels / swap. */
    private val floppyPaths = arrayOfNulls<String>(4)

    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var stopActive = false
    private val pauseRequested = AtomicBoolean(false)
    @Volatile
    private var pauseActive = false
    @Volatile
    private var turboActive = false
    private val shutDown = AtomicBoolean(false)
    /**
     * Instruction counter for the run thread only. Plain [Long] (not [AtomicLong]) —
     * the UI can tolerate a torn/stale read of [instructionCount]; atomic CAS every
     * insn showed up heavily in JFR.
     */
    private var instructionsExecuted = 0L
    private val startedAtMs = AtomicLong(0)
    private val realtimePacer = if (options.realtime) RealtimePacer() else null
    /** Reused by [advanceTime] — allocating a lambda every instruction showed up in JFR. */
    private val paceKeepGoing: () -> Boolean = {
        !stopActive && !pauseActive
    }

    /** User mute preference (toolbar toggle). */
    @Volatile
    private var userAudioMuted = false

    /**
     * Console focus: host VMs start unfocused (no window yet); single-instance
     * with a visible CGA window starts focused.
     */
    @Volatile
    private var consoleFocused = options.showVideo

    /** Absolute paths of disk images currently claimed by this machine (for host exclusivity). */
    private val claimedDiskPaths = mutableSetOf<String>()

    /** Execution breakpoints keyed by linear CS:IP (real-mode physical address). */
    private val breakpoints = ConcurrentHashMap.newKeySet<Int>()
    /**
     * Cached emptiness for the run loop — [ConcurrentHashMap.isEmpty] calls sumCount()
     * and showed up as a top hotspot with no breakpoints armed.
     */
    @Volatile
    private var breakpointsArmed = false

    init {
        cpu.attachIoBus(ioBus)

        ioBus.map(pic, listOf(0x20, 0x21), owner = "motherboard-pic")
        setInterruptSource(pic)

        ioBus.map(pit, listOf(0x40, 0x41, 0x42, 0x43), owner = "motherboard-pit")

        ioBus.map(ppi, listOf(0x60, 0x61, 0x62, 0x63), owner = "motherboard-ppi")

        ioBus.map(dma, (0x00..0x0F).toList(), owner = "motherboard-dma")
        ioBus.map(dma, (0x80..0x8F).toList(), owner = "motherboard-dma")

        pit.refreshDma = dma
        dmaOwners[0] = "motherboard-refresh"

        cga?.let { video ->
            ioBus.map(video, (0x3D0..0x3DF).toList(), owner = "cga-adapter")
            video.onKeyScanCode = { code -> keyboard.enqueueScanCode(code) }
            cpu.hostServices.onDosTerminate = { video.restoreTextModeIfGraphics() }
            video.hostControls = Cga.HostControls(
                floppyDriveCount = floppyToolbarDriveCount(),
                onCtrlAltDelete = { keyboard.sendCtrlAltDelete() },
                onChangeFloppy = { drive, path -> changeFloppy(drive, path) },
                floppyPath = { drive -> floppyPaths.getOrNull(drive) },
                onToggleAudio = {
                    setAudioMuted(!isAudioMuted())
                },
                isAudioMuted = { isAudioMuted() },
                onTogglePause = {
                    if (isPaused()) resume() else requestPause()
                },
                isPaused = { isPaused() },
                onToggleTurbo = {
                    setTurbo(!isTurbo())
                },
                isTurbo = { isTurbo() },
            )
        }

        applyAudioMute()

        fdc?.let { controller ->
            ioBus.map(controller, (0x3F0..0x3F7).toList(), owner = "floppy-controller")
            dmaOwners[2] = "floppy-controller"
        }

        uart?.let { u ->
            ioBus.map(u, (0x3F8..0x3FF).toList(), owner = "com1-uart")
            options.serialLogPath?.let { path ->
                val stream = BufferedOutputStream(FileOutputStream(path, false))
                serialLogStream = stream
                u.onTransmit = { byte ->
                    synchronized(stream) {
                        stream.write(byte)
                        stream.flush()
                    }
                }
            }
        }

        ioBus.map(shutdownPort, listOf(ShutdownPort.PORT), owner = "shutdown-port")

        wd1003?.let { hdc ->
            val base = options.hardDisk.ioBase
            ioBus.map(hdc, (base until base + 8).toList(), owner = "hd-controller")
            dmaOwners[options.hardDisk.dmaChannel] = "hd-controller"
        }

        floppyInt13?.let { shim ->
            cpu.hostServices.onInt13Floppy = { shim.handle() }
        }

        when {
            hdInt13 != null -> cpu.hostServices.onInt13HardDisk = { hdInt13.handle() }
            fixedDiskBios != null -> cpu.hostServices.onInt13HardDisk = { fixedDiskBios.handle() }
        }

        applyMotherboardOptions()
        ppi.configureFloppyDrives(ppiFloppyCount(options.floppy.driveImages.size))
        if (options.floppy.enabled) {
            options.floppy.driveImages.forEachIndexed { i, path ->
                if (i in floppyPaths.indices) floppyPaths[i] = path
            }
        }
    }

    private fun applyMotherboardOptions() {
        val mb = options.motherboard
        cpu.conventionalMemoryEnd = mb.conventionalMemoryEnd()
        cpu.mathCoprocessor = if (mb.mathCoprocessor) {
            MathCoprocessor8087().also { fpu ->
                // IBM PC/XT routes the 8087 INT output to the 8088 NMI input.
                fpu.onUnmaskedException = { cpu.requestNmi() }
            }
        } else {
            null
        }
        ppi.configureMotherboard(mb)
    }

    private fun ppiFloppyCount(attachedImages: Int): Int =
        if (!options.floppy.enabled) 0 else attachedImages.coerceAtLeast(1)

    /** Buttons for A:… when FDC is present; at least one drive if controller enabled. */
    private fun floppyToolbarDriveCount(): Int =
        if (fdc == null) 0 else ppiFloppyCount(options.floppy.driveImages.size).coerceAtMost(4)

    /**
     * Swap or eject floppy media for BIOS drive [drive] (0=A: … 3=D:) and signal
     * the FDC disk-change line so guests can detect the media swap.
     */
    @Synchronized
    fun changeFloppy(drive: Int, path: String?) {
        require(drive in 0..3) { "Floppy drive must be 0..3 (got $drive)" }
        val controller = fdc ?: error("No floppy controller")
        val old = floppyPaths[drive]
        if (old != null) claimedDiskPaths.remove(java.io.File(old).absolutePath)
        cpu.changeFloppyImage(drive, path)
        floppyPaths[drive] = path
        if (path != null) claimedDiskPaths.add(java.io.File(path).absolutePath)
        controller.signalDiskChange()
    }

    fun floppyImagePath(drive: Int): String? = floppyPaths.getOrNull(drive)

    fun floppyDriveCount(): Int = floppyToolbarDriveCount()

    fun claimedDiskPaths(): Set<String> = synchronized(this) { claimedDiskPaths.toSet() }

    fun instructionCount(): Long = instructionsExecuted

    fun uptimeMs(): Long {
        val start = startedAtMs.get()
        if (start == 0L) return 0L
        return System.currentTimeMillis() - start
    }

    fun copyFramebuffer(): FramebufferSnapshot? = cga?.copyFramebuffer()

    fun enqueueScanCode(code: Int) = keyboard.enqueueScanCode(code)

    fun sendCtrlAltDelete() = keyboard.sendCtrlAltDelete()

    /** User-facing mute preference (does not include focus-based auto-mute). */
    fun isAudioMuted(): Boolean = userAudioMuted

    /**
     * True when the user should not hear audio (mute, unfocused, pause, or turbo).
     * Turbo suspends the audio device entirely; the others feed silence.
     */
    fun isAudioOutputMuted(): Boolean =
        feedAudioSilence() || turboActive

    /** Mute/focus/pause: keep [SourceDataLine] fed with silence (avoids underrun clicks). */
    fun feedAudioSilence(): Boolean =
        userAudioMuted || !consoleFocused || pauseActive

    /** Turbo: skip audio device writes so they cannot pace the CPU. */
    fun isAudioOutputSuspended(): Boolean = turboActive

    fun setAudioMuted(muted: Boolean) {
        userAudioMuted = muted
        applyAudioMute()
    }

    fun setConsoleFocused(focused: Boolean) {
        consoleFocused = focused
        applyAudioMute()
    }

    fun requestPause() {
        pauseRequested.set(true)
        pauseActive = true
        applyAudioMute()
    }

    fun resume() {
        if (pauseRequested.compareAndSet(true, false)) {
            pauseActive = false
            realtimePacer?.reset()
            applyAudioMute()
        }
    }

    fun isPaused(): Boolean = pauseActive

    fun setTurbo(enabled: Boolean) {
        turboActive = enabled
        // Drop audio pacing and skip per-vsync presents so the CPU can free-run.
        cga?.presentEnabled = !enabled
        if (!enabled) realtimePacer?.reset()
        applyAudioMute()
    }

    fun isTurbo(): Boolean = turboActive

    fun addBreakpoint(linearAddress: Int) {
        breakpoints.add(linearAddress and 0xFFFFFF)
        breakpointsArmed = true
    }

    fun removeBreakpoint(linearAddress: Int) {
        breakpoints.remove(linearAddress and 0xFFFFFF)
        breakpointsArmed = breakpoints.isNotEmpty()
    }

    fun listBreakpoints(): List<Int> = breakpoints.sorted()

    fun clearBreakpoints() {
        breakpoints.clear()
        breakpointsArmed = false
    }

    /**
     * Snapshot of architectural registers / next-instruction bytes for the debug UI.
     * Safe to call from another thread while the machine is paused (or briefly while running).
     */
    fun cpuDebugState(): CpuDebugSnapshot {
        val length = cpu.peekInstructionLengthAtCsIp().coerceIn(1, 15)
        val linear = cpu.currentCsIpLinear()
        val nextBytes = (0 until length).map { cpu.readPhysByte(linear + it) }
        return CpuDebugSnapshot(
            ax = cpu.getReg16(REG_AX),
            bx = cpu.getReg16(REG_BX),
            cx = cpu.getReg16(REG_CX),
            dx = cpu.getReg16(REG_DX),
            sp = cpu.getReg16(REG_SP),
            bp = cpu.getReg16(REG_BP),
            si = cpu.getReg16(REG_SI),
            di = cpu.getReg16(REG_DI),
            es = cpu.getReg16(REG_ES),
            cs = cpu.getReg16(REG_CS),
            ss = cpu.getReg16(REG_SS),
            ds = cpu.getReg16(REG_DS),
            ip = cpu.getIp(),
            flags = cpu.getFlags(),
            linearCsIp = linear,
            halted = cpu.isHalted(),
            instructionCount = instructionsExecuted,
            nextBytes = nextBytes,
            nextLength = length,
        )
    }

    fun readGuestMemory(address: Int, length: Int): List<Int> {
        val len = length.coerceIn(0, MAX_DEBUG_MEMORY_READ)
        val base = address and 0xFFFFFF
        return (0 until len).map { cpu.readPhysByte(base + it) }
    }

    /**
     * Execute one run-loop iteration while remaining paused.
     * Returns false if the machine is not paused, is stopping, or the iteration failed.
     */
    fun stepOnce(): Boolean {
        if (!pauseRequested.get() || stopRequested.get() || shutDown.get()) return false
        return runOneIteration(fromStep = true)
    }

    private fun applyAudioMute() {
        speaker.muted = feedAudioSilence()
        speaker.suspended = isAudioOutputSuspended()
    }

    private fun rememberFloppyPaths(images: List<String>) {
        for (i in floppyPaths.indices) floppyPaths[i] = null
        images.forEachIndexed { i, path ->
            if (i in floppyPaths.indices) floppyPaths[i] = path
        }
        cga?.run {
            hostControls = hostControls?.copy(
                floppyDriveCount = if (fdc == null) 0 else ppiFloppyCount(images.size).coerceAtMost(4),
                floppyPath = { drive -> floppyPaths.getOrNull(drive) },
            )
        }
    }

    fun setInterruptSource(src: InterruptSource) {
        interruptSource = src
        cpu.attachInterruptSource(src)
    }

    fun addTickable(t: (cycles: Int) -> Unit) {
        tickableList.add(t)
        tickables = tickableList.toTypedArray()
    }

    fun tickDevices(cycles: Int) {
        // Coalesce PIT: IRQ0 / refresh tolerate ~16-cycle quanta; avoids advance() every insn.
        pitTickDebt += cycles
        if (pitTickDebt >= PIT_TICK_QUANTUM) {
            pit.tickCpuCycles(pitTickDebt)
            pitTickDebt = 0
        }
        val video = cga
        if (video != null) video.tickCpuCycles(cycles)
        speaker.tickCpuCycles(cycles)
        val extras = tickables
        if (extras.isEmpty()) return
        for (i in extras.indices) extras[i](cycles)
    }

    private fun advanceTime(cycles: Int) {
        tickDevices(cycles)
        if (turboActive) return
        realtimePacer?.addCycles(cycles, paceKeepGoing)
    }

    /** Wait while paused; returns false if stop was requested. */
    private fun waitIfPaused(): Boolean {
        while (pauseRequested.get() && !stopRequested.get()) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !stopRequested.get()
    }

    fun claimDmaChannel(channel: Int, owner: String): DmaChannel {
        require(channel in 0..3) { "DMA channel out of range: $channel" }
        val existing = dmaOwners[channel]
        if (existing != null) {
            throw IllegalStateException("DMA channel $channel already claimed by '$existing'")
        }
        dmaOwners[channel] = owner
        return object : DmaChannel {
            override val channel: Int = channel
            override fun readByte(): Int = dma.dmaReadByte(channel)
            override fun writeByte(value: Int) = dma.dmaWriteByte(channel, value)
            override fun isMasked(): Boolean = dma.isMasked(channel)
        }
    }

    fun loadCards(specs: List<CardSpec>): List<IsaCard> =
        slotLoader.loadAll(this, specs)

    fun loadedCards(): List<IsaCard> = slotLoader.cards()

    fun boot(
        floppyImage: String? = null,
        hardDiskImage: String? = null,
        hardDiskBytes: Long = XT_HARD_DISK_BYTES,
        maxInstructions: Long = Long.MAX_VALUE,
    ) = boot(
        floppyImages = listOfNotNull(floppyImage),
        hardDiskImage = hardDiskImage,
        hardDiskBytes = hardDiskBytes,
        maxInstructions = maxInstructions,
    )

    fun boot(
        floppyImages: List<String>,
        hardDiskImage: String? = null,
        hardDiskBytes: Long = XT_HARD_DISK_BYTES,
        maxInstructions: Long = Long.MAX_VALUE,
        secondHardDiskImage: String? = null,
    ) {
        prepareBoot(floppyImages, hardDiskImage, hardDiskBytes, secondHardDiskImage)
        run(maxInstructions)
    }

    fun boot(setup: MachineSetup, maxInstructions: Long = Long.MAX_VALUE) {
        prepareBoot(setup)
        run(maxInstructions)
    }

    /** Load ROMs/disks/cards without entering the run loop (for managed hosts). */
    fun prepareBoot(setup: MachineSetup) {
        val specs = setup.cards.filter { it.enabled }.map {
            CardSpec(it.jarPath, it.effectiveConfig())
        }
        if (specs.isNotEmpty()) loadCards(specs)
        prepareBoot(
            floppyImages = setup.floppyImages,
            hardDiskImage = setup.hardDiskPathForBoot(),
            hardDiskBytes = setup.hardDiskBytes,
            secondHardDiskImage = setup.hardDisk.secondImagePath?.takeIf { setup.hardDisk.enabled },
        )
    }

    fun prepareBoot(
        floppyImages: List<String>,
        hardDiskImage: String? = null,
        hardDiskBytes: Long = XT_HARD_DISK_BYTES,
        secondHardDiskImage: String? = null,
    ) {
        stopRequested.set(false)
        stopActive = false
        pauseRequested.set(false)
        pauseActive = false
        turboActive = false
        stopReason = RunStopReason.NONE
        postResumeF1Pressed = false
        postResumePollCounter = 0L
        floppyMediaHintsApplied = false
        watchingWarmBootCs = true
        warmBootPollCounter = 0
        pitTickDebt = 0
        cga?.presentEnabled = true
        shutDown.set(false)
        instructionsExecuted = 0
        breakpoints.clear()
        breakpointsArmed = false
        val floppies = if (options.floppy.enabled) floppyImages else emptyList()
        applyMotherboardOptions()
        ppi.configureFloppyDrives(ppiFloppyCount(floppies.size))
        rememberFloppyPaths(floppies)
        cpu.loadSystemRoms(u18RomPath, u19RomPath)
        val hd = if (options.hardDisk.enabled || hardDiskImage != null) hardDiskImage else null
        val hd2 = if (options.hardDisk.enabled) {
            secondHardDiskImage ?: options.hardDisk.secondImagePath
        } else null
        if (floppies.isNotEmpty() || hd != null || hd2 != null) {
            cpu.setupBootDisks(floppies, hd, hardDiskBytes, hd2)
        }
        attachHdControllerImages()
        mapFixedDiskOptionRom()
        synchronized(this) {
            claimedDiskPaths.clear()
            floppies.forEach { claimedDiskPaths.add(java.io.File(it).absolutePath) }
            hd?.removePrefix("@")?.let { claimedDiskPaths.add(java.io.File(it).absolutePath) }
            hd2?.let { claimedDiskPaths.add(java.io.File(it).absolutePath) }
        }
        if (options.turbo) {
            setTurbo(true)
        } else {
            turboActive = false
        }
    }

    private fun attachHdControllerImages() {
        val hdc = wd1003 ?: return
        hdc.attachImage(0, cpu.diskImage(0x80))
        hdc.attachImage(1, cpu.diskImage(0x81))
    }

    /**
     * Map guest Fixed Disk option ROM at C800:0 when HD INT 13 is not host-owned.
     * Patches drive geometry at ROM offset 0x700 from attached Wd1003 images.
     */
    private fun mapFixedDiskOptionRom() {
        if (!options.hardDisk.enabled || options.hardDisk.useInt13Shim || options.hardDisk.useHostFixedDiskBios) {
            return
        }
        val hdc = wd1003 ?: return
        val romFile = resolveFixedDiskRomFile() ?: return
        val bytes = romFile.readBytes().copyOf()
        require(bytes.size >= 16 && (bytes[0].toInt() and 0xFF) == 0x55 && (bytes[1].toInt() and 0xFF) == 0xAA) {
            "Fixed Disk ROM invalid: ${romFile.path}"
        }
        // Geometry table: 8 bytes at the XT default signature (306/4/17).
        var geoOff = -1
        for (i in 0 until bytes.size - 3) {
            if ((bytes[i].toInt() and 0xFF) == 0x32 &&
                (bytes[i + 1].toInt() and 0xFF) == 0x01 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x04 &&
                (bytes[i + 3].toInt() and 0xFF) == 0x11
            ) {
                geoOff = i
                break
            }
        }
        if (geoOff < 0) geoOff = 0x700
        fun patchDrive(drive: Int, base: Int) {
            if (!hdc.drivePresent(drive)) return
            if (base + 3 >= bytes.size) return
            val g = hdc.geometry(drive)
            bytes[base] = (g.cylinders and 0xFF).toByte()
            bytes[base + 1] = ((g.cylinders shr 8) and 0xFF).toByte()
            bytes[base + 2] = (g.heads and 0xFF).toByte()
            bytes[base + 3] = (g.sectorsPerTrack and 0xFF).toByte()
        }
        patchDrive(0, geoOff)
        patchDrive(1, geoOff + 4)
        bytes[bytes.lastIndex] = 0
        val sum = bytes.fold(0) { a, b -> (a + (b.toInt() and 0xFF)) and 0xFF }
        bytes[bytes.lastIndex] = (-sum).toByte()
        // Writable image: option ROM saves prior INT 13 vector and transfer state in-place.
        cpu.memoryBus.map(MemoryRegion.Ram(0xC8000, bytes.size, bytes), "fdrom")
    }

    private fun resolveFixedDiskRomFile(): File? {
        val configured = options.hardDisk.fixedDiskRomPath
        if (!configured.isNullOrBlank()) {
            val f = File(configured)
            return f.takeIf { it.isFile }
        }
        val env = System.getenv("K8086_FDROM")
        if (!env.isNullOrBlank()) {
            val f = File(env)
            if (f.isFile) return f
        }
        // Beside U18: .../roms/u18.bin → .../roms/fdrom.bin
        val besideU18 = File(File(u18RomPath).parentFile, "fdrom.bin")
        if (besideU18.isFile) return besideU18
        return null
    }

    /** Cooperative stop; the run loop exits and [shutdown] runs from `finally`. */
    fun requestStop() {
        if (stopReason == RunStopReason.NONE) {
            stopReason = RunStopReason.HOST_STOP
        }
        pauseRequested.set(false)
        pauseActive = false
        stopRequested.set(true)
        stopActive = true
    }

    private fun requestShutdownFromPort() {
        stopReason = RunStopReason.SHUTDOWN_PORT
        pauseRequested.set(false)
        pauseActive = false
        stopRequested.set(true)
        stopActive = true
    }

    fun isStopRequested(): Boolean = stopRequested.get()

    fun stopReason(): RunStopReason = stopReason

    /**
     * Detach cards, close speaker/disks, dispose any owned CGA window.
     * Idempotent; normally invoked from [run]'s `finally`.
     */
    fun shutdown() {
        if (!shutDown.compareAndSet(false, true)) return
        breakpoints.clear()
        breakpointsArmed = false
        slotLoader.detachAll()
        speaker.close()
        cpu.closeDisks()
        cga?.disposeWindow()
        serialLogStream?.let { stream ->
            try {
                synchronized(stream) { stream.close() }
            } catch (_: Exception) {
            }
            serialLogStream = null
            uart?.onTransmit = null
        }
        synchronized(this) { claimedDiskPaths.clear() }
    }

    fun run(maxInstructions: Long = Long.MAX_VALUE) {
        startedAtMs.set(System.currentTimeMillis())
        realtimePacer?.reset()
        if (stopReason == RunStopReason.NONE) {
            // leave NONE until a concrete reason is set
        }
        val expect = options.cgaExpect
        val expectEvery = options.cgaExpectEvery.coerceAtLeast(1L)
        try {
            while (instructionsExecuted < maxInstructions && !stopActive) {
                if (pauseActive && !waitIfPaused()) break
                if (!runOneIteration(fromStep = false)) {
                    if (stopReason == RunStopReason.NONE) {
                        stopReason = RunStopReason.GUEST_FAULT
                    }
                    break
                }
                if (expect != null &&
                    instructionsExecuted % expectEvery == 0L &&
                    cgaTextContains(expect)
                ) {
                    stopReason = RunStopReason.CGA_EXPECT
                    stopRequested.set(true)
                    stopActive = true
                    break
                }
            }
            if (stopReason == RunStopReason.NONE &&
                instructionsExecuted >= maxInstructions &&
                maxInstructions != Long.MAX_VALUE
            ) {
                stopReason = RunStopReason.MAX_INSTRUCTIONS
            }
        } finally {
            shutdown()
        }
    }

    /** 80×25 CGA ASCII rows joined by newlines (non-printable → space). */
    fun cgaScreenText(): String = buildString {
        for (row in 0 until 25) {
            if (row > 0) append('\n')
            for (col in 0 until 80) {
                val ch = cpu.readPhysByte(0xB8000 + (row * 80 + col) * 2)
                append(if (ch in 32..126) ch.toChar() else ' ')
            }
        }
    }

    fun cgaTextContains(needle: String): Boolean = cgaScreenText().contains(needle)

    /**
     * If the POST resume prompt is on screen, inject F1 once and return true.
     * Safe to call from manual step loops (integration tests) as well as [run].
     * Polls every [POST_RESUME_POLL_EVERY] iterations to avoid scanning CGA VRAM
     * each step (especially while the guest is halted).
     */
    fun pollPostResumeF1(): Boolean {
        if (postResumeF1Pressed) return false
        postResumePollCounter++
        // Never scan CGA every HLT quantum — that stalls headless boots.
        if (postResumePollCounter % POST_RESUME_POLL_EVERY != 0L) return false
        if (!cgaTextContains(POST_RESUME_NEEDLE)) return false
        keyboard.typeKey(0x3B)
        postResumeF1Pressed = true
        return true
    }

    /**
     * One iteration of the machine run loop.
     * @param fromStep when true, execution breakpoints are ignored so Step can leave a hit.
     * @return false if the machine should stop (decode failure / CS:IP == 0).
     */
    private var floppyMediaHintsApplied = false

    /** Publish 360K/720K media type into BDA 40:8B for guest AH=08 (image-size heuristic). */
    private fun applyFloppyMediaHints() {
        if (floppyMediaHintsApplied || !options.floppy.enabled) return
        // Wait until POST has initialized the BDA (equipment word non-zero).
        if (cpu.getMem(0x410) == 0 && cpu.getMem(0x411) == 0) return
        fun typeFor(drive: Int): Int {
            val img = cpu.diskImage(drive) ?: return 0
            return when (img.length()) {
                368640L -> 1 // 360K
                737280L -> 3 // 720K
                else -> 3
            }
        }
        val t0 = typeFor(0)
        if (t0 != 0) cpu.setMem(0x48B, t0)
        floppyMediaHintsApplied = true
    }

    private fun runOneIteration(fromStep: Boolean): Boolean {
        pollPostResumeF1()
        applyFloppyMediaHints()

        if (cpu.nmiPending && cpu.serviceNmiIfPending()) {
            instructionsExecuted++
            val c = cpu.lastInstructionCycles
            advanceTime(if (c < 1) 1 else c)
            noticeWarmBootRequest()
            return true
        }

        if (cpu.halted) {
            // Single-step (TF) is taken after HLT completes, even with IF=0 — CheckIt
            // 2.1's CPU Interrupt Bug test ends in `HLT` and relies on this.
            if (cpu.trapFlag) {
                cpu.serviceInterrupt(1)
                cpu.updateTrapPending()
                instructionsExecuted++
                val c = cpu.lastInstructionCycles
                advanceTime(if (c < 1) 1 else c)
                noticeWarmBootRequest()
                return true
            }
            // HLT with IF=0 waits for NMI/reset on real hardware. Keep advancing
            // peripherals so DRAM-refresh / PIT state can progress.
            if (!cpu.interruptsEnabled()) {
                advanceTime(AVG_CYCLES_PER_INSTRUCTION)
                instructionsExecuted++
                noticeWarmBootRequest()
                return true
            }
            advanceTime(AVG_CYCLES_PER_INSTRUCTION)
            pollHardwareInterrupt()
            instructionsExecuted++
            noticeWarmBootRequest()
            return true
        }

        val addrZero = (cpu.regIp and 0xFFFF) == 0 && cpu.reg16u(REG_CS) == 0
        if (addrZero) return false
        if (!fromStep && breakpointsArmed) {
            val addr = cpu.currentCsIpLinear()
            if (addr in breakpoints) {
                requestPause()
                return true
            }
        }
        if (!cpu.step()) return false
        instructionsExecuted++

        val cycles = cpu.lastInstructionCycles
        advanceTime(if (cycles < 1) 1 else cycles)

        // MOV SS / POP SS delay *all* instruction-boundary traps for one insn
        // (maskable IRQ, NMI sampling, and TF) — Intel's fix for the early-8088
        // stack-switch race. CheckIt 2.1's CPU Interrupt Bug test single-steps
        // across POP SS and hangs on the following HLT if TF ignores the shadow
        // or if trapFlag sticks after INT 1 (FLAG_TF is cleared in pcInterrupt).
        val ssShadow = cpu.ssIrqShadow
        cpu.ssIrqShadow = false
        if (!ssShadow && cpu.trapFlag) {
            cpu.serviceInterrupt(1)
        }
        // Always resync: INT 1 clears FLAG_TF, POPF/IRET may set it. Skipping this
        // after service leaves trapFlag sticky → nested INT 1 forever.
        cpu.updateTrapPending()
        if (!ssShadow && cpu.nmiPending && cpu.serviceNmiIfPending()) {
            irqLineLatched = false
        } else {
            pollHardwareInterrupt(ssShadow)
        }
        noticeWarmBootRequest()
        return true
    }

    /**
     * Take a pending IRQ only if INTR was already latched after the previous
     * instruction — matches 8088 sampling so POST's `OUT 21h` / `IN 21h` IMR
     * probe is not preempted mid-pair (warm-boot error 101). Also respects the
     * one-instruction interrupt shadow after MOV SS / POP SS ([ssShadow]).
     */
    private fun pollHardwareInterrupt(ssShadow: Boolean = false) {
        val src = interruptSource
        if (src == null) {
            irqLineLatched = false
            return
        }
        // Read IF/TF/prefix once — previously each helper re-hit the flag bytes.
        val ifEnabled = cpu.interruptsEnabled()
        val prefix = cpu.prefixActive()
        val trap = cpu.trapFlagSet()
        val canTake = ifEnabled && !prefix && !trap && !ssShadow
        if (!canTake) {
            // Drop any prior latch so the instruction after MOV SS is protected;
            // re-sample below so an IRQ can fire once that instruction completes.
            irqLineLatched = false
        } else if (irqLineLatched) {
            val v = src.pendingVector()
            if (v >= 0) {
                src.acknowledge(v)
                cpu.serviceInterrupt(v)
            }
        }
        irqLineLatched = ifEnabled && !prefix && !trap && src.pendingVector() >= 0
    }

    /**
     * Observe warm POST entry (CAD `ljmp` at EA82 or POST at E05B): stop further
     * host key injection and drop undelivered breaks (86Box-style). Do **not** arm
     * this on every BIOS instruction while 40:72 is already 0x1234 — that would
     * discard Alt/Del during a second CAD's Ctrl INT9. PIC ISR is left for BIOS ICW1.
     */
    private fun noticeWarmBootRequest() {
        // Under DOS (watchingWarmBootCs=false) skip CS reads most of the time —
        // CAD entry into F000 is still seen within one poll quantum.
        if (!watchingWarmBootCs) {
            warmBootPollCounter++
            if ((warmBootPollCounter and 0xFF) != 0) return
        }
        val cs = cpu.reg16u(REG_CS)
        if (cs < 0xF000) {
            if (watchingWarmBootCs) {
                watchingWarmBootCs = false
                keyboard.inReset = false
            }
            return
        }
        watchingWarmBootCs = true
        val ip = cpu.regIp and 0xFFFF
        if (ip != 0xEA82 && ip != 0xE05B) return
        // Each POST may show the resume prompt again — allow one more auto-F1.
        postResumeF1Pressed = false
        postResumePollCounter = 0L
        val flag = cpu.readPhysByte(0x472) or (cpu.readPhysByte(0x473) shl 8)
        if (flag == 0x1234 && !keyboard.inReset) {
            keyboard.inReset = true
            keyboard.reset()
            cga?.allowBdaRowsRestamp()
        }
    }

    companion object {
        /**
         * Idle/HLT peripheral quantum when no instruction just ran. Instruction
         * paths use [peripheralCyclesFor] / the cycle table with no floor.
         */
        const val AVG_CYCLES_PER_INSTRUCTION = 15

        /** PIT is advanced at most this often (CPU clocks). */
        private const val PIT_TICK_QUANTUM = 32

        /** Substring shared by IBM XT and rmDOS POST F1-resume prompts. */
        private const val POST_RESUME_NEEDLE = "RESUME = "

        /** How often [pollPostResumeF1] scans CGA when the CPU is not halted. */
        private const val POST_RESUME_POLL_EVERY = 4096L

        const val MAX_DEBUG_MEMORY_READ = 4096

        /** Cycles to advance peripherals for the last CPU instruction. */
        fun peripheralCyclesFor(instructionCycles: Int, opcodeByte: Int = 0): Int =
            if (instructionCycles < 1) 1 else instructionCycles
    }
}

/** Emulator-side CPU snapshot (mapped to protocol [com.trugath.k8086.protocol.CpuDebugState] by the host). */
data class CpuDebugSnapshot(
    val ax: Int,
    val bx: Int,
    val cx: Int,
    val dx: Int,
    val sp: Int,
    val bp: Int,
    val si: Int,
    val di: Int,
    val es: Int,
    val cs: Int,
    val ss: Int,
    val ds: Int,
    val ip: Int,
    val flags: Int,
    val linearCsIp: Int,
    val halted: Boolean,
    val instructionCount: Long,
    val nextBytes: List<Int>,
    val nextLength: Int,
)
