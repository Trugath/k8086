package com.trugath.k8086.cpu

import com.trugath.k8086.api.CpuModel
/**
 * Intel 80286 CPU emulator (real-mode first).
 *
 * Shares the 8086 instruction engine, remaps 80186/286 opcodes via
 * [DecodeProfiles.I80286], and delegates the real-mode 0x0F escape to
 * [I80286Extensions]. Protected-mode addressing is not enabled yet; LMSW may
 * set PE in the MSW image while segment arithmetic stays real-mode.
 */
internal class Emulator80286 : Emulator8086(DecodeProfiles.I80286) {
    override val model: CpuModel = CpuModel.I80286

    private val extensions = I80286Extensions(this)

    init {
        physicalAddressMask = 0xFFFFFF
    }

    /** Machine status word (CR0 low bits). Reset value matches 286 power-on. */
    var machineStatusWord: Int
        get() = extensions.machineStatusWord
        private set(value) { extensions.machineStatusWord = value }

    override fun flagsReservedOnes(): Int = 0x0002

    override fun flagsExtraMask(): Int = 0xF000 // IOPL, NT, and bit 15

    override fun wordOffsetFaults(offset: Int): Boolean = (offset and 0xFFFF) == 0xFFFF

    override fun overflowAfterShiftRotate(
        operation: Int,
        count: Int,
        originalValue: Int,
        result: Int,
        carry: Boolean,
        signMask: Int,
    ): Int =
        definedShiftRotateOverflow(operation, count, originalValue, result, carry, signMask)

    override fun valuePushedForSp(spBeforePush: Int): Int = spBeforePush and 0xFFFF

    override fun maskShiftCount(count: Int): Int = (count and 0xFF) and 0x1F

    override fun signedDivideQuotientFits(quotient: Long, word: Boolean): Boolean =
        if (word) quotient in -32768L..32767L else quotient in -128L..127L

    override fun advanceIpBeforeDivideError(): Boolean = false

    override fun faultReturnIp(): Int = instructionStartIp and 0xFFFF

    override fun daaDasNeedHigh(
        das: Boolean,
        al: Int,
        result: Int,
        lowNibble: Int,
        af: Boolean,
        oldCf: Boolean,
    ): Boolean = oldCf || al > 0x99

    override fun clearAfWhenDaaDasSkipsLowAdjust(): Boolean = true

    override fun dasLowAdjustBorrows(al: Int): Boolean = al < 6

    override fun aaaAasAddsToAxWord(): Boolean = true

    override fun invalidMovSregField(regField: Int, toSreg: Boolean): Boolean =
        regField > 3 || (toSreg && regField == 1) // no FS/GS; MOV CS,#UD

    override fun leaRegisterFormRaisesUd(): Boolean = true

    override fun lesLdsRegisterFormRaisesUd(): Boolean = true

    override fun farIndirectRegisterFormRaisesUd(): Boolean = true

    override fun shiftReg6IsSetmo(): Boolean = false

    override fun aamDivideErrorSzp(al: Int): Triple<Boolean, Boolean, Int> =
        // Harris 80C286: clear SF/ZF; PF from parity of AL[7:1].
        Triple(false, false, al and 0xFE)

    override fun popfValueMask(): Int = 0x0FFF

    override fun execute0FEscape() = extensions.execute0FEscape()

    companion object {
        const val MSW_PE = 0x0001
        const val MSW_MP = 0x0002
        const val MSW_EM = 0x0004
        const val MSW_TS = 0x0008
        /** Power-on MSW: PE clear, upper bits set as on silicon. */
        const val MSW_RESET = 0xFFF0
    }
}
