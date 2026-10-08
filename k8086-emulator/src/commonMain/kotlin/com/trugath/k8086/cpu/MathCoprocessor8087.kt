package com.trugath.k8086.cpu

import com.trugath.k8086.cpu.fpu.EscDecodeTables
import com.trugath.k8086.cpu.fpu.EscDecoded
import com.trugath.k8086.cpu.fpu.Ext80
import com.trugath.k8086.cpu.fpu.Ext80Math
import com.trugath.k8086.cpu.fpu.Ext80Transcendentals
import com.trugath.k8086.cpu.fpu.FpuFormats
import com.trugath.k8086.cpu.fpu.FpuOp

/**
 * Software Intel 8087 core.
 *
 * ESC instructions execute synchronously during the 8088 step (WAIT needs no BUSY#
 * stall for correct results). Values use explicit 80-bit [Ext80] storage;
 * transcendentals are evaluated in Ext80/BigDecimal space.
 */
internal class MathCoprocessor8087 {
    /**
     * Host memory/register callbacks for ESC execution.
     *
     * Lambdas are fixed at construction; [instructionPointer]/[dataCs] are
     * mutable so a single instance can be reused on the hot path.
     */
    class Access(
        val readByte: (Int) -> Int,
        val writeByte: (Int, Int) -> Unit,
        val writeAx: (Int) -> Unit = {},
    ) {
        /** Optional CS:IP of the ESC instruction for FSTENV. */
        var instructionPointer: Int = 0
        var instructionCs: Int = 0
        /** Optional data pointer for memory operands. */
        var dataPointer: Int = 0
        var dataCs: Int = 0
    }

    private val registers = Array(8) { Ext80.ZERO }
    private val tags = IntArray(8) { Ext80.TAG_EMPTY }
    private var top = 0

    var controlWord: Int = DEFAULT_CONTROL_WORD
        private set
    var statusWord: Int = 0
        private set

    /** Last instruction pointer / opcode / data pointer (real-mode FSTENV fields). */
    var fip: Int = 0
        private set
    var fcs: Int = 0
        private set
    var fop: Int = 0
        private set
    var fdp: Int = 0
        private set
    var fds: Int = 0
        private set

    val exceptionPending: Boolean
        get() = (statusWord and ES) != 0

    var onUnmaskedException: (() -> Unit)? = null

    val tagWord: Int
        get() {
            var word = 0
            for (i in 0..7) word = word or ((tags[i] and 3) shl (i * 2))
            return word
        }

    private val rc: Int get() = (controlWord ushr 10) and 3
    private val pc: Int get() = (controlWord ushr 8) and 3

    fun reset() {
        controlWord = DEFAULT_CONTROL_WORD
        statusWord = 0
        top = 0
        registers.fill(Ext80.ZERO)
        tags.fill(Ext80.TAG_EMPTY)
        fip = 0
        fcs = 0
        fop = 0
        fdp = 0
        fds = 0
        syncTop()
    }

    /** Logical ST(i) as Double for tests/debugging. */
    fun st(index: Int): Double? {
        require(index in 0..7)
        val physical = physical(index)
        return if (tags[physical] == Ext80.TAG_EMPTY) null else registers[physical].toDouble()
    }

    fun stExt(index: Int): Ext80? {
        require(index in 0..7)
        val physical = physical(index)
        return if (tags[physical] == Ext80.TAG_EMPTY) null else registers[physical]
    }

    fun executeEsc(
        opcode: Int,
        iMod: Int,
        iReg: Int,
        iRm: Int,
        rmAddr: Int,
        access: Access,
    ) {
        val opByte = opcode and 0xFF
        clearCondition(C1)
        val decoded = EscDecodeTables.decode(opByte, iMod, iReg, iRm)
        // FIP/FDP track the last non-control instruction (Intel numerics model).
        if (!isControlOp(decoded.op)) {
            fop = ((opByte and 7) shl 8) or ((iMod and 3) shl 6) or ((iReg and 7) shl 3) or (iRm and 7)
            fip = access.instructionPointer and 0xFFFF
            fcs = access.instructionCs and 0xFFFF
            if (iMod != 3) {
                fdp = rmAddr and 0xFFFF
                fds = access.dataCs and 0xFFFF
            }
        }
        dispatch(decoded, rmAddr, access)
        syncTop()
    }

