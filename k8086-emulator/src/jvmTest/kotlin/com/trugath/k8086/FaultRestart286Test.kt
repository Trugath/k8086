package com.trugath.k8086

import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FaultRestart286Test {
    private fun cpuWithCode(vararg code: Int): Emulator80286 = Emulator80286().apply {
        setReg16(REG_CS, 0x1000)
        setReg16(REG_SS, 0x2000)
        setReg16(REG_DS, 0x3000)
        setReg16(REG_ES, 0x4000)
        setReg16(REG_SP, 0x0100)
        setIp(0x0100)
        setFlagsValue(0)
        code.forEachIndexed { i, byte -> writePhysByte(0x10100 + i, byte) }
        writePhysByte(13 * 4 + 1, 0x02)
        writePhysByte(13 * 4 + 3, 0x30)
    }

    @Test
    fun popaStackFaultPreservesAllGeneralRegisters() {
        val cpu = cpuWithCode(0x61)
        val registers = listOf(REG_AX, REG_BX, REG_CX, REG_DX, REG_BP, REG_SI, REG_DI)
        registers.forEachIndexed { i, reg -> cpu.setReg16(reg, (i + 1) * 0x1111) }
        cpu.setReg16(REG_SP, 0xFFF1)
        for (offset in 0xFFF1..0xFFFE) cpu.writePhysByte(0x20000 + offset, 0xAA)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0200, cpu.getIp())
        assertEquals(0xFFEB, cpu.getReg16(REG_SP))
        registers.forEachIndexed { i, reg -> assertEquals((i + 1) * 0x1111, cpu.getReg16(reg)) }
    }

    @Test
    fun repComparisonFaultPreservesLastCompletedCarryAndAuxiliaryCarry() {
        for (opcode in listOf(0xA7, 0xAF)) {
            val cpu = cpuWithCode(0xF2, opcode)
            cpu.setReg16(REG_AX, 0)
            cpu.setReg16(REG_CX, 2)
            cpu.setReg16(REG_SI, 0x0200)
            cpu.setReg16(REG_DI, 0xFFFD)
            cpu.writePhysByte(0x4FFFD, 1)
            repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
            assertEquals(0x3000, cpu.getReg16(REG_CS))
            assertEquals(0x0200, cpu.getIp())
            assertEquals(0x11, cpu.getFlags() and 0x11)
            assertEquals(0x11, cpu.readPhysByte(0x200FE) and 0x11)
        }
    }
}
