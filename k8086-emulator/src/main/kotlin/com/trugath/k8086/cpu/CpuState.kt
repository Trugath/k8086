package com.trugath.k8086.cpu

import com.trugath.k8086.bus.InterruptSource
import com.trugath.k8086.bus.IoBus
import com.trugath.k8086.bus.MemoryBus

/**
 * Persistent primitive CPU state: guest memory, register file, decode scratch,
 * and prefix/exception latches.
 *
 * [Emulator8086] subclasses this so hot-path field access stays direct (no
 * extra object hop per register read). One instance per CPU; nothing is
 * allocated while stepping.
 */
internal open class CpuState {
    // Guest memory + register file (register file lives at REGS_BASE in the same array).
    internal val memoryBus = MemoryBus(RAM_SIZE)
    /** Direct reference to [MemoryBus.backing] — @JvmField avoids a getter hop per access. */
    @JvmField
    internal val mem: ByteArray = memoryBus.backing

    /**
     * Exclusive end of installed conventional RAM (below A0000h). Addresses in
     * `[conventionalMemoryEnd, 0xA0000)` read as bus-float (0xFF) and ignore writes
     * so POST’s memory-sizing loop stops at the configured size.
     * @JvmField: hot path reads this every guest byte access.
     */
    @JvmField
    internal var conventionalMemoryEnd: Int = 0xA0000

    fun setConventionalMemoryEnd(value: Int) {
        conventionalMemoryEnd = value.coerceIn(64 * 1024, 0xA0000)
    }

    /** Optional software 8087; when null, ESC opcodes are no-ops. */
    internal var mathCoprocessor: MathCoprocessor8087? = null
    internal val ioPorts = ByteArray(IO_PORT_COUNT)

    // Register views: matches C code's aliasing of regs8 = mem + REGS_BASE
    // and regs16 = (unsigned short *)regs8. Both views read/write the same
    // underlying `mem` storage so writes through one are visible via the other.
    // Non-inner + inline operators keep flag/register access off the JFR top list.
    internal val regs8 = RegBytesView(mem)
    internal val regs16 = RegWordsView(mem)

    // Whether system ROMs have been mapped (for reset / POST).
    @JvmField
    internal var romLoaded = false

    // HLT state: set by the HLT instruction, cleared when an interrupt is serviced.
    @JvmField
    internal var halted = false
    @JvmField
    internal var nmiPending = false

    /**
     * 8086-family: after MOV to SS or POP SS, maskable interrupts (and NMI) are not
     * recognized until the end of the following instruction — so `MOV SS`/`MOV SP`
     * pairs stay atomic. CheckIt's "CPU Interrupt Bug" test fails without this.
     */
    @JvmField
    internal var ssIrqShadow = false

    /**
     * Optional override for branch taken/not-taken cycle costs.
     * [CYCLE_OVERRIDE_NONE] means "use the opcode table" (primitive; no boxing).
     */
    @JvmField
    internal var cycleOverride: Int = CYCLE_OVERRIDE_NONE

    /** Cycles attributed to the most recently executed instruction (coarse estimate). */
    @JvmField
    internal var lastInstructionCycles: Int = 15

    /** Primary opcode byte of the most recently executed instruction. */
    @JvmField
    internal var lastOpcodeByte: Int = 0x90

    // Emulator state
    @JvmField
    internal var regIp = 0
    @JvmField
    internal var trapFlag = false
    @JvmField
    internal var segOverrideEn = 0
    @JvmField
    internal var segOverride = 0
    @JvmField
    internal var repOverrideEn = 0
    @JvmField
    internal var lockOverrideEn = 0
    @JvmField
    internal var repMode: Int = 0

