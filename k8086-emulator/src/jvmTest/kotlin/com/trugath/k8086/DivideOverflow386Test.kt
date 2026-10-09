package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator80386
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class DivideOverflow386Test {
    @TestFactory
    fun capturedExOverflowOperandsRequireArchitecturalDivideError(): List<DynamicTest> = listOf(
        Triple("67F6.7 #375", 29726, -104),
        Triple("67F6.7 #460", 24960, -67),
        Triple("67F6.7 #516", -25487, 71),
        Triple("F6.7 #469", 25740, -73),
        Triple("F6.7 #516", -25487, 71),
        Triple("F6.7 #850", -30393, 109),
        Triple("F6.7 #1265", 18432, -16),
        Triple("F6.7 #1754", -21272, 38),
        Triple("F6.7 #2348", 32189, -123),
    ).map { (name, dividend, divisor) ->
        DynamicTest.dynamicTest("$name: $dividend / $divisor raises #DE") {
            assertTrue(dividend / divisor !in -128..127)
            verifyDivide(dividend, divisor, true, name.startsWith("67"))
        }
    }

    @TestFactory
    fun signedByteQuotientBoundaries(): List<DynamicTest> = listOf(-129, -128, 127, 128).map { quotient ->
        DynamicTest.dynamicTest("quotient $quotient") {
            verifyDivide(quotient, 1, quotient !in -128..127, false)
        }
    }

    private fun verifyDivide(dividend: Int, divisor: Int, overflow: Boolean, addressPrefix: Boolean) {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg32Value(REG_SP, 0xCAFE0100.toInt())
        val eax = 0xABCD0000.toInt() or (dividend and 0xFFFF)
        cpu.setReg32Value(REG_AX, eax)
        cpu.setReg32Value(REG_CX, 0x12345600 or (divisor and 0xFF))
        // Use the same operands through CL, independently of the corpus files.
        val code = (if (addressPrefix) listOf(0x67) else emptyList()) + listOf(0xF6, 0xF9)
        code.forEachIndexed { index, value -> cpu.writePhysByte(0x10100 + index, value) }
        cpu.writePhysByte(1, 0x02)
        cpu.writePhysByte(3, 0x30) // #DE handler at 3000:0200.
        var interrupt: Int? = null
        cpu.interruptTrace = { number, _, _ -> interrupt = number }
        repeat(if (addressPrefix) 2 else 1) { assertTrue(cpu.executeSingleInstruction()) }
        if (overflow) {
            assertEquals(0, interrupt)
            assertEquals(eax, cpu.getReg32(REG_AX))
            assertEquals(0xCAFE00FA.toInt(), cpu.getReg32(REG_SP))
            assertEquals(0x3000, cpu.getReg16(REG_CS))
            assertEquals(0x0200, cpu.getIp())
            assertEquals(0x00, cpu.readPhysByte(0x200FA))
            assertEquals(0x01, cpu.readPhysByte(0x200FB)) // Restart at the prefix, if present.
            assertEquals(0x00, cpu.readPhysByte(0x200FC))
            assertEquals(0x10, cpu.readPhysByte(0x200FD))
        } else {
            assertEquals(null, interrupt)
            assertEquals(0xABCD0000.toInt() or ((dividend / divisor) and 0xFF), cpu.getReg32(REG_AX))
            assertEquals(0xCAFE0100.toInt(), cpu.getReg32(REG_SP))
            assertEquals(0x1000, cpu.getReg16(REG_CS))
            assertEquals(0x0100 + code.size, cpu.getIp())
        }
        assertEquals(0x12345600 or (divisor and 0xFF), cpu.getReg32(REG_CX))
    }
}