    private fun isControlOp(op: FpuOp): Boolean = when (op) {
        FpuOp.FLDENV, FpuOp.FLDCW, FpuOp.FSTENV, FpuOp.FSTCW,
        FpuOp.FRSTOR, FpuOp.FSAVE, FpuOp.FSTSW, FpuOp.FSTSW_AX,
        FpuOp.FENI, FpuOp.FDISI, FpuOp.FCLEX, FpuOp.FINIT, FpuOp.FNOP,
        -> true
        else -> false
    }

    fun executeEsc(
        opcode: Int,
        iMod: Int,
        iReg: Int,
        iRm: Int,
        rmAddr: Int,
        writeWord: (Int, Int) -> Unit,
    ) {
        var lowAddress = -1
        var lowByte = 0
        executeEsc(
            opcode,
            iMod,
            iReg,
            iRm,
            rmAddr,
            Access(
                readByte = { 0 },
                writeByte = { addr, value ->
                    if (lowAddress < 0) {
                        lowAddress = addr
                        lowByte = value and 0xFF
                    } else {
                        writeWord(lowAddress, lowByte or ((value and 0xFF) shl 8))
                        lowAddress = -1
                    }
                },
            ),
        )
    }

    private fun dispatch(d: EscDecoded, rmAddr: Int, a: Access) {
        when (d.op) {
            FpuOp.UNIMPLEMENTED -> setException(IE)

            FpuOp.FADD, FpuOp.FIADD -> arith(d, rmAddr, a, Ext80Math::add, reverse = false, pop = false)
            FpuOp.FMUL, FpuOp.FIMUL -> arith(d, rmAddr, a, Ext80Math::mul, reverse = false, pop = false)
            FpuOp.FSUB, FpuOp.FISUB -> arith(d, rmAddr, a, Ext80Math::sub, reverse = false, pop = false)
            FpuOp.FSUBR, FpuOp.FISUBR -> arith(d, rmAddr, a, Ext80Math::sub, reverse = true, pop = false)
            FpuOp.FDIV, FpuOp.FIDIV -> arith(d, rmAddr, a, Ext80Math::div, reverse = false, pop = false)
            FpuOp.FDIVR, FpuOp.FIDIVR -> arith(d, rmAddr, a, Ext80Math::div, reverse = true, pop = false)
            FpuOp.FADDP -> arith(d, rmAddr, a, Ext80Math::add, reverse = false, pop = true)
            FpuOp.FMULP -> arith(d, rmAddr, a, Ext80Math::mul, reverse = false, pop = true)
            FpuOp.FSUBP -> arith(d, rmAddr, a, Ext80Math::sub, reverse = false, pop = true)
            FpuOp.FSUBRP -> arith(d, rmAddr, a, Ext80Math::sub, reverse = true, pop = true)
            FpuOp.FDIVP -> arith(d, rmAddr, a, Ext80Math::div, reverse = false, pop = true)
            FpuOp.FDIVRP -> arith(d, rmAddr, a, Ext80Math::div, reverse = true, pop = true)

            FpuOp.FCOM, FpuOp.FICOM -> {
                compare(value(0), memOrSt(d, rmAddr, a))
            }
            FpuOp.FCOMP, FpuOp.FICOMP -> {
                compare(value(0), memOrSt(d, rmAddr, a))
                pop()
            }
            FpuOp.FCOMPP -> {
                compare(value(0), value(1))
                pop(); pop()
            }
            FpuOp.FUCOM -> compare(value(0), value(d.index))
            FpuOp.FUCOMP -> {
                compare(value(0), value(d.index))
                pop()
            }
            FpuOp.FUCOMPP -> {
                compare(value(0), value(1))
                pop(); pop()
            }

            FpuOp.FLD_M32 -> push(FpuFormats.readFloat32(rmAddr, a.readByte))
            FpuOp.FST_M32 -> FpuFormats.writeFloat32(rmAddr, value(0), a.writeByte)
            FpuOp.FSTP_M32 -> {
                FpuFormats.writeFloat32(rmAddr, value(0), a.writeByte)
                pop()
            }
            FpuOp.FLD_M64 -> push(FpuFormats.readFloat64(rmAddr, a.readByte))
            FpuOp.FST_M64 -> FpuFormats.writeFloat64(rmAddr, value(0), a.writeByte)
            FpuOp.FSTP_M64 -> {
                FpuFormats.writeFloat64(rmAddr, value(0), a.writeByte)
                pop()
            }
            FpuOp.FLD_M80 -> push(FpuFormats.readFloat80(rmAddr, a.readByte))
            FpuOp.FSTP_M80 -> {
                FpuFormats.writeFloat80(rmAddr, value(0), a.writeByte)
                pop()
            }
            FpuOp.FILD_M16 -> push(FpuFormats.readI16(rmAddr, a.readByte))
            FpuOp.FILD_M32 -> push(FpuFormats.readI32(rmAddr, a.readByte))
            FpuOp.FILD_M64 -> push(FpuFormats.readI64(rmAddr, a.readByte))
            FpuOp.FIST_M16 -> storeInteger(rmAddr, 2, pop = false, a)
            FpuOp.FISTP_M16 -> storeInteger(rmAddr, 2, pop = true, a)
            FpuOp.FIST_M32 -> storeInteger(rmAddr, 4, pop = false, a)
            FpuOp.FISTP_M32 -> storeInteger(rmAddr, 4, pop = true, a)
            FpuOp.FISTP_M64 -> storeInteger(rmAddr, 8, pop = true, a)
            FpuOp.FBLD -> push(FpuFormats.readPackedBcd(rmAddr, a.readByte))
            FpuOp.FBSTP -> {
                FpuFormats.writePackedBcd(rmAddr, value(0), rc, a.writeByte)
                pop()
            }

            FpuOp.FLD_ST -> push(value(d.index))
            FpuOp.FST_ST -> storeTo(d.index, value(0))
            FpuOp.FSTP_ST -> {
                storeTo(d.index, value(0))
                pop()
            }
            FpuOp.FXCH -> exchange(d.index)
            FpuOp.FFREE -> tags[physical(d.index)] = Ext80.TAG_EMPTY

            FpuOp.FLDENV -> loadEnvironment(rmAddr, a)
            FpuOp.FLDCW -> controlWord = FpuFormats.readU16(rmAddr, a.readByte)
            FpuOp.FSTENV -> {
                storeEnvironment(rmAddr, a)
                controlWord = controlWord or EXCEPTION_MASK
            }
            FpuOp.FSTCW -> FpuFormats.writeU16(rmAddr, controlWord, a.writeByte)
            FpuOp.FRSTOR -> restoreState(rmAddr, a)
            FpuOp.FSAVE -> {
                saveState(rmAddr, a)
                reset()
            }
            FpuOp.FSTSW -> FpuFormats.writeU16(rmAddr, statusWord, a.writeByte)
            FpuOp.FSTSW_AX -> a.writeAx(statusWord)
            FpuOp.FENI, FpuOp.FDISI, FpuOp.FNOP -> Unit
            FpuOp.FCLEX -> clearExceptions()
            FpuOp.FINIT -> reset()
            FpuOp.FDECSTP -> top = (top - 1) and 7
            FpuOp.FINCSTP -> top = (top + 1) and 7

            FpuOp.FCHS -> set(0, value(0).negate())
            FpuOp.FABS -> set(0, value(0).abs())
            FpuOp.FTST -> compare(value(0), Ext80.ZERO)
            FpuOp.FXAM -> examine()

            FpuOp.FLD1 -> push(Ext80.ONE)
            FpuOp.FLDL2T -> push(Ext80.L2T)
            FpuOp.FLDL2E -> push(Ext80.L2E)
            FpuOp.FLDPI -> push(Ext80.PI)
            FpuOp.FLDLG2 -> push(Ext80.LG2)
            FpuOp.FLDLN2 -> push(Ext80.LN2)
            FpuOp.FLDZ -> push(Ext80.ZERO)

            FpuOp.F2XM1 -> set(0, Ext80Transcendentals.f2xm1(value(0), rc, pc))
            FpuOp.FYL2X -> {
                val result = Ext80Transcendentals.fyl2x(value(1), value(0), rc, pc)
                pop()
                set(0, result)
            }
            FpuOp.FYL2XP1 -> {
                val result = Ext80Transcendentals.fyl2xp1(value(1), value(0), rc, pc)
                pop()
                set(0, result)
            }
            FpuOp.FPTAN -> {
                val (tan, one) = Ext80Transcendentals.fptan(value(0), rc, pc)
                set(0, tan)
                push(one)
            }
            FpuOp.FPATAN -> {
                val result = Ext80Transcendentals.fpatan(value(0), value(1), rc, pc)
                pop()
                set(0, result)
            }
            FpuOp.FXTRACT -> {
                val x = value(0)
                val expo = x.extractExponent()
                set(0, x.extractSignificand())
                push(expo)
            }
            FpuOp.FPREM -> remainder(nearest = false)
            FpuOp.FPREM1 -> remainder(nearest = true)
            FpuOp.FSQRT -> applyResult(Ext80Math.sqrt(value(0), rc, pc))
            FpuOp.FSINCOS -> {
                val x = value(0)
                set(0, Ext80Transcendentals.fsin(x, rc, pc))
                push(Ext80Transcendentals.fcos(x, rc, pc))
            }
            FpuOp.FRNDINT -> set(0, value(0).roundToIntegral(rc))
            FpuOp.FSCALE -> {
                val n = value(1).toIntegralLong(Ext80.RC_CHOP)?.toInt() ?: value(1).toDouble().toInt()
                applyResult(Ext80Math.scale(value(0), n))
            }
            FpuOp.FSIN -> set(0, Ext80Transcendentals.fsin(value(0), rc, pc))
            FpuOp.FCOS -> set(0, Ext80Transcendentals.fcos(value(0), rc, pc))
        }
    }

