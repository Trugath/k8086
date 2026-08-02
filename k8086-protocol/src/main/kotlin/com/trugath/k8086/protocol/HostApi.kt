package com.trugath.k8086.protocol

/** Opaque VM identifier (UUID string). */
@JvmInline
value class VmId(val value: String) {
    override fun toString(): String = value
}

enum class VmState {
    Stopped,
    Starting,
    Running,
    Paused,
    Stopping,
    Error,
}

enum class GraphicsKind {
    NONE,
    CGA,
}

enum class InitialVideoKind {
    SPECIAL_OR_NONE,
    CGA_40x25,
    CGA_80x25,
    MDA_80x25,
}

data class MotherboardSpec(
    /** Wire name: `"8088"` (default, XT) or `"8086"`. */
    val cpu: String = "8088",
    val baseMemoryKb: Int = 640,
    val mathCoprocessor: Boolean = false,
    val initialVideo: InitialVideoKind = InitialVideoKind.CGA_80x25,
    val postLoop: Boolean = false,
)

data class FloppySpec(
    val enabled: Boolean = true,
    val driveImages: List<String> = emptyList(),
    /** Guest FDC owns INT 13h by default. */
    val useInt13Shim: Boolean = false,
)

data class HardDiskSpec(
    val enabled: Boolean = false,
    val imagePath: String? = null,
    val secondImagePath: String? = null,
    val provisionBytes: Long = 10_607_616L, // classic XT ~10 MB
    val bootFromDisk: Boolean = false,
    val ioBase: Int = 0x320,
    val irq: Int = 5,
    val dmaChannel: Int = 3,
    val useInt13Shim: Boolean = false,
    /** Host FixedDiskBios intercept; false → guest C800 option ROM. Default false. */
    val useHostFixedDiskBios: Boolean = false,
    val fixedDiskRomPath: String? = null,
    val cylinders: Int? = null,
    val heads: Int? = null,
    val sectorsPerTrack: Int? = null,
)

data class CardSpecDto(
    val jarPath: String,
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap(),
)

/**
 * Persisted VM configuration (wire-ready; no emulator types).
 */
data class VmDefinition(
    val id: VmId,
    val name: String,
    val u18RomPath: String,
    val u19RomPath: String,
    val motherboard: MotherboardSpec = MotherboardSpec(),
    val graphics: GraphicsKind = GraphicsKind.CGA,
    val enableCom1: Boolean = true,
    val floppy: FloppySpec = FloppySpec(),
    val hardDisk: HardDiskSpec = HardDiskSpec(),
    val cards: List<CardSpecDto> = emptyList(),
)

data class VmSummary(
    val id: VmId,
    val name: String,
    val state: VmState,
    val errorMessage: String? = null,
)

data class VmMetrics(
    val id: VmId,
    val state: VmState,
    val instructionCount: Long = 0,
    val uptimeMs: Long = 0,
    val floppyPaths: List<String?> = emptyList(),
)

data class ConsoleFrame(
    val width: Int,
    val height: Int,
    /** Packed ARGB, row-major. */
    val argb: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsoleFrame) return false
        return width == other.width && height == other.height && argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argb.contentHashCode()
        return result
    }
}

data class NetworkSummary(
    val id: String,
    val name: String,
    val kind: String = "nat",
)

/**
 * Persisted virtual network: host userspace-NAT gateway with optional DHCP.
 */
data class NetworkDefinition(
    val id: String,
    val name: String,
    val gatewayIp: String = "10.0.2.2",
    val subnetMask: String = "255.255.255.0",
    val dhcpEnabled: Boolean = true,
    val dhcpStartIp: String = "10.0.2.15",
    val dhcpEndIp: String = "10.0.2.31",
) {
    fun toSummary(): NetworkSummary = NetworkSummary(id = id, name = name, kind = "nat")
}

/** Architectural CPU snapshot for the debug window. */
data class CpuDebugState(
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
    /** Raw bytes of the next instruction at CS:IP (including prefixes). */
    val nextBytes: List<Int>,
    val nextLength: Int,
)

/** Physical guest-memory dump for the debug window. */
data class MemoryDump(
    val address: Int,
    val bytes: List<Int>,
)

/**
 * Completed LPT1 capture ready for a host preview / system print.
 * [text] is CP437-decoded for display; [rawBytes] preserves guest output for Save.
 */
data class PrintJob(
    val vmId: VmId,
    val text: String,
    val rawBytes: ByteArray,
    val capturedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrintJob) return false
        return vmId == other.vmId &&
            text == other.text &&
            capturedAtMs == other.capturedAtMs &&
            rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int {
        var result = vmId.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + capturedAtMs.hashCode()
        return result
    }
}

/**
 * Host control plane. Milestone-1 implementations are in-process; DTOs are JSON-ready
 * for a future WebSocket transport.
 */
interface HostApi {
    fun listVms(): List<VmSummary>
    fun getDefinition(id: VmId): VmDefinition?
    fun createVm(definition: VmDefinition): VmSummary
    fun updateVm(definition: VmDefinition): VmSummary
    fun deleteVm(id: VmId)

    fun startVm(id: VmId)
    fun stopVm(id: VmId)

    fun metrics(id: VmId): VmMetrics?

    fun pollConsoleFrame(id: VmId): ConsoleFrame?
    /** Drain completed LPT1 print jobs for [id] (may be empty). */
    fun pollPrintJobs(id: VmId): List<PrintJob>
    fun sendScanCode(id: VmId, code: Int)
    fun sendCtrlAltDelete(id: VmId)
    fun changeFloppy(id: VmId, drive: Int, path: String?)
    fun setConsoleFocused(id: VmId, focused: Boolean)
    fun setAudioMuted(id: VmId, muted: Boolean)
    fun isAudioMuted(id: VmId): Boolean

    fun pauseVm(id: VmId)
    fun resumeVm(id: VmId)
    fun isPaused(id: VmId): Boolean
    fun setTurbo(id: VmId, enabled: Boolean)
    fun isTurbo(id: VmId): Boolean

    fun getCpuDebugState(id: VmId): CpuDebugState?
    fun readGuestMemory(id: VmId, address: Int, length: Int): MemoryDump?
    /** Execute one instruction while remaining paused. Returns false if not paused/running. */
    fun stepVm(id: VmId): Boolean
    fun addBreakpoint(id: VmId, linearAddress: Int)
    fun removeBreakpoint(id: VmId, linearAddress: Int)
    fun listBreakpoints(id: VmId): List<Int>

    fun network(): NetworkApi
}

/**
 * Virtual network control plane: CRUD for NAT networks with optional DHCP.
 * Guest NICs attach via card config `network=<id>` at VM start.
 */
interface NetworkApi {
    fun listNetworks(): List<NetworkDefinition>
    fun getNetwork(id: String): NetworkDefinition?
    fun createNetwork(definition: NetworkDefinition): NetworkDefinition
    fun updateNetwork(definition: NetworkDefinition): NetworkDefinition
    fun deleteNetwork(id: String)
}

/** Empty stub for tests / hosts without networking. */
object NetworkApiStub : NetworkApi {
    override fun listNetworks(): List<NetworkDefinition> = emptyList()
    override fun getNetwork(id: String): NetworkDefinition? = null
    override fun createNetwork(definition: NetworkDefinition): NetworkDefinition =
        error("Networking is not available")
    override fun updateNetwork(definition: NetworkDefinition): NetworkDefinition =
        error("Networking is not available")
    override fun deleteNetwork(id: String) = error("Networking is not available")
}
