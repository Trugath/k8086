package com.trugath.k8086

import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringIoAndRotateRegressionTest {
    @Test
    fun byteSignedDivideRaisesDivideErrorForOverflow() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_AX, 0x741E)
        cpu.setReg16(REG_CX, 0x0098) // 29726 / -104 cannot fit in signed AL.
        cpu.writePhysByte(0x10100, 0xF6)
        cpu.writePhysByte(0x10101, 0xF9)
        cpu.writePhysByte(1, 0x02)
        cpu.writePhysByte(3, 0x30)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0200, cpu.getIp())
        assertEquals(0x00FA, cpu.getReg16(REG_SP))
        assertEquals(0x741E, cpu.getReg16(REG_AX))
        assertEquals(0x00, cpu.readPhysByte(0x200FA))
        assertEquals(0x01, cpu.readPhysByte(0x200FB))
    }

    @Test
    fun leaWith32BitAddressDoesNotAccessMemoryOrCheckSegmentLimit() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg32Value(REG_BX, 0x12345678)
        listOf(0x67, 0x66, 0x8D, 0x43, 0x04).forEachIndexed { i, byte ->
            cpu.writePhysByte(0x10100 + i, byte)
        }
        repeat(3) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0x1234567C, cpu.getReg32(REG_AX))
        assertEquals(0x1000, cpu.getReg16(REG_CS))
    }

    @Test
    fun dwordEnterLeavePreservesFullFramePointer() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg32Value(REG_BP, 0x12345678)
        listOf(0x66, 0xC8, 0x10, 0, 0, 0x66, 0xC9).forEachIndexed { i, byte ->
            cpu.writePhysByte(0x10100 + i, byte)
        }
        repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0xFC, cpu.getReg32(REG_BP))
        assertEquals(0xEC, cpu.getReg16(REG_SP))
        assertEquals(0x12, cpu.readPhysByte(0x200FF))
        repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0x12345678, cpu.getReg32(REG_BP))
        assertEquals(0x100, cpu.getReg16(REG_SP))
    }

    @Test
    fun dwordUnsignedDivideUsesEdxEaxDividend() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg32Value(REG_DX, 1)
        cpu.setReg32Value(REG_AX, 3)
        cpu.setReg32Value(REG_BX, 2)
        listOf(0x66, 0xF7, 0xF3).forEachIndexed { i, byte ->
            cpu.writePhysByte(0x10100 + i, byte)
        }
        repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0x80000001.toInt(), cpu.getReg32(REG_AX))
        assertEquals(1, cpu.getReg32(REG_DX))
    }

    @Test
    fun dwordNearReturnRejectsTargetBeyondCodeSegmentLimit() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        listOf(0x66, 0xC2, 0x10, 0).forEachIndexed { i, byte ->
            cpu.writePhysByte(0x10100 + i, byte)
        }
        cpu.writePhysByte(0x20102, 1) // return EIP = 0x00010000
        cpu.writePhysByte(13 * 4 + 1, 0x02)
        cpu.writePhysByte(13 * 4 + 3, 0x30)
        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0200, cpu.getIp())
        // Only the exception frame consumes stack space; RET's pop and imm16
        // adjustment must not commit when its target is outside the CS limit.
        assertEquals(0x00FA, cpu.getReg16(REG_SP))
    }

    @Test
    fun dwordRotatePreservesAll32Bits() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg32Value(REG_AX, 0x80000001.toInt())
        listOf(0x66, 0xC1, 0xC0, 1).forEachIndexed { i, byte ->
            cpu.writePhysByte(0x10100 + i, byte)
        }
        assertTrue(cpu.executeSingleInstruction()) // operand-size prefix
        assertTrue(cpu.executeSingleInstruction()) // ROL EAX, 1
        assertEquals(3, cpu.getReg32(REG_AX))
        assertEquals(1, cpu.getFlags() and 1)
    }

    @Test
    fun repInsWordWrapFaultHasModelSpecificSideEffects() {
        for (cpu in listOf(Emulator80286(), Emulator80386())) {
            cpu.setReg16(REG_CS, 0x1000)
            cpu.setIp(0x0100)
            cpu.setReg16(REG_SS, 0x2000)
            cpu.setReg16(REG_SP, 0x0100)
            cpu.setReg16(REG_DI, 0xFFFF)
            cpu.setReg16(REG_CX, 5)
            cpu.writePhysByte(0x10100, 0xF3)
            cpu.writePhysByte(0x10101, 0x6D)
            cpu.writePhysByte(13 * 4, 0x00)
            cpu.writePhysByte(13 * 4 + 1, 0x02)
            cpu.writePhysByte(13 * 4 + 2, 0x00)
            cpu.writePhysByte(13 * 4 + 3, 0x30)
            assertTrue(cpu.executeSingleInstruction())
            assertTrue(cpu.executeSingleInstruction())
            val is386 = cpu is Emulator80386
            assertEquals(if (is386) 0xFFFF else 1, cpu.getReg16(REG_DI))
            assertEquals(if (is386) 5 else 3, cpu.getReg16(REG_CX))
            assertEquals(0x3000, cpu.getReg16(REG_CS))
            assertEquals(0x0200, cpu.getIp())
        }
    }
}
