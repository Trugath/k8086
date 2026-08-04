package com.trugath.k8086.cpu

// Intel 8086 instruction engine. Emulator8088 inherits this implementation and
// overrides the small number of observable silicon differences.
// Opcode xlat/length maps live in DecodeTables; ModR/M, parity, FLAGS layout,
// and Jcc predicates are expressed in code. Runtime firmware is IBM ROM BIOS.

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.bus.InterruptSource
import com.trugath.k8086.bus.IoBus

// Emulator system constants
internal const val IO_PORT_COUNT = 0x10000
// Guest-addressable space is 0x00000..0x10FFEF (1 MB plus the 8086-family HMA wrap
// window). The memory-mapped register file is relocated ABOVE that window so it
// can never collide with the real IBM ROM BIOS in the F000 segment.
internal const val REGS_BASE = 0x110000
internal const val RAM_SIZE = 0x110100
// IBM 5155/5160 (PC/XT) ROM BIOS memory map. The U19 (8 KB) and U18 (32 KB) chips
// together fill 0xF6000-0xFFFFF; the CPU reset vector at 0xFFFF0 lives in U18.
internal const val ROM_BIOS_U19_BASE = 0xF6000  // lower Cassette BASIC (8 KB)
internal const val ROM_BIOS_U18_BASE = 0xF8000  // upper BASIC + system BIOS (32 KB)
internal const val ROM_REGION_START = 0xF6000
internal const val ROM_REGION_END = 0x100000    // exclusive

// Classic IBM PC/XT fixed disk (Seagate ST-412): 306 cylinders × 4 heads ×
// 17 sectors/track × 512 bytes ≈ 10 MB. Used to provision a blank hard-disk
// image when the requested file does not exist yet, so a bare `@hd.img` gives
// DOS a formattable 10 MB drive instead of a 0-cylinder stub.
internal const val XT_HARD_DISK_BYTES = 306L * 4 * 17 * 512  // 10,653,696

// 16-bit register decodes
internal const val REG_AX = 0
internal const val REG_CX = 1
internal const val REG_DX = 2
internal const val REG_BX = 3
internal const val REG_SP = 4
internal const val REG_BP = 5
internal const val REG_SI = 6
internal const val REG_DI = 7

internal const val REG_ES = 8
internal const val REG_CS = 9
internal const val REG_SS = 10
internal const val REG_DS = 11

internal const val REG_ZERO = 12
internal const val REG_SCRATCH = 13

// 8-bit register decodes
internal const val REG_AL = 0
internal const val REG_AH = 1
internal const val REG_CL = 2
internal const val REG_CH = 3
internal const val REG_DL = 4
internal const val REG_DH = 5
internal const val REG_BL = 6
internal const val REG_BH = 7

// FLAGS register decodes
internal const val FLAG_CF = 40
internal const val FLAG_PF = 41
internal const val FLAG_AF = 42
internal const val FLAG_ZF = 43
internal const val FLAG_SF = 44
internal const val FLAG_TF = 45
internal const val FLAG_IF = 46
internal const val FLAG_DF = 47
internal const val FLAG_OF = 48

// FLAGS register bit positions (Intel layout)
private val FLAGS_BIT_POSITIONS = intArrayOf(0, 2, 4, 6, 7, 8, 9, 10, 11)

// Bitfields for DecodeTables.STD_FLAGS values
internal const val FLAGS_UPDATE_SZP = 1
internal const val FLAGS_UPDATE_AO_ARITH = 2
internal const val FLAGS_UPDATE_OC_LOGIC = 4

/** Sentinel from [Emulator8086.overflowAfterShiftRotate]: leave OF unchanged. */
internal const val OF_UNCHANGED = -1

/** Sentinel for [CpuState.cycleOverride]: consult [CycleTables] instead. */
internal const val CYCLE_OVERRIDE_NONE = -1

/**
 * Max REP string iterations per [step] when IF=1. Long fills (Wolf Mode Y) otherwise
 * starve PIT/IRQ0 inside one giant instruction. Resume via [CpuState.repContinue].
 * Keep this large enough that InitGame “Working…” still completes; too-small values
 * regress HD IRQ livelock edge cases and stall resource load.
 */
internal const val REP_ITER_QUANTUM = 256

/** ALU ops for [Emulator8086.memOp] — Int so the when compiles to tableswitch. */
internal const val ALU_ADD = 0
internal const val ALU_SUB = 1
internal const val ALU_MUL = 2
internal const val ALU_AND = 3
internal const val ALU_OR = 4
internal const val ALU_XOR = 5
internal const val ALU_MOV = 6

/**
 * Intel 8086 CPU emulator and base implementation for the bus-compatible 8088.
 *
 * The processors share their instruction set and architectural state, so
 * [Emulator8088] inherits this engine and overrides only observable silicon
 * differences. Construct this class directly for an 8086.
 */