    private fun memOrSt(d: EscDecoded, rmAddr: Int, a: Access): Ext80 {
        if (d.memBytes == 0) return value(d.index)
        return when (d.op) {
            FpuOp.FIADD, FpuOp.FIMUL, FpuOp.FICOM, FpuOp.FICOMP,
            FpuOp.FISUB, FpuOp.FISUBR, FpuOp.FIDIV, FpuOp.FIDIVR,
            -> when (d.memBytes) {
                2 -> FpuFormats.readI16(rmAddr, a.readByte)
                4 -> FpuFormats.readI32(rmAddr, a.readByte)
                else -> FpuFormats.readI16(rmAddr, a.readByte)
            }
            else -> when (d.memBytes) {
                4 -> FpuFormats.readFloat32(rmAddr, a.readByte)
                8 -> FpuFormats.readFloat64(rmAddr, a.readByte)
                else -> FpuFormats.readFloat32(rmAddr, a.readByte)
            }
        }
    }

    private fun arith(
        d: EscDecoded,
        rmAddr: Int,
        a: Access,
        op: (Ext80, Ext80, Int, Int) -> Ext80Math.Result,
        reverse: Boolean,
        pop: Boolean,
    ) {
        val operand = if (d.memBytes != 0) memOrSt(d, rmAddr, a) else value(d.index)
        val left: Ext80
        val right: Ext80
        val dest: Int
        if (d.destIsSt0 || d.memBytes != 0) {
            left = value(0)
            right = operand
            dest = 0
        } else {
            left = value(d.index)
            right = value(0)
            dest = d.index
        }
        val result = if (reverse) op(right, left, rc, pc) else op(left, right, rc, pc)
        applyResult(result, dest)
        if (pop) pop()
    }

