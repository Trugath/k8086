package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator80286
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.FLAG_OF
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_BP
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_SI
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Emulator80286Test {
    @Test
    fun pushaPopaRoundTrip() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_AX, 0x1111)
        cpu.setReg16(REG_CX, 0x2222)
        cpu.setReg16(REG_DX, 0x3333)
        cpu.setReg16(REG_BX, 0x4444)
        cpu.setReg16(REG_BP, 0x5555)
        cpu.setReg16(REG_SI, 0x6666)
        cpu.setReg16(REG_DI, 0x7777)
        cpu.writePhysByte(0x10100, 0x60) // PUSHA
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x00F0, cpu.getReg16(REG_SP))

        cpu.setReg16(REG_AX, 0)
        cpu.setReg16(REG_CX, 0)
        cpu.setReg16(REG_DX, 0)
        cpu.setReg16(REG_BX, 0)
        cpu.setReg16(REG_BP, 0)
        cpu.setReg16(REG_SI, 0)
        cpu.setReg16(REG_DI, 0)
        cpu.writePhysByte(0x10101, 0x61) // POPA
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x1111, cpu.getReg16(REG_AX))
        assertEquals(0x2222, cpu.getReg16(REG_CX))
        assertEquals(0x3333, cpu.getReg16(REG_DX))
        assertEquals(0x4444, cpu.getReg16(REG_BX))
        assertEquals(0x5555, cpu.getReg16(REG_BP))
        assertEquals(0x6666, cpu.getReg16(REG_SI))
        assertEquals(0x7777, cpu.getReg16(REG_DI))
        assertEquals(0x0100, cpu.getReg16(REG_SP))
    }

    @Test
    fun pushImmAndLeaveEnter() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_BP, 0xAAAA)
        // ENTER 4, 0
        cpu.writePhysByte(0x10100, 0xC8)
        cpu.writePhysByte(0x10101, 0x04)
        cpu.writePhysByte(0x10102, 0x00)
        cpu.writePhysByte(0x10103, 0x00)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x00FE, cpu.getReg16(REG_BP))
        assertEquals(0x00FA, cpu.getReg16(REG_SP))

        cpu.writePhysByte(0x10104, 0xC9) // LEAVE
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xAAAA, cpu.getReg16(REG_BP))
        assertEquals(0x0100, cpu.getReg16(REG_SP))
    }

    @Test
    fun pushSpStoresPreDecrementValue() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.writePhysByte(0x10100, 0x54) // PUSH SP
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x00FE, cpu.getReg16(REG_SP))
        assertEquals(0x00, cpu.readPhysByte(0x200FE))
        assertEquals(0x01, cpu.readPhysByte(0x200FF))
    }

    @Test
    fun smswDoesNotPopCs() {
        val cpu286 = Emulator80286()
        cpu286.setReg16(REG_CS, 0x1000)
        cpu286.setIp(0x0100)
        cpu286.setReg16(REG_AX, 0)
        // SMSW AX = 0F 01 E0
        cpu286.writePhysByte(0x10100, 0x0F)
        cpu286.writePhysByte(0x10101, 0x01)
        cpu286.writePhysByte(0x10102, 0xE0)
        assertTrue(cpu286.executeSingleInstruction())
        assertEquals(0x1000, cpu286.getReg16(REG_CS), "0x0F must not be POP CS on 286")
        assertEquals(Emulator80286.MSW_RESET, cpu286.getReg16(REG_AX))
        assertEquals(0x0103, cpu286.getIp())

        val cpu88 = Emulator8088()
        cpu88.setReg16(REG_CS, 0x1000)
        cpu88.setIp(0x0100)
        cpu88.setReg16(REG_SS, 0x2000)
        cpu88.setReg16(REG_SP, 0xFFFE)
        cpu88.writePhysByte(0x2FFFE, 0x00)
        cpu88.writePhysByte(0x2FFFF, 0x30)
        cpu88.writePhysByte(0x10100, 0x0F)
        assertTrue(cpu88.executeSingleInstruction())
        assertEquals(0x3000, cpu88.getReg16(REG_CS))
    }

    @Test
    fun cltsClearsTaskSwitched() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        // Force TS via LMSW then CLTS
        cpu.setReg16(REG_AX, Emulator80286.MSW_TS)
        cpu.writePhysByte(0x10100, 0x0F)
        cpu.writePhysByte(0x10101, 0x01)
        cpu.writePhysByte(0x10102, 0xF0) // LMSW AX
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(Emulator80286.MSW_TS, cpu.machineStatusWord and Emulator80286.MSW_TS)

        cpu.writePhysByte(0x10103, 0x0F)
        cpu.writePhysByte(0x10104, 0x06) // CLTS
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0, cpu.machineStatusWord and Emulator80286.MSW_TS)
    }

    @Test
    fun consecutiveSegmentPrefixedMovesDoNotAccumulatePastTenBytes() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_DS, 0x3000)
        cpu.setReg16(REG_ES, 0x3000)
        cpu.setReg16(REG_BX, 0)
        // #GP vector -> 5000:0000 so a false length fault is visible.
        cpu.writePhysByte(0x34, 0x00)
        cpu.writePhysByte(0x35, 0x00)
        cpu.writePhysByte(0x36, 0x00)
        cpu.writePhysByte(0x37, 0x50)
        // Eight ES-prefixed stores, matching a DOS PSP-init pattern. Each is
        // only 5–6 bytes; a leaked prefix hold used to charge them as one >10-byte
        // instruction and #GP back to the first prefix.
        val code = intArrayOf(
            0x26, 0xC7, 0x07, 0xCD, 0x20,             // ES: MOV WORD [BX], 0x20CD
            0x26, 0xC6, 0x47, 0x05, 0x9A,             // ES: MOV BYTE [BX+5], 0x9A
            0x26, 0xC7, 0x47, 0x06, 0xC0, 0x00,       // ES: MOV WORD [BX+6], 0x00C0
            0x26, 0x89, 0x5F, 0x08,                   // ES: MOV [BX+8], BX
            0x26, 0xC7, 0x47, 0x50, 0xCD, 0x21,       // ES: MOV WORD [BX+0x50], 0x21CD
            0x26, 0xC6, 0x47, 0x52, 0xCB,             // ES: MOV BYTE [BX+0x52], 0xCB
            0x26, 0x8C, 0x47, 0x16,                   // ES: MOV [BX+0x16], ES
            0x26, 0xC7, 0x47, 0x38, 0xFF, 0xFF,       // ES: MOV WORD [BX+0x38], 0xFFFF
            0x90,                                     // NOP — proves we finished the chain
        )
        code.forEachIndexed { i, b -> cpu.writePhysByte(0x10100 + i, b) }
        val endIp = 0x0100 + code.size
        var steps = 0
        while (cpu.getReg16(REG_CS) == 0x1000 && cpu.getIp() < endIp && steps < 64) {
            assertTrue(cpu.executeSingleInstruction())
            steps++
        }
        assertEquals(0x1000, cpu.getReg16(REG_CS), "must not take #GP during prefixed MOV chain")
        assertEquals(endIp, cpu.getIp())
        assertEquals(0xCD, cpu.readPhysByte(0x30000))
        assertEquals(0x20, cpu.readPhysByte(0x30001))
        assertEquals(0x9A, cpu.readPhysByte(0x30005))
        assertEquals(0xFF, cpu.readPhysByte(0x30038))
        assertEquals(0xFF, cpu.readPhysByte(0x30039))
    }

    @Test
    fun imulImm8SetsProductAndFlags() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_BX, 10)
        // IMUL AX, BX, 3  (6B C3 03)
        cpu.writePhysByte(0x10100, 0x6B)
        cpu.writePhysByte(0x10101, 0xC3)
        cpu.writePhysByte(0x10102, 0x03)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(30, cpu.getReg16(REG_AX))
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(0, cpu.getFlag(FLAG_OF))
    }

    @Test
    fun boundRaisesInt5WhenOutOfRange() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_DS, 0x3000)
        cpu.setReg16(REG_AX, 5)
        // bounds [0, 3] at DS:0000
        cpu.writePhysByte(0x30000, 0x00)
        cpu.writePhysByte(0x30001, 0x00)
        cpu.writePhysByte(0x30002, 0x03)
        cpu.writePhysByte(0x30003, 0x00)
        // INT 5 vector -> 5000:0000
        cpu.writePhysByte(0x14, 0x00)
        cpu.writePhysByte(0x15, 0x00)
        cpu.writePhysByte(0x16, 0x00)
        cpu.writePhysByte(0x17, 0x50)
        // BOUND AX, [0] = 62 06 00 00
        cpu.writePhysByte(0x10100, 0x62)
        cpu.writePhysByte(0x10101, 0x06)
        cpu.writePhysByte(0x10102, 0x00)
        cpu.writePhysByte(0x10103, 0x00)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x5000, cpu.getReg16(REG_CS))
        assertEquals(0x0000, cpu.getIp())
    }
}
