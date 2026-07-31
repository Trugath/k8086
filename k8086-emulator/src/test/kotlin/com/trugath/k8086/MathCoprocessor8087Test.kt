package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.MathCoprocessor8087
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MathCoprocessor8087Test {
    private class Memory(size: Int = 4096) {
        val bytes = ByteArray(size)
        var ax = 0
        val access = MathCoprocessor8087.Access(
            readByte = { bytes[it].toInt() and 0xFF },
            writeByte = { addr, value -> bytes[addr] = value.toByte() },
            writeAx = { ax = it and 0xFFFF },
        )

        fun putFloat(addr: Int, value: Float) = putInt(addr, value.toRawBits())
        fun float(addr: Int): Float = Float.fromBits(int(addr))
        fun putDouble(addr: Int, value: Double) = putLong(addr, value.toRawBits())
        fun double(addr: Int): Double = Double.fromBits(long(addr))

        fun putInt(addr: Int, value: Int) {
            repeat(4) { bytes[addr + it] = (value ushr (it * 8)).toByte() }
        }

        fun int(addr: Int): Int {
            var value = 0
            repeat(4) { value = value or ((bytes[addr + it].toInt() and 0xFF) shl (it * 8)) }
            return value
        }

        fun putLong(addr: Int, value: Long) {
            repeat(8) { bytes[addr + it] = (value ushr (it * 8)).toByte() }
        }

        fun long(addr: Int): Long {
            var value = 0L
            repeat(8) { value = value or ((bytes[addr + it].toLong() and 0xFF) shl (it * 8)) }
            return value
        }
    }

    @Test
    fun initStackConstantsArithmeticAndPopStore() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()

        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xD8, 3, 0, 1, 0, mem.access) // FADD ST, ST(1)
        assertEquals(2.0, fpu.st(0))

        fpu.executeEsc(0xDD, 0, 3, 0, 100, mem.access) // FSTP m64real
        assertEquals(2.0, mem.double(100))
        assertEquals(1.0, fpu.st(0))

        fpu.executeEsc(0xDB, 3, 4, 3, 0, mem.access) // FNINIT
        assertEquals(0x037F, fpu.controlWord)
        assertEquals(0xFFFF, fpu.tagWord)
        assertNull(fpu.st(0))
    }

    @Test
    fun memoryFloatIntegerMultiplyDivideAndSqrt() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0, 1.5f)
        mem.putInt(16, 4)
        mem.putDouble(24, 3.0)

        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access)   // FLD m32real
        fpu.executeEsc(0xDA, 0, 1, 0, 16, mem.access)  // FIMUL m32int
        assertEquals(6.0, fpu.st(0))
        fpu.executeEsc(0xDC, 0, 6, 0, 24, mem.access)  // FDIV m64real
        assertEquals(2.0, fpu.st(0))
        fpu.executeEsc(0xD9, 3, 7, 2, 0, mem.access)   // FSQRT
        assertEquals(kotlin.math.sqrt(2.0), fpu.st(0)!!, 1e-12)

        fpu.executeEsc(0xD9, 0, 3, 0, 32, mem.access)  // FSTP m32real
        assertEquals(kotlin.math.sqrt(2.0).toFloat(), mem.float(32))
    }

    @Test
    fun compareSetsConditionCodesAndStatusCanReachAx() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0, 2.0f)
        mem.putFloat(4, 3.0f)
        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access)
        fpu.executeEsc(0xD8, 0, 2, 0, 4, mem.access) // FCOM 3; 2 < 3 => C0
        assertTrue((fpu.statusWord and 0x0100) != 0)
        assertEquals(0, fpu.statusWord and 0x4400)

        fpu.executeEsc(0xDF, 3, 4, 0, 0, mem.access) // FNSTSW AX
        assertEquals(fpu.statusWord, mem.ax)
    }

    @Test
    fun controlWordLoadStoreAndIntegerRounding() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        // Round toward zero (RC=11).
        mem.bytes[0] = 0x7F
        mem.bytes[1] = 0x0F
        fpu.executeEsc(0xD9, 0, 5, 0, 0, mem.access) // FLDCW
        assertEquals(0x0F7F, fpu.controlWord)

        fpu.executeEsc(0xD9, 0, 7, 0, 4, mem.access) // FNSTCW
        assertEquals(0x7F, mem.bytes[4].toInt() and 0xFF)
        assertEquals(0x0F, mem.bytes[5].toInt() and 0xFF)

        mem.putFloat(8, -3.75f)
        fpu.executeEsc(0xD9, 0, 0, 0, 8, mem.access)
        fpu.executeEsc(0xDB, 0, 3, 0, 12, mem.access) // FISTP m32
        assertEquals(-3, mem.int(12))
    }

    @Test
    fun extendedRealAndEnvironmentRoundTrip() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()

        fpu.executeEsc(0xD9, 3, 5, 3, 0, mem.access) // FLDPI
        fpu.executeEsc(0xDB, 0, 7, 0, 100, mem.access) // FSTP m80real
        assertNull(fpu.st(0))

        fpu.executeEsc(0xDB, 0, 5, 0, 100, mem.access) // FLD m80real
        assertEquals(Math.PI, fpu.st(0)!!, 1e-15)

        mem.bytes[0] = 0x7F
        mem.bytes[1] = 0x07 // round toward +infinity
        fpu.executeEsc(0xD9, 0, 5, 0, 0, mem.access)   // FLDCW
        fpu.executeEsc(0xD9, 0, 6, 0, 200, mem.access) // FNSTENV
        fpu.executeEsc(0xDB, 3, 4, 3, 0, mem.access)   // FNINIT
        assertEquals(0x037F, fpu.controlWord)
        fpu.executeEsc(0xD9, 0, 4, 0, 200, mem.access) // FLDENV
        assertEquals(0x077F, fpu.controlWord)
    }

    @Test
    fun registerDestinationSubtractAndDivideUseArchitecturalOperandOrder() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0, 2.0f)
        mem.putFloat(4, 10.0f)

        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access)
        fpu.executeEsc(0xD9, 0, 0, 0, 4, mem.access)
        fpu.executeEsc(0xDC, 3, 4, 1, 0, mem.access) // FSUB ST(1), ST
        assertEquals(-8.0, fpu.st(1))
        fpu.executeEsc(0xDC, 3, 5, 1, 0, mem.access) // FSUBR ST(1), ST
        assertEquals(18.0, fpu.st(1))

        fpu.reset()
        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access)
        fpu.executeEsc(0xD9, 0, 0, 0, 4, mem.access)
        fpu.executeEsc(0xDE, 3, 4, 1, 0, mem.access) // FSUBP ST(1), ST
        assertEquals(-8.0, fpu.st(0))
    }

    @Test
    fun fxchEmptyRegisterUsesMaskedIndefiniteOrAbortsWhenUnmasked() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xD9, 3, 1, 1, 0, mem.access) // FXCH ST(1)
        assertTrue(fpu.st(0)!!.isNaN())
        assertEquals(1.0, fpu.st(1))
        assertTrue((fpu.statusWord and 0x0001) != 0, "IE")

        fpu.reset()
        mem.bytes[0] = 0x7E // unmask invalid operation
        mem.bytes[1] = 0x03
        fpu.executeEsc(0xD9, 0, 5, 0, 0, mem.access) // FLDCW
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xD9, 3, 1, 1, 0, mem.access) // FXCH ST(1)
        assertEquals(1.0, fpu.st(0))
        assertNull(fpu.st(1))
        assertTrue(fpu.exceptionPending)
    }

    @Test
    fun divideByZeroAndStackFaultSetStatusFlags() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0, 1.0f)
        mem.putFloat(4, 0.0f)
        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access)
        fpu.executeEsc(0xD8, 0, 6, 0, 4, mem.access)
        assertTrue((fpu.statusWord and 0x0004) != 0, "ZE")

        fpu.reset()
        repeat(9) { fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) }
        assertTrue((fpu.statusWord and 0x0041) == 0x0041, "IE + SF")
        assertTrue((fpu.statusWord and 0x0200) != 0, "C1 on overflow")
    }

    @Test
    fun unmaskedExceptionSignalsOnceAndFnclexClearsPendingState() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        var signals = 0
        fpu.onUnmaskedException = { signals++ }
        // Default control with divide-by-zero mask (bit 2) cleared.
        mem.bytes[0] = 0x7B
        mem.bytes[1] = 0x03
        mem.putFloat(4, 1.0f)
        mem.putFloat(8, 0.0f)
        fpu.executeEsc(0xD9, 0, 5, 0, 0, mem.access) // FLDCW
        fpu.executeEsc(0xD9, 0, 0, 0, 4, mem.access)
        fpu.executeEsc(0xD8, 0, 6, 0, 8, mem.access)
        assertTrue(fpu.exceptionPending)
        assertEquals(1, signals)

        // A second sticky exception does not pulse INT again until cleared.
        fpu.executeEsc(0xD8, 0, 6, 0, 8, mem.access)
        assertEquals(1, signals)
        fpu.executeEsc(0xDB, 3, 4, 2, 0, mem.access) // FNCLEX
        assertTrue(!fpu.exceptionPending)
    }

    @Test
    fun fpremConditionCodesAndFsincosStackOrder() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0, 3.0f)
        mem.putFloat(4, 17.0f)
        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access) // divisor
        fpu.executeEsc(0xD9, 0, 0, 0, 4, mem.access) // dividend
        fpu.executeEsc(0xD9, 3, 7, 0, 0, mem.access) // FPREM, quotient 5 (101b)
        assertEquals(2.0, fpu.st(0))
        assertEquals(0x0300, fpu.statusWord and 0x4700) // C0 + C1

        fpu.reset()
        mem.putFloat(8, 0.5f)
        fpu.executeEsc(0xD9, 0, 0, 0, 8, mem.access)
        fpu.executeEsc(0xD9, 3, 7, 3, 0, mem.access) // FSINCOS
        assertEquals(kotlin.math.cos(0.5), fpu.st(0)!!, 1e-12)
        assertEquals(kotlin.math.sin(0.5), fpu.st(1)!!, 1e-12)
    }

    @Test
    fun cpuEscDispatchReadsAndWritesGuestMemoryAndWaitDoesNotHang() {
        val cpu = Emulator8086()
        cpu.mathCoprocessor = MathCoprocessor8087()
        cpu.setReg16(REG_CS, 0)
        cpu.setReg16(REG_DS, 0)
        cpu.setIp(0x200)
        putFloat(cpu, 0x500, 1.25f)
        putFloat(cpu, 0x504, 2.75f)
        cpu.writeInstruction(
            byteArrayOf(
                0xD9.toByte(), 0x06, 0x00, 0x05, // FLD dword [0500]
                0xD8.toByte(), 0x06, 0x04, 0x05, // FADD dword [0504]
                0xD9.toByte(), 0x1E, 0x08, 0x05, // FSTP dword [0508]
                0x9B.toByte(),                   // WAIT
                0xDF.toByte(), 0xE0.toByte(),    // FNSTSW AX
            ),
        )

        repeat(5) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(4.0f, readFloat(cpu, 0x508))
        assertEquals(cpu.mathCoprocessor!!.statusWord, cpu.getReg16(REG_AX))
    }

    @Test
    fun cpuEscWithoutCoprocessorRemainsNoOp() {
        val cpu = Emulator8086()
        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x200)
        cpu.writeInstruction(byteArrayOf(0xD9.toByte(), 0xE8.toByte())) // FLD1
        assertTrue(cpu.executeSingleInstruction())
        assertNull(cpu.mathCoprocessor)
    }

    @Test
    fun unimplementedEscSetsInvalidOperation() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        // DB /1 memory form is not an 8087 encoding.
        fpu.executeEsc(0xDB, 0, 1, 0, 0, mem.access)
        assertTrue((fpu.statusWord and 0x0001) != 0, "IE")
    }

    @Test
    fun fstenvRecordsInstructionAndDataPointers() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0x40, 2.0f)
        val access = MathCoprocessor8087.Access(
            readByte = mem.access.readByte,
            writeByte = mem.access.writeByte,
        ).also {
            it.instructionPointer = 0x1234
            it.instructionCs = 0x0100
            it.dataPointer = 0x40
            it.dataCs = 0x0200
        }
        fpu.executeEsc(0xD9, 0, 0, 0, 0x40, access)
        fpu.executeEsc(0xD9, 0, 6, 0, 0x100, mem.access) // FNSTENV
        assertEquals(0x1234, (mem.bytes[0x106].toInt() and 0xFF) or ((mem.bytes[0x107].toInt() and 0xFF) shl 8))
        val csOp = (mem.bytes[0x108].toInt() and 0xFF) or ((mem.bytes[0x109].toInt() and 0xFF) shl 8)
        assertEquals(0x0100, csOp and 0x0FFF)
        assertEquals(0x40, (mem.bytes[0x10A].toInt() and 0xFF) or ((mem.bytes[0x10B].toInt() and 0xFF) shl 8))
        assertEquals(0x0200, (mem.bytes[0x10C].toInt() and 0xFF) or ((mem.bytes[0x10D].toInt() and 0xFF) shl 8))
    }

    @Test
    fun packedBcdRoundTripAndFsaveRestore() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        // 123 packed BCD little-endian digits.
        mem.bytes[0] = 0x23
        mem.bytes[1] = 0x01
        fpu.executeEsc(0xDF, 0, 4, 0, 0, mem.access) // FBLD
        assertEquals(123.0, fpu.st(0))
        fpu.executeEsc(0xDF, 0, 6, 0, 20, mem.access) // FBSTP
        assertEquals(0x23, mem.bytes[20].toInt() and 0xFF)
        assertEquals(0x01, mem.bytes[21].toInt() and 0xFF)

        fpu.reset()
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xDD, 0, 6, 0, 100, mem.access) // FSAVE
        assertNull(fpu.st(0))
        fpu.executeEsc(0xDD, 0, 4, 0, 100, mem.access) // FRSTOR
        assertEquals(1.0, fpu.st(0))
    }

    @Test
    fun precisionExceptionSetOnInexactExtendedStorePath() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        // Force single precision (PC=00) so 1/3 is inexact.
        mem.bytes[0] = 0x7F
        mem.bytes[1] = 0x00 // RC=near, PC=single, masks on
        fpu.executeEsc(0xD9, 0, 5, 0, 0, mem.access) // FLDCW
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        mem.putFloat(8, 3.0f)
        fpu.executeEsc(0xD8, 0, 6, 0, 8, mem.access) // FDIV 3
        assertTrue((fpu.statusWord and 0x0020) != 0, "PE")
    }

    @Test
    fun unmaskedExceptionThroughCpuTakesNmiVector() {
        val cpu = Emulator8086()
        val fpu = MathCoprocessor8087().also {
            it.onUnmaskedException = { cpu.requestNmi() }
        }
        cpu.mathCoprocessor = fpu
        // NMI vector → 1000:0000
        cpu.writePhysByte(8, 0x00)
        cpu.writePhysByte(9, 0x00)
        cpu.writePhysByte(10, 0x00)
        cpu.writePhysByte(11, 0x10)
        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x200)
        putFloat(cpu, 0x500, 1.0f)
        putFloat(cpu, 0x504, 0.0f)
        // FLDCW 037B (ZE unmasked), FLD [500], FDIV [504]
        cpu.writePhysByte(0x510, 0x7B)
        cpu.writePhysByte(0x511, 0x03)
        cpu.writeInstruction(
            byteArrayOf(
                0xD9.toByte(), 0x2E, 0x10, 0x05, // FLDCW [0510]
                0xD9.toByte(), 0x06, 0x00, 0x05, // FLD [0500]
                0xD8.toByte(), 0x36, 0x04, 0x05, // FDIV [0504]
            ),
        )
        repeat(3) { assertTrue(cpu.executeSingleInstruction()) }
        assertTrue(cpu.isNmiPending())
        assertTrue(cpu.serviceNmiIfPending())
        assertEquals(0x1000, cpu.getReg16(REG_CS))
        assertEquals(0x0000, cpu.getIp())
    }

    @Test
    fun guestFloatSmokeComputesOnePointFiveTimesTwoPlusPi() {
        // Stand-in for BASIC/TP float expression: 1.5 * 2 + π
        val cpu = Emulator8086()
        cpu.mathCoprocessor = MathCoprocessor8087()
        cpu.setReg16(REG_CS, 0)
        cpu.setReg16(REG_DS, 0)
        cpu.setIp(0x200)
        putFloat(cpu, 0x600, 1.5f)
        putFloat(cpu, 0x604, 2.0f)
        cpu.writeInstruction(
            byteArrayOf(
                0xD9.toByte(), 0x06, 0x00, 0x06, // FLD dword [0600] ; 1.5
                0xD8.toByte(), 0x0E, 0x04, 0x06, // FMUL dword [0604] ; *2
                0xD9.toByte(), 0xEB.toByte(),    // FLDPI
                0xDE.toByte(), 0xC1.toByte(),    // FADDP ST(1), ST
                0xD9.toByte(), 0x1E, 0x08, 0x06, // FSTP dword [0608]
            ),
        )
        repeat(5) { assertTrue(cpu.executeSingleInstruction()) }
        val expected = (1.5 * 2.0 + Math.PI).toFloat()
        assertEquals(expected, readFloat(cpu, 0x608), 1e-5f)
    }

    @Test
    fun checkItArithmeticIntegerRoundTripAndRomConstants() {
        // Golden qwords from CheckIt 2.1 DS table (file offsets mapped from 6e06…).
        val a = 0x5555555555555555L
        val b = 0xaaaaaaaaaaaaaaaaUL.toLong() // signed -0x5555555555555556
        val fpu = MathCoprocessor8087()
        val mem = Memory()

        mem.putLong(0, a)
        mem.putLong(8, b)
        fpu.executeEsc(0xDF, 0, 5, 0, 0, mem.access) // FILD m64 A
        fpu.executeEsc(0xDF, 0, 7, 0, 16, mem.access) // FISTP m64
        assertEquals(a, mem.long(16))

        fpu.executeEsc(0xDF, 0, 5, 0, 8, mem.access) // FILD B
        fpu.executeEsc(0xDF, 0, 5, 0, 0, mem.access) // FILD A
        fpu.executeEsc(0xD8, 3, 0, 1, 0, mem.access) // FADD ST, ST(1)
        fpu.executeEsc(0xDF, 0, 7, 0, 24, mem.access)
        assertEquals(-1L, mem.long(24))

        // FSTP of ROM constants must match CheckIt's IEEE64 golden table.
        fpu.reset()
        fpu.executeEsc(0xD9, 3, 5, 3, 0, mem.access) // FLDPI
        fpu.executeEsc(0xD9, 3, 5, 2, 0, mem.access) // FLDL2E
        fpu.executeEsc(0xD9, 3, 5, 1, 0, mem.access) // FLDL2T
        fpu.executeEsc(0xD9, 3, 5, 4, 0, mem.access) // FLDLG2
        fpu.executeEsc(0xD9, 3, 5, 5, 0, mem.access) // FLDLN2
        // CheckIt FXCH reorder → store order PI, L2E, L2T, LG2, LN2
        fpu.executeEsc(0xD9, 3, 1, 4, 0, mem.access) // FXCH ST(4)
        fpu.executeEsc(0xD9, 3, 1, 1, 0, mem.access)
        fpu.executeEsc(0xD9, 3, 1, 3, 0, mem.access)
        fpu.executeEsc(0xD9, 3, 1, 1, 0, mem.access)
        val expectedConsts = longArrayOf(
            0x400921fb54442d18L, // PI
            0x3ff71547652b82feL, // L2E
            0x400a934f0979a371L, // L2T
            0x3fd34413509f79ffL, // LG2
            0x3fe62e42fefa39efL, // LN2
        )
        for (i in expectedConsts.indices) {
            fpu.executeEsc(0xDD, 0, 3, 0, 100 + i * 8, mem.access) // FSTP m64
            assertEquals(expectedConsts[i], mem.long(100 + i * 8), "const[$i]")
        }
    }

    @Test
    fun checkItArithmeticPremSqrtChainProducesMinLong() {
        // Out-of-range integer store (CheckIt DS:6e26 golden) and the FPREM 15÷4 stub
        // used in the arithmetic sequence.
        val b = 0xaaaaaaaaaaaaaaaaUL.toLong()
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putLong(0, b)
        mem.bytes[32] = 15
        mem.bytes[33] = 0

        fpu.executeEsc(0xDF, 0, 5, 0, 0, mem.access) // FILD B
        fpu.executeEsc(0xDE, 0, 1, 0, 32, mem.access) // FIMUL 15 → beyond i64
        fpu.executeEsc(0xDF, 0, 7, 0, 64, mem.access) // FISTP m64
        assertEquals(Long.MIN_VALUE, mem.long(64))

        fpu.reset()
        mem.putFloat(0, 4.0f)
        mem.putFloat(4, 15.0f)
        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access) // divisor 4
        fpu.executeEsc(0xD9, 0, 0, 0, 4, mem.access) // dividend 15
        fpu.executeEsc(0xD9, 3, 7, 0, 0, mem.access) // FPREM
        assertEquals(3.0, fpu.st(0)!!, 1e-12)
        // Quotient 3 (011b): C0←Q2=0, C3←Q1=1, C1←Q0=1 → C3|C1
        assertEquals(0x4200, fpu.statusWord and 0x4700)
    }

    @Test
    fun checkItTrigGoldenF2xm1Fyl2xAndFptanFpatan() {
        // CheckIt expected IEEE64 at DS:6e58.
        val expectF2xm1Fyl2x = 0x3fdb78f91894efa5L // (2^(1/3)-1)*log2(pi)

        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putDouble(0, 1.0 / 3.0)
        fpu.executeEsc(0xDD, 0, 0, 0, 0, mem.access) // FLD m64 → 1/3
        fpu.executeEsc(0xD9, 3, 6, 0, 0, mem.access) // F2XM1
        fpu.executeEsc(0xD9, 3, 5, 3, 0, mem.access) // FLDPI
        fpu.executeEsc(0xD9, 3, 6, 1, 0, mem.access) // FYL2X
        fpu.executeEsc(0xDD, 0, 3, 0, 16, mem.access) // FSTP m64
        assertTrue(kotlin.math.abs(expectF2xm1Fyl2x - mem.long(16)) <= 1)

        fpu.reset()
        // pi/5 then FPTAN; FXCH; FPATAN recovers pi/2 - pi/5 (atan of cot).
        fpu.executeEsc(0xD9, 3, 5, 3, 0, mem.access) // FLDPI
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xD8, 3, 0, 0, 0, mem.access) // FADD ST,ST → 2
        fpu.executeEsc(0xD8, 3, 1, 0, 0, mem.access) // FMUL ST,ST → 4
        fpu.executeEsc(0xD9, 3, 5, 0, 0, mem.access) // FLD1
        fpu.executeEsc(0xDE, 3, 0, 1, 0, mem.access) // FADDP ST(1),ST → 5
        fpu.executeEsc(0xDE, 3, 6, 1, 0, mem.access) // FDIVP ST(1),ST → pi/5
        fpu.executeEsc(0xD9, 3, 6, 2, 0, mem.access) // FPTAN
        fpu.executeEsc(0xD9, 3, 1, 1, 0, mem.access) // FXCH ST(1)
        fpu.executeEsc(0xD9, 3, 6, 3, 0, mem.access) // FPATAN
        fpu.executeEsc(0xDD, 0, 3, 0, 8, mem.access)
        val expect = java.lang.Double.doubleToRawLongBits(Math.PI / 2.0 - Math.PI / 5.0)
        assertEquals(expect, mem.long(8))
    }

    @Test
    fun fdivrpSt1IsSt0OverSt1() {
        val fpu = MathCoprocessor8087()
        val mem = Memory()
        mem.putFloat(0, 3.0f)
        mem.putFloat(4, 1.0f)
        fpu.executeEsc(0xD9, 0, 0, 0, 0, mem.access) // push 3
        fpu.executeEsc(0xD9, 0, 0, 0, 4, mem.access) // push 1 → ST0=1,ST1=3
        fpu.executeEsc(0xDE, 3, 7, 1, 0, mem.access) // FDIVRP ST(1),ST
        assertEquals(1.0 / 3.0, fpu.st(0)!!, 1e-15)
    }

    private fun putFloat(cpu: Emulator8086, addr: Int, value: Float) {
        val bits = value.toRawBits()
        repeat(4) { cpu.writePhysByte(addr + it, bits ushr (it * 8)) }
    }

    private fun readFloat(cpu: Emulator8086, addr: Int): Float {
        var bits = 0
        repeat(4) { bits = bits or (cpu.readPhysByte(addr + it) shl (it * 8)) }
        return Float.fromBits(bits)
    }
}