    private fun applyResult(result: Ext80Math.Result, dest: Int = 0) {
        if (result.exceptions != 0) setException(result.exceptions)
        if (result.c1) setCondition(C1) else clearCondition(C1)
        set(dest, result.value)
    }

    private fun remainder(nearest: Boolean) {
        val dividend = value(0)
        val divisor = value(1)
        clearCondition(C0 or C1 or C2 or C3)
        val rem = Ext80Math.remainder(dividend, divisor, nearest, rc, pc)
        if (rem.exceptions != 0) setException(rem.exceptions)
        set(0, rem.value)
        if (rem.incomplete) {
            setCondition(C2)
            return
        }
        // Intel: C0←Q2, C3←Q1, C1←Q0
        val low = rem.quotientLow and 7
        if ((low and 4) != 0) setCondition(C0)
        if ((low and 2) != 0) setCondition(C3)
        if ((low and 1) != 0) setCondition(C1)
    }

    private fun compare(left: Ext80, right: Ext80) {
        clearCondition(C0 or C2 or C3)
        when (left.compareTo(right)) {
            Ext80.CMP_UNORDERED -> {
                setCondition(C0 or C2 or C3)
                setException(IE)
            }
            1 -> Unit
            -1 -> setCondition(C0)
            else -> setCondition(C3)
        }
    }