    /**
     * Mid-REP resume: next [Emulator8086.step] re-enters the string body without
     * re-fetching a REP prefix (so [prefixActive] is false between quanta and
     * Machine can deliver IRQ0). Cleared when the REP completes or faults.
     */
    @JvmField
    internal var repContinue = false
    @JvmField
    internal var repContinueRawOpcode = 0
    @JvmField
    internal var repContinueXlat = 0
    @JvmField
    internal var repContinueExtra = 0
    @JvmField
    internal var repContinueIW = false
    @JvmField
    internal var repContinueRepMode = 0
    @JvmField
    internal var repContinueSegActive = false
    @JvmField
    internal var repContinueSeg = 0
    /** CS:IP of the suspended string body — resume only when CS:IP matches (not in ISR). */
    @JvmField
    internal var repContinueCs = 0
    @JvmField
    internal var repContinueIp = 0
    /** When true, skip IP advance after this string quantum (more CX remains). */
    @JvmField
    internal var suppressIpAdvance = false

    /** CS:IP of the first byte of a current instruction (including prefixes). */
    @JvmField
    internal var instructionStartIp = 0

    /** Prefix bytes already consumed for the current logical instruction. */
    @JvmField
    internal var instructionPrefixBytes = 0

    // Instruction decoding
    @JvmField
    internal var rawOpcodeId = 0
    @JvmField
    internal var iW = false
    @JvmField
    internal var iD = false
    @JvmField
    internal var iMod = 0
    @JvmField
    internal var iReg = 0
    @JvmField
    internal var iRm = 0
    @JvmField
    internal var iModSize = 0
    @JvmField
    internal var iData0 = 0
    @JvmField
    internal var iData1 = 0
    @JvmField
    internal var iData2 = 0
    @JvmField
    internal var setFlagsType = 0
    @JvmField
    internal var xlatOpcodeId = 0
    @JvmField
    internal var extra = 0

    // Scratch variables
    @JvmField
    internal var scratchUint = 0
    @JvmField
    internal var scratch2Uint = 0
    @JvmField
    internal var scratchInt = 0
    @JvmField
    internal var opResult = 0
    @JvmField
    internal var opDest = 0
    @JvmField
    internal var opSource = 0
    @JvmField
    internal var rmAddr = 0
    @JvmField
    internal var rmOffset = 0
    @JvmField
    internal var rmSegment = REG_ZERO
    @JvmField
    internal var rmIsMemory = false
    // 8086 #DE pushes the next-instruction IP. Defer until after length/flag updates.
    @JvmField
    internal var pendingDivideError = false
    /** Pending CPU exception vector (-1 = none); delivered like #DE after IP policy. */
    @JvmField
    internal var pendingException = -1
    @JvmField
    internal var opToAddr = 0
    @JvmField
    internal var opFromAddr = 0

    // Hardware integration hooks. When an IoBus is attached, IN/OUT route to real
    // device models (PIC/PIT/PPI/DMA/CRTC/FDC); unmapped ports fall back to the flat
    // ioPorts array. The interrupt source (PIC) is polled by the machine loop.
    internal var ioBus: IoBus? = null
    internal var interruptSource: InterruptSource? = null

    // Optional diagnostic hook: invoked on every port access (write=true for OUT).
    internal var portTrace: ((write: Boolean, port: Int, value: Int) -> Unit)? = null

    // Optional diagnostic hook: invoked on every interrupt dispatch (INT/exception/IRQ),
    // with the vector and the CS:IP being interrupted.
    internal var interruptTrace: ((vector: Int, cs: Int, ip: Int) -> Unit)? = null
}

/**
 * Byte view of the memory-mapped register file at [REGS_BASE].
 * Inline operators collapse to direct [ByteArray] loads at call sites.
 */
internal class RegBytesView(@JvmField val mem: ByteArray) {
    @Suppress("NOTHING_TO_INLINE")
    inline operator fun get(index: Int): Byte = mem[REGS_BASE + index]

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun set(index: Int, value: Byte) {
        mem[REGS_BASE + index] = value
    }
}

/**
 * Little-endian word view of the same register file as [RegBytesView].
 */
internal class RegWordsView(@JvmField val mem: ByteArray) {
    @Suppress("NOTHING_TO_INLINE")
    inline operator fun get(index: Int): Short {
        val addr = REGS_BASE + 2 * index
        val lo = mem[addr].toInt() and 0xFF
        val hi = mem[addr + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun set(index: Int, value: Short) {
        val addr = REGS_BASE + 2 * index
        val v = value.toInt() and 0xFFFF
        mem[addr] = (v and 0xFF).toByte()
        mem[addr + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