internal open class Emulator8086(
    profile: DecodeProfile = DecodeProfiles.I8086,
) : CpuState() {
    open val model: CpuModel = CpuModel.I8086

    /** Per-model decode metadata; tables are also cached as @JvmField arrays below. */
    @JvmField
    internal val decodeProfile: DecodeProfile = profile

    @JvmField internal val tblXlatOpcode: ByteArray = profile.xlatOpcode
    @JvmField internal val tblXlatSubfunction: ByteArray = profile.xlatSubfunction
    @JvmField internal val tblStdFlags: ByteArray = profile.stdFlags
    @JvmField internal val tblBaseInstSize: ByteArray = profile.baseInstSize
    @JvmField internal val tblIWSize: ByteArray = profile.iWSize
    @JvmField internal val tblIModSize: ByteArray = profile.iModSize

    /**
     * 286 enforces a 10-byte instruction limit; 8086/8088 do not. Cached so the
     * decode loop can skip [encodingLengthBytes] on the common models.
     */
    @JvmField
    internal val maxInsnBytes: Int =
        if (profile === DecodeProfiles.I80286) 10 else Int.MAX_VALUE

    /** Persistent host hooks (DOS terminate, INT 13h); never allocated while stepping. */
    internal val hostServices = CpuHostServices()

    /** Floppy/HDD image store; Machine may also hold a reference after wiring. */
    internal val diskStore = DiskImageStore()

    /** Allocation-free fetch/decode/length orchestrator (one instance per CPU). */
    private val decoder = InstructionDecoder(this)

    /**
     * Reused ESC host callbacks — lambdas capture this CPU once; pointer/CS
     * fields are updated per instruction (no allocation on the FPU path).
     */
    internal val fpuAccess = MathCoprocessor8087.Access(
        readByte = { addr -> readPhysByte(addr) },
        writeByte = { addr, value -> writePhysByte(addr, value) },
        writeAx = { value -> regs16[REG_AX] = (value and 0xFFFF).toShort() },
    )
    /** Request a non-maskable interrupt (INT 2) on the next instruction boundary. */
    fun requestNmi() {
        nmiPending = true
        halted = false
    }

    fun isNmiPending(): Boolean = nmiPending

    /** Service a pending NMI if any. Returns true if NMI was delivered. */
    fun serviceNmiIfPending(): Boolean {
        if (!nmiPending) return false
        nmiPending = false
        pcInterrupt(2)
        return true
    }
    fun isHalted(): Boolean = halted

    /** True if the just-finished instruction loaded SS (clears the shadow latch). */
    fun pollSsInterruptShadow(): Boolean {
        val shadowed = ssIrqShadow
        ssIrqShadow = false
        return shadowed
    }

    internal fun armSsInterruptShadow() {
        ssIrqShadow = true
    }

    fun attachIoBus(bus: IoBus) { ioBus = bus }
    fun attachInterruptSource(src: InterruptSource) { interruptSource = src }

    // --- Public surface used by the Machine run loop and device models. ---

    // Execute exactly one instruction. Returns false when the CPU should stop
    // (CS:IP resolved to linear 0 — used as the emulator halt convention).
    fun step(): Boolean = decoder.decodeAndExecuteInstruction()

    // Linear address currently pointed to by CS:IP.
    fun currentCsIpLinear(): Int = segreg(REG_CS, REG_ZERO, regIp)

    fun interruptsEnabled(): Boolean = mem[REGS_BASE + FLAG_IF].toInt() != 0
    fun trapFlagSet(): Boolean = mem[REGS_BASE + FLAG_TF].toInt() != 0
    fun prefixActive(): Boolean =
        segOverrideEn != 0 || repOverrideEn != 0 || lockOverrideEn != 0
    fun isTrapPending(): Boolean = trapFlag
    fun updateTrapPending() { trapFlag = mem[REGS_BASE + FLAG_TF].toInt() != 0 }

    // Inject an interrupt (hardware IRQ vector, or a service like INT 1 single-step).
    fun serviceInterrupt(vector: Int) = pcInterrupt(vector)

    // Physical memory access for DMA / device models / ISA cards.
    fun readPhysByte(addr: Int): Int = guestRead8(addr)
    fun writePhysByte(addr: Int, value: Int) = guestWrite8(addr, value)

    /** Guest (and HMA) read; register-file addresses use the backing array directly. */
    internal fun guestRead8(addr: Int): Int {
        // Hottest: conventional RAM and system ROM are direct backing loads.
        if (addr < 0xA0000) {
            if (addr < 0 || addr >= conventionalMemoryEnd) return 0xFF
            return mem[addr].toInt() and 0xFF
        }
        if (addr < ROM_REGION_END) {
            if (addr >= ROM_REGION_START) return mem[addr].toInt() and 0xFF
            return memoryBus.read8(addr) // A0000..F5FFF: video / UMB / option ROM MMIO
        }
        if (addr >= REGS_BASE) {
            return if (addr < RAM_SIZE) mem[addr].toInt() and 0xFF else 0xFF
        }
        return memoryBus.read8(addr) // HMA wrap window
    }

    internal fun guestWrite8(addr: Int, value: Int) {
        if (addr >= 0 && addr < 0xA0000) {
            if (addr >= conventionalMemoryEnd) return
            mem[addr] = (value and 0xFF).toByte()
            return
        }
        // ROM write-protect without scanning overlays.
        if (addr >= ROM_REGION_START && addr < ROM_REGION_END) return
        if (addr >= REGS_BASE) {
            if (addr < RAM_SIZE) mem[addr] = (value and 0xFF).toByte()
            return
        }
        memoryBus.write8(addr, value)
    }

    internal fun guestRead16(addr: Int): Int =
        guestRead8(addr) or (guestRead8(addr + 1) shl 8)

    internal fun guestWrite16(addr: Int, value: Int) {
        guestWrite8(addr, value and 0xFF)
        guestWrite8(addr + 1, (value shr 8) and 0xFF)
    }

    internal fun readSegmentWord(segmentReg: Int, offset: Int): Int {
        val off = offset and 0xFFFF
        if (wordOffsetFaults(off)) {
            raiseException(exceptionForWordAccess(segmentReg))
            return 0
        }
        return guestRead8(segreg(segmentReg, REG_ZERO, off)) or
            (guestRead8(segreg(segmentReg, REG_ZERO, off + 1)) shl 8)
    }

    internal fun writeSegmentWord(segmentReg: Int, offset: Int, value: Int) {
        val off = offset and 0xFFFF
        if (wordOffsetFaults(off)) {
            raiseException(exceptionForWordAccess(segmentReg))
            return
        }
        guestWrite8(segreg(segmentReg, REG_ZERO, off), value)
        guestWrite8(segreg(segmentReg, REG_ZERO, off + 1), value shr 8)
    }

    // Byte/word port access. A mapped device model handles the port if present,
    // otherwise the flat ioPorts array backs it (scratch/undecoded ports).
    internal fun ioReadPort8(port: Int): Int {
        if (port < 0 || port >= IO_PORT_COUNT) return 0xFF
        // Undecoded ports float high on the XT bus: an IN from a port with no device
        // driving the data lines reads 0xFF, never the last value written. POST relies
        // on this (e.g. the expansion-unit probe at 0x210 writes 0x55/0xAA and treats a
        // non-matching read as "no expansion unit installed").
        val value = ioBus?.deviceFor(port)?.let { it.ioReadByte(port) and 0xFF } ?: 0xFF
        portTrace?.invoke(false, port, value)
        return value
    }

    internal fun ioWritePort8(port: Int, value: Int) {
        if (port < 0 || port >= IO_PORT_COUNT) return
        portTrace?.invoke(true, port, value and 0xFF)
        val d = ioBus?.deviceFor(port)
        if (d != null) d.ioWriteByte(port, value and 0xFF) else ioPorts[port] = (value and 0xFF).toByte()
    }

    internal fun ioReadPort16(port: Int): Int = ioReadPort8(port) or (ioReadPort8(port + 1) shl 8)

    internal fun ioWritePort16(port: Int, value: Int) {
        ioWritePort8(port, value and 0xFF)
        ioWritePort8(port + 1, (value shr 8) and 0xFF)
    }
    
    // Get register address (offset into mem array).
    // GET_REG_ADDR is `2*reg_id + reg_id/4 & 7` where `&`
    // binds LOWER than `+`, i.e. `(2*reg_id + reg_id/4) & 7` - this maps byte reg
    // codes 4-7 (AH/CH/DH/BH) onto the high byte of AX/CX/DX/BX. Kotlin's infix `and`
    // also binds looser than `+`, so parenthesizing as `2*regId + ((regId/4) and 7)`
    // (as this used to read) computes a different, wrong address for AH/CH/DH/BH.
    internal fun getRegAddr(regId: Int): Int {
        return REGS_BASE + if (iW) 2 * regId else (2 * regId + regId / 4) and 7
    }
    
    // Returns number of top bit in operand
    internal fun topBit(): Int = 8 * (if (iW) 2 else 1)
    
    /**
     * Physical bus width mask (20-bit on 8086/8088, 24-bit on 286). Field rather
     * than a virtual call keeps segreg on the monomorphic fast path.
     */
    @JvmField
    internal var physicalAddressMask: Int = 0xFFFFF

    // Convert segment:offset to a physical address. Offset arithmetic wraps at
    // 16 bits; 8086 physical addresses wrap at 20 bits, while the 286 keeps 24-bit.
    internal fun segreg(regSeg: Int, regOfs: Int, offset: Int = 0): Int {
        val seg = reg16u(regSeg)
        val ofs = if (regOfs == REG_ZERO) 0 else reg16u(regOfs)
        return ((seg shl 4) + ((ofs + offset) and 0xFFFF)) and physicalAddressMask
    }

    /** Unsigned 16-bit register read without Short boxing. */
    internal fun reg16u(index: Int): Int {
        val addr = REGS_BASE + 2 * index
        return (mem[addr].toInt() and 0xFF) or ((mem[addr + 1].toInt() and 0xFF) shl 8)
    }

    /** Mask a linear address to the CPU's physical bus width. */
    internal fun physicalAddress(linear: Int): Int = linear and physicalAddressMask

    
    // Returns sign bit of an 8-bit or 16-bit operand
    internal fun signOf(value: Int): Int {
        val topBit = topBit()
        return if (iW) {
            (value.toShort().toInt() shr (topBit - 1)) and 1
        } else {
            (value.toByte().toInt() shr (topBit - 1)) and 1
        }
    }
    
    /**
     * Pack ModR/M base/index/default-segment into a Long (no allocation).
     * Layout: bits 0..7 base, 8..15 index, 16..23 defaultSeg.
     * Shared by [decodeRmReg] and 286 system-instruction EA calculation.
     */
    internal fun modRmComponentsPacked(rm: Int, mod: Int): Long {
        val base: Int
        val index: Int
        val defaultSeg: Int
        when (rm) {
            0 -> { base = REG_BX; index = REG_SI; defaultSeg = REG_DS }
            1 -> { base = REG_BX; index = REG_DI; defaultSeg = REG_DS }
            2 -> { base = REG_BP; index = REG_SI; defaultSeg = REG_SS }
            3 -> { base = REG_BP; index = REG_DI; defaultSeg = REG_SS }
            4 -> { base = REG_SI; index = REG_ZERO; defaultSeg = REG_DS }
            5 -> { base = REG_DI; index = REG_ZERO; defaultSeg = REG_DS }
            6 -> if (mod == 0) {
                base = REG_ZERO; index = REG_ZERO; defaultSeg = REG_DS // [disp16]
            } else {
                base = REG_BP; index = REG_ZERO; defaultSeg = REG_SS
            }
            else -> { base = REG_BX; index = REG_ZERO; defaultSeg = REG_DS } // rm=7
        }
        return (base.toLong() and 0xFF) or
            ((index.toLong() and 0xFF) shl 8) or
            ((defaultSeg.toLong() and 0xFF) shl 16)
    }

    /**
     * Linear effective address for a ModR/M memory operand (no segment override).
     * Used by 286 0x0F system instructions that decode their own ModR/M.
     */
    internal fun effectiveAddressLinear(mod: Int, rm: Int, disp: Int): Int {
        val packed = modRmComponentsPacked(rm, mod)
        val base = (packed and 0xFF).toInt()
        val index = ((packed shr 8) and 0xFF).toInt()
        val defaultSeg = ((packed shr 16) and 0xFF).toInt()
        var offset = (if (base == REG_ZERO) 0 else reg16u(base)) +
            (if (index == REG_ZERO) 0 else reg16u(index))
        if (mod != 0 || rm == 6) offset += disp
        return segreg(defaultSeg, REG_ZERO, offset and 0xFFFF)
    }

    // Decode mod, r/m and reg fields — standard 8086 effective-address encoding.
    internal fun decodeRmReg() {
        if (iMod < 3) {
            // Memory addressing: [BX+SI], [BX+DI], [BP+SI], [BP+DI], [SI], [DI],
            // [disp16] (mod=0 rm=6) or [BP+disp] (mod≠0 rm=6), [BX].
            // Default segment is DS, except BP-based forms use SS.
            val packed = modRmComponentsPacked(iRm, iMod)
            val base = (packed and 0xFF).toInt()
            val index = ((packed shr 8) and 0xFF).toInt()
            val defaultSeg = ((packed shr 16) and 0xFF).toInt()
            val segReg = if (segOverrideEn != 0) segOverride else defaultSeg
            var offset = (if (base == REG_ZERO) 0 else reg16u(base)) +
                (if (index == REG_ZERO) 0 else reg16u(index))
            if (iMod != 0 || iRm == 6) {
                offset += iData1
            }
            rmOffset = offset and 0xFFFF
            rmSegment = segReg
            rmIsMemory = true
            rmAddr = segreg(segReg, REG_ZERO, rmOffset)
        } else {
            // Register mode (iMod == 3)
            rmIsMemory = false
            rmAddr = getRegAddr(iRm)
        }

        opFromAddr = getRegAddr(iReg)
        opToAddr = rmAddr

        if (iD) {
            val temp = opFromAddr
            opFromAddr = rmAddr
            opToAddr = temp
        }
    }
    
    // Flag operations
    internal fun setCF(value: Boolean) {
        if (pendingException >= 0 || pendingDivideError) return
        regs8[FLAG_CF] = if (value) 1 else 0
    }
    
    internal fun setOF(value: Boolean) {
        if (pendingException >= 0 || pendingDivideError) return
        regs8[FLAG_OF] = if (value) 1 else 0
    }

    /**
     * OF after a shift/rotate as a primitive sentinel (no boxing):
     * [OF_UNCHANGED] keep prior OF; 0 clear; 1 set.
     *
     * On the 8086, OF is defined only for a count of one. The 8088 overrides
     * this rule because its silicon has observable behavior for larger counts.
     */
    internal open fun overflowAfterShiftRotate(
        operation: Int,
        count: Int,
        originalValue: Int,
        result: Int,
        carry: Boolean,
        signMask: Int,
    ): Int =
        if (count == 1) {
            definedShiftRotateOverflow(operation, count, originalValue, result, carry, signMask)
        } else {
            OF_UNCHANGED
        }

    /** Computes OF for silicon on which the supplied count has defined behavior. */
    protected fun definedShiftRotateOverflow(
        operation: Int,
        count: Int,
        originalValue: Int,
        result: Int,
        carry: Boolean,
        signMask: Int,
    ): Int = when (operation) {
        0, 2, 4 -> if ((result and signMask != 0) xor carry) 1 else 0 // ROL, RCL, SHL
        1, 3 -> if (((result xor (result shl 1)) and signMask) != 0) 1 else 0 // ROR, RCR
        5 -> if (count == 1 && (originalValue and signMask) != 0) 1 else 0 // SHR
        7 -> 0 // SAR
        else -> OF_UNCHANGED // Undocumented SETMO operation
    }
    
    internal fun setAF(value: Boolean) {
        if (pendingException >= 0 || pendingDivideError) return
        regs8[FLAG_AF] = if (value) 1 else 0
    }
    
    internal fun setPF(value: Boolean) {
        if (pendingException >= 0 || pendingDivideError) return
        regs8[FLAG_PF] = if (value) 1 else 0
    }
    
    internal fun setZF(value: Boolean) {
        regs8[FLAG_ZF] = if (value) 1 else 0
    }
    
    internal fun setSF(value: Boolean) {
        regs8[FLAG_SF] = if (value) 1 else 0
    }

    // PF = 1 when the low byte has even parity (Intel 8086).
    internal fun parityFlag(value: Int): Int =
        if (Integer.bitCount(value and 0xFF) and 1 == 0) 1 else 0

    // Evaluate Jcc condition code 0..7 (JO, JB, JE, JBE, JS, JP, JL, JLE).
    internal fun jccTaken(cond: Int): Boolean {
        val m = mem
        val base = REGS_BASE
        val cf = m[base + FLAG_CF].toInt() != 0
        val zf = m[base + FLAG_ZF].toInt() != 0
        val sf = m[base + FLAG_SF].toInt() != 0
        val of = m[base + FLAG_OF].toInt() != 0
        val pf = m[base + FLAG_PF].toInt() != 0
        return when (cond) {
            0 -> of
            1 -> cf
            2 -> zf
            3 -> cf || zf
            4 -> sf
            5 -> pf
            6 -> sf xor of
            7 -> (sf xor of) || zf
            else -> false
        }
    }
    
    // Assemble and return emulated CPU FLAGS register
    internal fun makeFlags(): Int {
        var flags = flagsReservedOnes() or flagsExtraBits
        for (i in 8 downTo 0) {
            flags += (regs8[FLAG_CF + i].toInt() shl FLAGS_BIT_POSITIONS[i])
        }
        return flags and 0xFFFF
    }
    
    // Set emulated CPU FLAGS register from flags value
    internal fun setFlags(flags: Int) {
        for (i in 8 downTo 0) {
            regs8[FLAG_CF + i] = if ((flags and (1 shl FLAGS_BIT_POSITIONS[i])) != 0) 1 else 0
        }
        flagsExtraBits = flags and flagsExtraMask()
    }

    /**
     * Reserved FLAGS bits that always read as 1. On the 8086 that is 0xF002;
     * on the 286 only bit 1 is forced (IOPL/NT live in [flagsExtraBits]).
     */
    internal open fun flagsReservedOnes(): Int = 0xF002

    /** Mask of FLAGS bits preserved outside the CF..OF set (IOPL/NT on 286). */
    internal open fun flagsExtraMask(): Int = 0

    /** Non-tracked FLAGS bits (e.g. 286 IOPL/NT) retained across make/setFlags. */
    internal var flagsExtraBits: Int = 0

    /** Mask applied to values loaded by POPF/IRET. 286 real-mode clears IOPL/NT/bit15. */
    internal open fun popfValueMask(): Int = 0xFFFF

    
    // AAA and AAS instructions - which_operation is +1 for AAA, and -1 for AAS
    // Matches C code: AAA_AAS function
    internal fun aaaAas(whichOperation: Int): Int {
        val al = regs8[REG_AL].toUByte().toInt()
        val lowNibble = al and 0x0F
        val af = regs8[FLAG_AF].toInt() != 0
        
        val shouldAdjust = (lowNibble > 9) || af
        setAF(shouldAdjust)
        setCF(shouldAdjust)
        
        if (shouldAdjust) {
            if (aaaAasAddsToAxWord()) {
                // 286+: AX ← AX ± 0106h (AL adjust may carry/borrow into AH).
                val ax = regs16[REG_AX].toInt() and 0xFFFF
                regs16[REG_AX] = ((ax + whichOperation * 0x106) and 0xFFFF).toShort()
            } else {
                // 8086: AL and AH are adjusted independently (no carry between them).
                regs8[REG_AL] = (al + 6 * whichOperation).toByte()
                val ah = regs8[REG_AH].toUByte().toInt()
                regs8[REG_AH] = (ah + whichOperation).toByte()
            }
        }
        regs8[REG_AL] = (regs8[REG_AL].toInt() and 0x0F).toByte()
        
        return regs16[REG_AX].toInt() and 0xFFFF
    }

    /** When true, AAA/AAS use AX ± 0106h (286+). 8086 adjusts AL/AH separately. */
    internal open fun aaaAasAddsToAxWord(): Boolean = false
    
    // Arithmetic operations
    // set_AF_OF_arith semantics, including
    // mutation of op_source via `^=` - nothing reads the pre-mutation opSource
    // after this call in any caller.
    internal fun setAFOfArith() {
        opSource = opSource xor opDest xor opResult
        setAF((opSource and 0x10) != 0)
        if (opResult == opDest) {
            setOF(false)
        } else {
            val cf = regs8[FLAG_CF].toInt()
            setOF((1 and (cf xor (opSource shr (topBit() - 1)))) != 0)
        }
    }
    
    // Memory operations
    // C's OP()/MEM_OP() macros only write the result back to `dest` when the
    // passed operator is a compound assignment (+=, -=, &=, |=, ^=, =). CMP and
    // TEST instead use the bare operator (-, &) purely to compute flags without
    // modifying the destination, so writeBack must be false for those callers.
    // Operand-width mask (0xFF for byte ops, 0xFFFF for word ops) used to truncate
    // opResult before the unsigned carry/borrow comparisons, matching how the C
    // reference stores op_result through a width-typed cast.
    internal fun cfWidthMask(): Int = if (iW) 0xFFFF else 0xFF

    internal fun rmAddress(relative: Int): Int =
        if (rmIsMemory) segreg(rmSegment, REG_ZERO, rmOffset + relative) else rmAddr + relative

    internal fun readOperand(addr: Int): Int {
        if (!iW) return guestRead8(addr)
        if (rmIsMemory && addr == rmAddr && wordOffsetFaults(rmOffset and 0xFFFF)) {
            raiseException(exceptionForWordAccess(rmSegment))
            return 0
        }
        return if (rmIsMemory && addr == rmAddr) {
            guestRead8(rmAddr) or (guestRead8(rmAddress(1)) shl 8)
        } else {
            guestRead16(addr)
        }
    }

    internal fun memOp(dest: Int, op: Int, src: Int, writeBack: Boolean = true) {
        if (dest < 0 || dest >= RAM_SIZE) return
        if (src < 0 || src >= RAM_SIZE) return
        if (pendingException >= 0) return

        // MOV is write-only on real silicon. Reading dest first would reload VGA
        // latches from the destination and break write-mode-1 copies
        // (e.g. mov al,[si] / mov [di],al → identity blit).
        val destVal = if (op == ALU_MOV) {
            0
        } else {
            readOperand(dest)
        }
        if (pendingException >= 0) return
        val srcVal = readOperand(src)
        if (pendingException >= 0) return

        opDest = destVal
        opSource = srcVal

        val result = when (op) {
            ALU_ADD -> destVal + srcVal
            ALU_SUB -> destVal - srcVal
            ALU_MUL -> destVal * srcVal
            ALU_AND -> destVal and srcVal
            ALU_OR -> destVal or srcVal
            ALU_XOR -> destVal xor srcVal
            ALU_MOV -> srcVal
            else -> destVal
        }

        opResult = result

        if (!writeBack) return

        writeOperand(dest, result)
    }

    // Write a literal operand value to a memory address (respecting iW width),
    // for use where the value is already computed rather than needing to be
    // read from another memory address (unlike memOp's "src" parameter).
    internal fun writeOperand(addr: Int, value: Int) {
        if (addr < 0 || addr >= RAM_SIZE) return
        if (pendingException >= 0) return
        if (!iW) {
            guestWrite8(addr, value)
        } else if (rmIsMemory && addr == rmAddr) {
            if (wordOffsetFaults(rmOffset and 0xFFFF)) {
                raiseException(exceptionForWordAccess(rmSegment))
                return
            }
            guestWrite8(rmAddr, value)
            guestWrite8(rmAddress(1), value shr 8)
        } else {
            guestWrite16(addr, value)
        }
    }

    // Write a 16-bit value to two consecutive memory bytes unconditionally
    // (regardless of iW) - for sites like push/LES-LDS/RTC that always need a
    // word write rather than following the current operand-width flag.
    internal fun writeWord(addr: Int, value: Int) {
        if (addr < 0 || addr >= RAM_SIZE) return
        guestWrite16(addr, value)
    }

    // INC/DEC by a literal amount. Matches C: MEM_OP(op_from_addr, += 1 - 2*i_reg +, REGS_BASE + 2*REG_ZERO)
    // where REG_ZERO always holds 0, so op_source is always 0 here.
    internal fun applyIncDec(addr: Int, increment: Int) {
        if (addr < 0 || addr >= RAM_SIZE) return
        val destVal = if (iW) guestRead16(addr) else guestRead8(addr)
        opDest = destVal
        opSource = 0
        opResult = destVal + increment
        writeOperand(addr, opResult)
    }

    // Stack operations
    internal fun push(value: Int) {
        iW = true
        val newSp = ((regs16[REG_SP].toInt() and 0xFFFF) - 2) and 0xFFFF
        if (wordOffsetFaults(newSp)) {
            raiseException(13) // #GP — real-mode wrap fault (corpus uses 13, not #SS)
            return
        }
        regs16[REG_SP] = newSp.toShort()
        // Word writes must wrap the offset at 16 bits (SS:FFFF → SS:0000).
        writeSegmentWord(REG_SS, newSp, value)
    }
    
    internal fun pop(): Int {
        // Matches C: R_M_POP - increment SP first, then read from old location
        val oldSp = regs16[REG_SP].toInt() and 0xFFFF
        if (wordOffsetFaults(oldSp)) {
            raiseException(13) // #GP
            return 0
        }
        regs16[REG_SP] = ((oldSp + 2) and 0xFFFF).toShort()
        return readSegmentWord(REG_SS, oldSp)
    }

    internal fun raiseDivideError() {
        pendingDivideError = true
    }

    internal fun raiseException(vector: Int) {
        if (pendingException < 0) pendingException = vector
    }

    /**
     * 286 real-mode: a word access at offset FFFFh raises #GP (data) or #SS (stack).
     * 8086/8088 wrap the offset instead.
     */
    internal open fun wordOffsetFaults(offset: Int): Boolean = false

    internal open fun exceptionForWordAccess(segmentReg: Int): Int = 13 // #GP(0)
    
    // Index increment/decrement
    internal fun indexInc(regId: Int) {
        val increment = (2 * regs8[FLAG_DF].toInt() - 1) * (if (iW) 2 else 1)
        regs16[regId] = ((regs16[regId].toInt() and 0xFFFF) - increment).toShort()
    }
    
    // Interrupt handling
    // Matches C's pc_interrupt(): software INT fires unconditionally (only the
    // hardware timer/keyboard tick is gated by IF, and that's already checked by the
    // caller before invoking pcInterrupt(0xA)/pcInterrupt(7)); CF is never touched.
    internal fun pcInterrupt(intNum: Int, returnIp: Int = regIp) {
        interruptTrace?.invoke(intNum, regs16[REG_CS].toInt() and 0xFFFF, returnIp and 0xFFFF)
        // Any interrupt wakes the CPU from a HLT.
        halted = false

        // Decode like INT (0xCD) so the caller's generic post-execution IP/flags
        // update (driven by rawOpcodeId's BIOS-table entries) is a no-op regardless
        // of which opcode (INT3/INTO/a real INT imm8/a hardware tick) triggered this.
        setOpcode(0xCD)

        push(makeFlags())
        push(regs16[REG_CS].toInt() and 0xFFFF)
        push(returnIp and 0xFFFF)

        val intAddr = intNum * 4
        regIp = guestRead16(intAddr)
        regs16[REG_CS] = guestRead16(intAddr + 2).toShort()

        regs8[FLAG_IF] = 0
        regs8[FLAG_TF] = 0
        // INT delivery clears TF in the flag byte; keep the pending latch in sync
        // so the run loop does not immediately re-enter INT 1.
        trapFlag = false
    }
    
    // 8086-family reset state: CS=0xFFFF, IP=0, DS=ES=SS=0, flags cleared,
    // interrupts disabled. Execution begins at 0xFFFF0.
    fun reset() {
        regs16[REG_CS] = 0xFFFF.toShort()
        regs16[REG_DS] = 0
        regs16[REG_ES] = 0
        regs16[REG_SS] = 0
        regs16[REG_SP] = 0
        regIp = 0x0000
        for (f in FLAG_CF..FLAG_OF) regs8[f] = 0
        trapFlag = false
    }

    // True if a linear address falls inside a write-protected ROM overlay.
    internal fun isRom(addr: Int): Boolean = memoryBus.isRom(addr)
    
    /** Decode-table lookups via cached per-model arrays. */
    internal open fun xlatOpcodeFor(opcode: Int): Int =
        tblXlatOpcode[opcode and 0xFF].toInt() and 0xFF

    internal open fun xlatSubfunctionFor(opcode: Int): Int =
        tblXlatSubfunction[opcode and 0xFF].toInt() and 0xFF

    internal open fun iModSizeFor(opcode: Int): Int =
        tblIModSize[opcode and 0xFF].toInt() and 0xFF

    internal open fun stdFlagsFor(opcode: Int): Int =
        tblStdFlags[opcode and 0xFF].toInt() and 0xFF

    internal open fun baseInstSizeFor(opcode: Int): Int =
        tblBaseInstSize[opcode and 0xFF].toInt() and 0xFF

    internal open fun iWSizeFor(opcode: Int): Int =
        tblIWSize[opcode and 0xFF].toInt() and 0xFF

    internal fun setOpcode(opcode: Int) {
        val op = opcode and 0xFF
        rawOpcodeId = opcode
        xlatOpcodeId = tblXlatOpcode[op].toInt() and 0xFF
        extra = tblXlatSubfunction[op].toInt() and 0xFF
        iModSize = tblIModSize[op].toInt() and 0xFF
        setFlagsType = tblStdFlags[op].toInt() and 0xFF
    }

    /**
     * Value stored by PUSH SP. The 8086 pushes SP after the implicit decrement;
     * the 286 pushes the pre-decrement SP.
     */
    internal open fun valuePushedForSp(spBeforePush: Int): Int =
        (spBeforePush - 2) and 0xFFFF

    /** Shift/rotate count; 286 masks to five bits. */
    internal open fun maskShiftCount(count: Int): Int = count

    /** Signed DIV/IDIV quotient range. 8086 rejects -128/-32768; 286 accepts them. */
    internal open fun signedDivideQuotientFits(quotient: Long, word: Boolean): Boolean {
        val maxQ = if (word) 32767L else 127L
        return quotient in -maxQ..maxQ
    }

    /**
     * When true (8086), #DE is delivered after the normal IP advance so the pushed
     * return address is the next instruction. When false (286), IP is not advanced
     * before the fault so the pushed address is the faulting instruction.
     */
    internal open fun advanceIpBeforeDivideError(): Boolean = true

    /** IP pushed for CPU exceptions (#DE/#GP/#SS). 286 includes leading prefixes. */
    internal open fun faultReturnIp(): Int = regIp and 0xFFFF

    /**
     * Maximum instruction length in bytes (including prefixes). 286 raises #GP when
     * an instruction would exceed 10 bytes; 8086/8088 are left unrestricted here.
     */
    internal open fun maxInstructionBytes(): Int = maxInsnBytes

    /** 8086: ModR/M /6 of shifts is SETMO. 286: /6 aliases SHL/SAL. */
    internal open fun shiftReg6IsSetmo(): Boolean = true

    /**
     * 286: MOV to CS (#UD), and sreg field 4..7 (#UD). 8086 allows MOV CS and aliases 4..7.
     * [toSreg] is true for 0x8E (MOV Sreg,r/m), false for 0x8C (MOV r/m,Sreg).
     */
    internal open fun invalidMovSregField(regField: Int, toSreg: Boolean): Boolean = false

    /** 286 raises #UD for LEA with a register ModR/M form; 8086 does not. */
    internal open fun leaRegisterFormRaisesUd(): Boolean = false

    /** 286 raises #UD for LES/LDS with a register ModR/M form; 8086 does not. */
    internal open fun lesLdsRegisterFormRaisesUd(): Boolean = false

    /** 286 raises #UD for far CALL/JMP with a register ModR/M form; 8086 does not. */
    internal open fun farIndirectRegisterFormRaisesUd(): Boolean = false

    /**
     * Flags applied on AAM with immediate 0 before #DE is raised.
     * Returns (SF, ZF, value whose parity becomes PF).
     * 8086/8088: result 0. 286: SF/ZF clear; PF from AL bits 1..7.
     */
    internal open fun aamDivideErrorSzp(al: Int): Triple<Boolean, Boolean, Int> =
        Triple(false, true, 0)

    internal fun applyAamDivideErrorFlags() {
        val al = regs8[REG_AL].toInt() and 0xFF
        val (sf, zf, pfSrc) = aamDivideErrorSzp(al)
        regs8[FLAG_SF] = if (sf) 1 else 0
        regs8[FLAG_ZF] = if (zf) 1 else 0
        regs8[FLAG_PF] = parityFlag(pfSrc).toByte()
        setCF(false)
        setOF(false)
    }

    fun peekInstructionLengthAtCsIp(): Int = decoder.peekInstructionLengthAtCsIp()

    // Dispatch translated opcode (handlers live in Xlat_*.kt extensions).
    internal fun executeXlatOpcode(iReg4bit: Int) {
        when (xlatOpcodeId) {
            0 -> executeXlat0()
            1 -> executeXlat1(iReg4bit)
            2 -> executeXlat2(iReg4bit)
            3 -> executeXlat3(iReg4bit)
            4 -> executeXlat4(iReg4bit)
            5 -> executeXlat5()
            6 -> executeXlat6()
            7 -> executeXlat7()
            8 -> executeXlat8()
            9 -> executeXlat9()
            10 -> executeXlat10()
            11 -> executeXlat11()
            12 -> executeXlat12()
            13 -> executeXlat13(iReg4bit)
            14 -> executeXlat14()
            15 -> executeXlat15()
            16 -> executeXlat16(iReg4bit)
            17 -> executeXlat17()
            18 -> executeXlat18()
            19 -> executeXlat19()
            20 -> executeXlat20()
            21 -> executeXlat21()
            22 -> executeXlat22()
            23 -> executeXlat23()
            24 -> executeXlat24()
            25 -> executeXlat25()
            26 -> executeXlat26()
            27 -> executeXlat27()
            28 -> executeXlat28()
            29 -> executeXlat29()
            30 -> executeXlat30()
            31 -> executeXlat31()
            32 -> executeXlat32()
            33 -> executeXlat33()
            34 -> executeXlat34()
            35 -> executeXlat35()
            36 -> executeXlat36()
            37 -> executeXlat37()
            38 -> executeXlat38()
            39 -> executeXlat39()
            40 -> executeXlat40()
            41 -> executeXlat41()
            42 -> executeXlat42()
            43 -> executeXlat43()
            44 -> executeXlat44()
            45 -> executeXlat45()
            46 -> executeXlat46()
            47 -> executeXlat47()
            48 -> executeXlat48()
            49 -> executeXlat49()
            50 -> executeXlat50()
            51 -> executeXlat51()
            52 -> executeXlat52()
            53 -> executeXlat53()
            54 -> executeXlat54()
            55 -> executeXlat55()
            56 -> executeXlat56()
            57 -> executeXlat57()
            else -> executeXlatUnhandled()
        }
    }


    /** 286 multi-byte 0x0F escape; default is unused (808x keeps POP CS via xlat 26). */
    internal open fun execute0FEscape() {}

    /**
     * DAA/DAS. 8086 uses silicon-specific predicates around 0x9A..0x9F; 286+ use
     * the architectural `old_AL > 0x99 || old_CF` high-digit rule.
     */
    internal open fun executeDaaDas(das: Boolean) {
        iW = false
        val al = regs8[REG_AL].toUByte().toInt()
        val lowNibble = al and 0x0F
        val af = regs8[FLAG_AF].toInt() != 0
        val oldCf = regs8[FLAG_CF].toInt() != 0
        var result = al
        if (das) {
            val lowAdjusted = lowNibble > 9 || af
            if (lowAdjusted) {
                result = (al - 6) and 0xFF
                setAF(true)
            } else if (clearAfWhenDaaDasSkipsLowAdjust()) {
                setAF(false)
            }
            val needHigh = daaDasNeedHigh(
                das = true, al = al, result = result,
                lowNibble = lowNibble, af = af, oldCf = oldCf,
            )
            if (needHigh) {
                result = (result - 0x60) and 0xFF
            }
            setCF(needHigh || (lowAdjusted && dasLowAdjustBorrows(al)))
        } else {
            val lowAdjusted = lowNibble > 9 || af
            if (lowAdjusted) {
                result = (al + 6) and 0xFF
                setAF(true)
            } else if (clearAfWhenDaaDasSkipsLowAdjust()) {
                setAF(false)
            }
            val needHigh = daaDasNeedHigh(
                das = false, al = al, result = result,
                lowNibble = lowNibble, af = af, oldCf = oldCf,
            )
            if (needHigh) {
                result = (result + 0x60) and 0xFF
            }
            setCF(needHigh)
        }
        regs8[REG_AL] = (result and 0xFF).toByte()
        opResult = result
    }

    /** 8086-specific high-digit DAA/DAS predicate (see SingleStep 8086 vectors). */
    internal open fun daaDasNeedHigh(
        das: Boolean,
        al: Int,
        result: Int,
        lowNibble: Int,
        af: Boolean,
        oldCf: Boolean,
    ): Boolean =
        if (das) {
            oldCf || al > 0x9F || (al > 0x99 && lowNibble > 9 && !af)
        } else {
            oldCf || al > 0x9F || (result > 0x9F && lowNibble > 9 && !af)
        }

    internal open fun clearAfWhenDaaDasSkipsLowAdjust(): Boolean = false

    /** 286 DAS: subtracting 6 from AL sets CF when AL < 6. */
    internal open fun dasLowAdjustBorrows(al: Int): Boolean = false


    // Execute a single instruction (also used by tests via the same entry point).
    fun executeSingleInstruction(): Boolean = decoder.decodeAndExecuteInstruction()

    // Cross-package register/memory surface (Machine, storage, video, tests).
    fun getReg8(index: Int): Int = regs8[index].toUByte().toInt()
    fun getReg16(index: Int): Int = regs16[index].toInt() and 0xFFFF
    fun getIp(): Int = regIp and 0xFFFF
    fun getFlags(): Int = makeFlags()
    fun getMem(addr: Int): Int = if (addr in 0 until RAM_SIZE) guestRead8(addr) else 0
    fun setMem(addr: Int, value: Int) {
        if (addr in 0 until RAM_SIZE) guestWrite8(addr, value)
    }
    fun setReg8(index: Int, value: Int) {
        regs8[index] = (value and 0xFF).toByte()
    }
    fun setReg16(index: Int, value: Int) {
        regs16[index] = (value and 0xFFFF).toShort()
    }
    fun setIp(value: Int) {
        regIp = value and 0xFFFF
    }
    fun setFlagsValue(value: Int) {
        setFlags(value and 0xFFFF)
        updateTrapPending()
    }
}