    private fun examine() {
        clearCondition(C0 or C2 or C3)
        val physical = physical(0)
        if (tags[physical] == Ext80.TAG_EMPTY) {
            setCondition(C0 or C3)
            return
        }
        val x = registers[physical]
        if (x.sign) setCondition(C1)
        when {
            x.isNaN -> setCondition(C0)
            x.isInfinity -> setCondition(C0 or C2)
            x.isZero -> setCondition(C3)
            else -> setCondition(C2)
        }
    }

    private fun push(value: Ext80) {
        val next = (top - 1) and 7
        if (tags[next] != Ext80.TAG_EMPTY) {
            setException(IE or SF)
            setCondition(C1)
            return
        }
        top = next
        registers[top] = value
        tags[top] = value.tag()
        if (value.isDenormal) setException(DE)
    }

    private fun pop(): Ext80 {
        if (tags[top] == Ext80.TAG_EMPTY) {
            setException(IE or SF)
            clearCondition(C1)
            return Ext80.INDEFINITE
        }
        val result = registers[top]
        tags[top] = Ext80.TAG_EMPTY
        top = (top + 1) and 7
        return result
    }

    private fun value(index: Int): Ext80 {
        val p = physical(index)
        if (tags[p] == Ext80.TAG_EMPTY) {
            setException(IE or SF)
            return Ext80.INDEFINITE
        }
        val v = registers[p]
        if (v.isDenormal) setException(DE)
        return v
    }

    private fun set(index: Int, value: Ext80) {
        val p = physical(index)
        registers[p] = value
        tags[p] = value.tag()
    }

    private fun storeTo(index: Int, value: Ext80) = set(index, value)

    private fun exchange(index: Int) {
        val p0 = physical(0)
        val pi = physical(index)
        val topEmpty = tags[p0] == Ext80.TAG_EMPTY
        val otherEmpty = tags[pi] == Ext80.TAG_EMPTY
        if (topEmpty || otherEmpty) {
            setException(IE)
            if ((controlWord and IE) == 0) return
        }
        val topValue = if (topEmpty) Ext80.INDEFINITE else registers[p0]
        val otherValue = if (otherEmpty) Ext80.INDEFINITE else registers[pi]
        registers[p0] = otherValue
        registers[pi] = topValue
        tags[p0] = otherValue.tag()
        tags[pi] = topValue.tag()
    }

    private fun physical(logical: Int): Int = (top + logical) and 7

    private fun clearExceptions() {
        statusWord = statusWord and 0x7F00
    }

    private fun setException(bits: Int) {
        statusWord = statusWord or bits
        val exceptionBits = bits and 0x3F
        if ((exceptionBits and controlWord.inv()) != 0) {
            val notify = (statusWord and ES) == 0
            statusWord = statusWord or ES or BUSY
            if (notify) onUnmaskedException?.invoke()
        }
    }

    private fun syncTop() {
        statusWord = (statusWord and TOP_MASK.inv()) or ((top and 7) shl 11)
    }

