package com.trugath.k8086.cpu

import com.trugath.k8086.api.CpuModel

/**
 * Intel 80386 CPU emulator (real-address mode).
 *
 * Extends the 80286 real-mode core with 32-bit GPRs, FS/GS, a 32-bit physical
 * address bus, 66h/67h size prefixes, and the real-mode 0x0F application /
 * system instructions. Protected mode, paging, and V86 are not enabled; CR0.PE
 * may be set via LMSW/MOV CR0 while segment arithmetic stays real-mode.
 */
internal class Emulator80386 : Emulator80286(DecodeProfiles.I80386) {
    override val model: CpuModel = CpuModel.I80386

    private val i386 = I80386Extensions(this)

    init {
        physicalAddressMask = -1 // 32-bit bus (0xFFFFFFFF)
        i386.resetControlRegisters()
    }

    /** Full CR0 image (SMSW/LMSW touch the low 16 bits). */
    var cr0: Int
        get() = i386.cr0
        set(value) {
            i386.cr0 = value
        }

    /** CR3 image (real-mode tests / MOV CRn); paging is not enabled. */
    var cr3: Int
        get() = i386.cr3
        set(value) {
            i386.cr3 = value
        }

    /**
     * Keep [machineStatusWord] in sync with CR0[15:0] so existing 286-facing
     * helpers see the same low word SMSW would return.
     */
    override var machineStatusWord: Int
        get() = i386.cr0 and 0xFFFF
        set(value) {
            i386.cr0 = (i386.cr0 and 0xFFFF.inv()) or (value and 0xFFFF)
        }

    override fun dwordOffsetFaults(offset: Int): Boolean = (offset and 0xFFFF) > 0xFFFC

    /** 386EX: string wrap #GP/#SS does not advance SI/DI or consume CX. */
    override fun stringWrapFaultCommitsSideEffects(): Boolean = false

    /** 386 real-mode stack wrap is #SS; data-segment wrap stays #GP. */
    override fun exceptionForWordAccess(segmentReg: Int): Int =
        if (segmentReg == REG_SS) 12 else 13

    override fun invalidMovSregField(regField: Int, toSreg: Boolean): Boolean =
        regField > 5 || (toSreg && regField == 1) // FS/GS ok; MOV CS,#UD

    override fun sregFromModrmField(regField: Int): Int = when (regField) {
        0 -> REG_ES
        1 -> REG_CS
        2 -> REG_SS
        3 -> REG_DS
        4 -> REG_FS
        5 -> REG_GS
        else -> REG_ES
    }

    override fun execute0FEscape() = i386.execute0FEscape()

    /**
     * Real-mode 386 POPF/POPFD may update IOPL/NT (unlike the 286, which forces
     * them clear). Retain all bits above OF (including AC/RF/VM and the
     * reserved-high pattern SingleStepTests vectors round-trip) so EFLAGS
     * survives make/setFlags for hardware vectors and PUSHFD/POPFD probes.
     */
    override fun flagsExtraMask(): Int = -0x1000 // 0xFFFFF000 — bits 12..31

    /**
     * 386EX EFLAGS[31:18] read as ones and [17:16] as zero (SST FFFCxxxx),
     * plus the architecturally-1 bit 1. PUSHFD still stores only the low 16
     * bits zero-extended; POPFD must not clear this reserved high pattern when
     * the stack dword's high half is 0 (669D.MOO).
     */
    override fun flagsReservedOnes(): Int = 0xFFFC0002u.toInt()

    override fun popfValueMask(): Int = -1

    /**
     * LOCK is legal only with memory-updating forms of ADD/ADC/AND/BTC/BTR/BTS/
     * DEC/INC/NEG/NOT/OR/SBB/SUB/XOR/XCHG (Intel 386 SDM). Anything else → #UD.
     * Called with ModR/M already decoded when [iModSize] != 0.
     */
    override fun lockPrefixRaisesUd(opcode: Int): Boolean {
        val op = opcode and 0xFF
        val mem = iModSize != 0 && iMod != 3
        return when (op) {
            // XCHG r,r/m / r/m,r — LOCK ok only when one operand is memory
            0x86, 0x87 -> !mem
            // ALU mem,reg / mem,imm (d-bit clear → memory destination)
            in 0x00..0x03, in 0x08..0x0B, in 0x10..0x13, in 0x18..0x1B,
            in 0x20..0x23, in 0x28..0x2B, in 0x30..0x33 -> {
                // opcodes xx0 / xx1 are r/m <- r; xx2 / xx3 are r <- r/m (not lockable)
                val d = (op shr 1) and 1
                d != 0 || !mem
            }
            // ALU r/m, imm8/16/32 — CMP (/7) is read-only → #UD with LOCK
            0x80, 0x81, 0x82, 0x83 -> !mem || iReg == 7
            // NOT/NEG (F6/F7 /2,/3) and INC/DEC (FE/FF /0,/1) — memory only
            0xF6, 0xF7 -> !mem || (iReg != 2 && iReg != 3)
            0xFE, 0xFF -> !mem || (iReg != 0 && iReg != 1)
            // 0F bit-test forms handled inside execute0FEscape; primary opcode 0F
            // with LOCK is rejected here and re-checked for BA/B3/BB/AB if needed.
            0x0F -> !i386.lock0FAllowed()
            else -> true // PUSH/POP/MOV/jcc/... — always #UD with LOCK
        }
    }

    override fun reset() {
        super.reset()
        // 386 power-on zeros the GPRs (808x left them undefined; we keep that).
        for (r in REG_AX..REG_DI) {
            regs16[r] = 0
            gprHi[r] = 0
        }
        i386.resetControlRegisters()
    }

    companion object {
        const val CR0_PE = 0x00000001
        const val CR0_MP = 0x00000002
        const val CR0_EM = 0x00000004
        const val CR0_TS = 0x00000008
        const val CR0_ET = 0x00000010
        const val CR0_PG = 0x80000000.toInt()
        /** Power-on CR0: ET set, PE/MP/EM/TS/PG clear (Intel 386 reset). */
        const val CR0_RESET = 0x60000010
    }
}
