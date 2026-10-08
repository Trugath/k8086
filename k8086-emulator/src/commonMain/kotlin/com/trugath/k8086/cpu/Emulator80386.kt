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
        internal set(value) {
            i386.cr0 = value
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
