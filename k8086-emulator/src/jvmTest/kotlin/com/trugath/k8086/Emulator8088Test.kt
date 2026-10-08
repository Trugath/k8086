package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.FLAG_OF
import com.trugath.k8086.cpu.FLAG_ZF
import com.trugath.k8086.cpu.REG_AH
import com.trugath.k8086.cpu.REG_AL
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_CL
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.*
import com.trugath.k8086.api.CpuModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Focused 8088 silicon differences that are easy to lose during a shared-engine
 * refactor. The hardware-vector corpus remains the full oracle.
 */
class Emulator8088Test {
    @Test
    fun modelIsI8088() {
        assertEquals(CpuModel.I8088, Emulator8088().model)
    }

    @Test
    fun multiBitRotateDefinesOverflow() {
        val cpu = Emulator8088()
        cpu.setIp(0x200)
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg8(REG_AL, 0x81)
        cpu.setReg8(REG_CL, 2)
        cpu.setReg8(FLAG_OF, 1)
        cpu.writeInstruction(byteArrayOf(0xD2.toByte(), 0xC0.toByte())) // ROL AL, CL
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x06, cpu.getReg8(REG_AL))
        assertEquals(0, cpu.getFlag(FLAG_OF))
    }

    @Test
    fun multiBitShiftDefinesOverflowUnlike8086() {
        val i8086 = Emulator8086()
        val i8088 = Emulator8088()
        for (cpu in listOf(i8086, i8088)) {
            cpu.setIp(0x200)
            cpu.setReg16(REG_CS, 0x1000)
            cpu.setReg8(REG_AL, 0x40)
            cpu.setReg8(REG_CL, 2)
            cpu.setReg8(FLAG_OF, 0)
            cpu.writeInstruction(byteArrayOf(0xD2.toByte(), 0xE0.toByte())) // SHL AL, CL
            assertTrue(cpu.executeSingleInstruction())
        }
        // 8086 leaves OF unchanged for count>1; 8088 recomputes it (here: 1).
        assertEquals(0, i8086.getFlag(FLAG_OF))
        assertEquals(1, i8088.getFlag(FLAG_OF))
        assertEquals(0x00, i8088.getReg8(REG_AL))
    }

    @Test
    fun popCsIsOpcode0F() {
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x00FE)
        cpu.writePhysByte(0x200FE, 0x34)
        cpu.writePhysByte(0x200FF, 0x12)
        cpu.writePhysByte(0x10100, 0x0F) // POP CS
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x1234, cpu.getReg16(REG_CS))
        assertEquals(0x0100, cpu.getReg16(REG_SP))
    }

    @Test
    fun mulSetsZfFromHighHalf() {
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg8(REG_AL, 0x40)
        cpu.writePhysByte(0x10100, 0xF6)
        cpu.writePhysByte(0x10101, 0xE0) // MUL AL
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x1000, cpu.getReg16(REG_AX))
        // ZF from AH (0x10), not AL (0x00).
        assertEquals(0, cpu.getFlag(FLAG_ZF))
        assertEquals(0x10, cpu.getReg8(REG_AH))
    }
}