    private fun setCondition(bits: Int) {
        statusWord = statusWord or bits
    }

    private fun clearCondition(bits: Int) {
        statusWord = statusWord and bits.inv()
    }

    private fun storeInteger(addr: Int, bytes: Int, pop: Boolean, a: Access) {
        val x = value(0)
        val asLong = x.toIntegralLong(rc)?.let { v ->
            val ok = when (bytes) {
                2 -> v in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
                4 -> v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                else -> true // toIntegralLong already enforces i64 range
            }
            if (ok) v else null
        }
        val result = asLong ?: run {
            setException(IE)
            when (bytes) {
                2 -> 0x8000L
                4 -> 0x80000000L
                else -> Long.MIN_VALUE
            }
        }
        FpuFormats.writeInteger(addr, result, bytes, a.writeByte)
        if (pop) pop()
    }

    private fun storeEnvironment(addr: Int, a: Access) {
        // Real-mode 14-byte layout: CW, SW, TW, IP, CS|opcode nibble, DP, DS.
        FpuFormats.writeU16(addr, controlWord, a.writeByte)
        FpuFormats.writeU16(addr + 2, statusWord, a.writeByte)
        FpuFormats.writeU16(addr + 4, tagWord, a.writeByte)
        FpuFormats.writeU16(addr + 6, fip, a.writeByte)
        FpuFormats.writeU16(addr + 8, (fcs and 0x0FFF) or ((fop and 0xF) shl 12), a.writeByte)
        FpuFormats.writeU16(addr + 10, fdp, a.writeByte)
        FpuFormats.writeU16(addr + 12, fds, a.writeByte)
    }

    private fun loadEnvironment(addr: Int, a: Access) {
        controlWord = FpuFormats.readU16(addr, a.readByte)
        statusWord = FpuFormats.readU16(addr + 2, a.readByte)
        loadTagWord(FpuFormats.readU16(addr + 4, a.readByte))
        fip = FpuFormats.readU16(addr + 6, a.readByte)
        val csOp = FpuFormats.readU16(addr + 8, a.readByte)
        fcs = csOp and 0x0FFF
        fop = (fop and 0x7F0) or ((csOp ushr 12) and 0xF)
        fdp = FpuFormats.readU16(addr + 10, a.readByte)
        fds = FpuFormats.readU16(addr + 12, a.readByte)
        top = (statusWord ushr 11) and 7
        syncTop()
    }

    private fun saveState(addr: Int, a: Access) {
        storeEnvironment(addr, a)
        var p = addr + ENV_SIZE
        for (logical in 0..7) {
            FpuFormats.writeFloat80(p, valueOrZero(logical), a.writeByte)
            p += 10
        }
    }

    private fun restoreState(addr: Int, a: Access) {
        loadEnvironment(addr, a)
        var p = addr + ENV_SIZE
        for (logical in 0..7) {
            val physical = physical(logical)
            registers[physical] = FpuFormats.readFloat80(p, a.readByte)
            // Prefer stored tag word; refresh if valid-looking.
            if (tags[physical] != Ext80.TAG_EMPTY) {
                tags[physical] = registers[physical].tag()
            }
            p += 10
        }
    }

    private fun loadTagWord(word: Int) {
        for (i in 0..7) tags[i] = (word ushr (i * 2)) and 3
    }

    private fun valueOrZero(index: Int): Ext80 {
        val p = physical(index)
        return if (tags[p] == Ext80.TAG_EMPTY) Ext80.ZERO else registers[p]
    }

    companion object {
        private const val DEFAULT_CONTROL_WORD = 0x037F
        private const val EXCEPTION_MASK = 0x003F

        private const val IE = 0x0001
        private const val DE = 0x0002
        private const val SF = 0x0040
        private const val ES = 0x0080
        private const val C0 = 0x0100
        private const val C1 = 0x0200
        private const val C2 = 0x0400
        private const val TOP_MASK = 0x3800
        private const val C3 = 0x4000
        private const val BUSY = 0x8000

        private const val ENV_SIZE = 14
    }
}
