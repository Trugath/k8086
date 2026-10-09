package com.trugath.k8086

import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FarPointer386Test {
    private fun cpuWithFaultHandler(vector: Int, vararg code: Int): Emulator80386 = Emulator80386().apply {
        setReg16(REG_CS, 0x1000)
        setReg16(REG_DS, 0x2000)
        setReg16(REG_SS, 0x2000)
        setIp(0x0100)
        code.forEachIndexed { i, byte -> writePhysByte(0x10100 + i, byte) }
        writePhysByte(vector * 4 + 1, 0x02)
        writePhysByte(vector * 4 + 3, 0x30)
        writePhysByte(0x30200, 0xF4)
    }

    @Test
    fun dwordFarReturnValidatesFullCsSlotAndRollsBackStack() {
        val cpu = cpuWithFaultHandler(12, 0x66, 0xCB)
        cpu.setReg16(REG_SP, 0xFFF9)
        cpu.writePhysByte(0x2FFF9, 0x10)
        cpu.writePhysByte(0x2FFFA, 0x01)
        cpu.writePhysByte(0x2FFFD, 0)
        cpu.writePhysByte(0x2FFFE, 0x10)
        repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0200, cpu.getIp())
        assertEquals(0xFFF3, cpu.getReg16(REG_SP))
        assertEquals(0x00, cpu.readPhysByte(0x2FFF3))
        assertEquals(0x01, cpu.readPhysByte(0x2FFF4))
    }

    @Test
    fun addressSizeLesValidatesEntirePointerBeforeChangingRegisters() {
        val cpu = cpuWithFaultHandler(13, 0x67, 0xC4, 0x2A)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg32Value(REG_DX, 0xFFFE)
        cpu.setReg16(REG_BP, 0x1234)
        cpu.setReg16(REG_ES, 0x5678)
        cpu.writePhysByte(0x2FFFE, 0xAB)
        cpu.writePhysByte(0x2FFFF, 0xCD)
        repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0200, cpu.getIp())
        assertEquals(0x1234, cpu.getReg16(REG_BP))
        assertEquals(0x5678, cpu.getReg16(REG_ES))
    }
}
