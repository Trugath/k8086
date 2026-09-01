package com.trugath.k8086

import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Huffman inflate in rmDOS uses CS: word loads of table counts and CMP/Jcc
 * on those values. Lock the encodings GUNZIP.COM actually emits.
 */
class CsOverrideAndCmpTest {
    private fun cpuAt(cs: Int = 0x1000, ip: Int = 0x0200): Emulator8086 {
        val cpu = Emulator8086()
        cpu.setReg16(REG_CS, cs)
        cpu.setIp(ip)
        return cpu
    }

    @Test
    fun csOverrideMovAxFromBxIgnoresDs() {
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_BX, 0x0100)
        // CS:0100 = 0xBEEF, DS:0100 = 0x1111
        cpu.writePhysByte(0x10100, 0xEF)
        cpu.writePhysByte(0x10101, 0xBE)
        cpu.writePhysByte(0x20100, 0x11)
        cpu.writePhysByte(0x20101, 0x11)
        // CS: MOV AX, [BX]  (2E 8B 07)
        cpu.writeInstruction(byteArrayOf(0x2E, 0x8B.toByte(), 0x07))

        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xBEEF, cpu.getReg16(REG_AX), "CS:[BX] must not read DS:[BX]")
        assertEquals(0x0203, cpu.getIp())
    }

    @Test
    fun csOverrideMovAxFromBxPlusDisp8() {
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_BX, 0x0100)
        cpu.writePhysByte(0x10102, 0xFE)
        cpu.writePhysByte(0x10103, 0xCA)
        cpu.writePhysByte(0x20102, 0x00)
        cpu.writePhysByte(0x20103, 0x00)
        // CS: MOV AX, [BX+2]  (2E 8B 47 02)
        cpu.writeInstruction(byteArrayOf(0x2E, 0x8B.toByte(), 0x47, 0x02))

        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xCAFE, cpu.getReg16(REG_AX))
        assertEquals(0x0204, cpu.getIp())
    }

    @Test
    fun csOverrideMovAxFromBxModrm07VsDisp8Zero() {
        // GAS folds [bx+0] to [bx] (8B 07). Both forms must see CS, not DS.
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_BX, 0x0100)
        cpu.writePhysByte(0x10100, 0x34)
        cpu.writePhysByte(0x10101, 0x12)
        cpu.writePhysByte(0x20100, 0xFF)
        cpu.writePhysByte(0x20101, 0xFF)
        // CS: MOV AX, [BX+0]  (2E 8B 47 00)
        cpu.writeInstruction(byteArrayOf(0x2E, 0x8B.toByte(), 0x47, 0x00))

        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x1234, cpu.getReg16(REG_AX))
    }

    @Test
    fun cmpAxImm8SetsCarryWhenUnsignedBelow() {
        fun flagsAfter(ax: Int): Pair<Int, Int> {
            val cpu = cpuAt()
            cpu.setReg16(REG_AX, ax)
            // CMP AX, 2  (83 F8 02) — the form inf_decode uses
            cpu.writeInstruction(byteArrayOf(0x83.toByte(), 0xF8.toByte(), 0x02))
            assertTrue(cpu.executeSingleInstruction())
            return cpu.getFlag(FLAG_CF) to cpu.getFlag(FLAG_ZF)
        }
        assertEquals(1 to 0, flagsAfter(0), "0 < 2 → CF")
        assertEquals(1 to 0, flagsAfter(1), "1 < 2 → CF")
        assertEquals(0 to 1, flagsAfter(2), "2 == 2 → ZF")
        assertEquals(0 to 0, flagsAfter(3), "3 > 2 → NC NZ")
        assertEquals(0 to 0, flagsAfter(24), "count leftover 24 is above 2")
    }

    @Test
    fun movDxImm16IsThreeBytesAndClearsDh() {
        val cpu = cpuAt()
        cpu.setReg16(REG_DX, 0xFF00)
        // MOV DX, 1  (BA 01 00) — inf_bits bit mask
        cpu.writeInstruction(byteArrayOf(0xBA.toByte(), 0x01, 0x00))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(1, cpu.getReg16(REG_DX))
        assertEquals(0x0203, cpu.getIp(), "BA imm16 must consume 3 bytes on 8086")
    }

    @Test
    fun opcode66On8086IsJbeNotOperandSizePrefix() {
        val cpu = cpuAt()
        // XOR AX, AX  (ZF=1, CF=0 → JBE taken)
        cpu.writePhysByte(0x10200, 0x33)
        cpu.writePhysByte(0x10201, 0xC0)
        // 0x66 is JBE rel8 on 8086 (alias of 0x76). Displacement +2 skips MOV AL,1.
        cpu.writePhysByte(0x10202, 0x66)
        cpu.writePhysByte(0x10203, 0x02)
        cpu.writePhysByte(0x10204, 0xB0) // MOV AL, 1
        cpu.writePhysByte(0x10205, 0x01)
        cpu.writePhysByte(0x10206, 0xB0) // MOV AL, 2
        cpu.writePhysByte(0x10207, 0x02)

        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0, cpu.getReg16(REG_AX))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x0206, cpu.getIp(), "8086 66 xx must JBE, not consume a 32-bit operand")
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(2, cpu.getReg8(REG_AL))
    }

    @Test
    fun opcodeC1On8086IsNearRetNotShiftImm() {
        val cpu = cpuAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_DI, 0x0001)
        // Fake near-return target 0xABCD at SS:00FE
        cpu.writePhysByte(0x200FE, 0xCD)
        cpu.writePhysByte(0x200FF, 0xAB)
        cpu.setReg16(REG_SP, 0x00FE)
        // C1 E7 01 is SHL DI, 1 on 186+. 8086 maps C1 to RET-family (xlat 19).
        cpu.writeInstruction(byteArrayOf(0xC1.toByte(), 0xE7.toByte(), 0x01))

        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xABCD, cpu.getIp(), "8086 must not treat C1 as 186 SHL imm8")
        assertEquals(1, cpu.getReg16(REG_DI), "DI must be unchanged if C1 was RET")
    }

    @Test
    fun esOverrideMovAlFromBxIgnoresDs() {
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_ES, 0x3000)
        cpu.setReg16(REG_BX, 0x0100)
        cpu.writePhysByte(0x30100, 0x5A)
        cpu.writePhysByte(0x20100, 0x11)
        // ES: MOV AL, [BX]  (26 8A 07) — inf_copy
        cpu.writeInstruction(byteArrayOf(0x26, 0x8A.toByte(), 0x07))
        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x5A, cpu.getReg8(REG_AL), "ES:[BX] must not read DS:[BX]")
    }

    @Test
    fun movCxFromBxPlusDisp16ReadsWordTable() {
        // GUNZIP inf_codes: 8B 8F disp16  (MOV CX, [BX+disp16]) with BX = 2 * (sym-257).
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_SS, 0x1000)
        cpu.setReg16(REG_SP, 0x0F00)
        cpu.setReg16(REG_BX, 34) // index 17
        val table = 0x0EE6
        // lenbits[17] = 3 at DS:table+34
        cpu.writePhysByte(0x10000 + table + 34, 0x03)
        cpu.writePhysByte(0x10000 + table + 35, 0x00)
        cpu.writePhysByte(0x10000 + table, 0xAA) // index 0 must not be used
        cpu.writePhysByte(0x10000 + table + 1, 0xAA)
        // MOV CX, [BX+0x0EE6]  (8B 8F E6 0E)
        cpu.writeInstruction(byteArrayOf(0x8B.toByte(), 0x8F.toByte(), 0xE6.toByte(), 0x0E))

        assertTrue(cpu.executeSingleInstruction())
        assertEquals(3, cpu.getReg16(REG_CX), "must add BX to disp16, not read [disp16] alone")
        assertEquals(0x0204, cpu.getIp())
    }

    @Test
    fun infBitsStyleShrMemExtractsLowBitFirst() {
        // inf_bits: MOV BL,[buf]; SHR byte [buf],1; TEST BL,1
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_SS, 0x1000)
        cpu.setReg16(REG_SP, 0x0F00)
        cpu.writePhysByte(0x10300, 0x05) // bits 1,0,1 in LSB-first order over 3 reads
        // MOV BL, [0x0300]  (8A 1E 00 03)
        cpu.writePhysByte(0x10200, 0x8A)
        cpu.writePhysByte(0x10201, 0x1E)
        cpu.writePhysByte(0x10202, 0x00)
        cpu.writePhysByte(0x10203, 0x03)
        // SHR byte [0x0300], 1  (D0 2E 00 03)
        cpu.writePhysByte(0x10204, 0xD0.toByte().toInt() and 0xFF)
        cpu.writePhysByte(0x10205, 0x2E)
        cpu.writePhysByte(0x10206, 0x00)
        cpu.writePhysByte(0x10207, 0x03)
        // TEST BL, 1  (F6 C3 01)
        cpu.writePhysByte(0x10208, 0xF6.toByte().toInt() and 0xFF)
        cpu.writePhysByte(0x10209, 0xC3.toByte().toInt() and 0xFF)
        cpu.writePhysByte(0x1020A, 0x01)

        repeat(3) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0x05, cpu.getReg8(REG_BL), "TEST must see pre-shift bit")
        assertEquals(0x02, cpu.readPhysByte(0x10300), "buffer must shift down by 1")
        assertEquals(0, cpu.getFlag(FLAG_ZF), "LSB of 0x05 is 1 so ZF clear")
    }

    @Test
    fun infBitsCx3ReadsThreeBitsNotTheWholeByte() {
        // Exact GUNZIP inf_bits inner loop: CX=3 must yield 0..7, never a full byte.
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_SS, 0x1000)
        cpu.setReg16(REG_SP, 0x0F00)
        cpu.setReg16(REG_CX, 3)
        cpu.setReg16(REG_AX, 0xFFFF)
        val bits = byteArrayOf(
            0x0E, 0x1F, 0x53, 0x52, 0x56, 0x57, 0x89.toByte(), 0xCE.toByte(),
            0x31, 0xC0.toByte(), 0xBA.toByte(), 0x01, 0x00,
            0x83.toByte(), 0x3E, 0xDA.toByte(), 0x0F, 0x00,
            0x74, 0x03, 0xE9.toByte(), 0x11, 0x00,
            0xE8.toByte(), 0x38, 0x00,
            0x73, 0x03, 0xE9.toByte(), 0x2D, 0x00,
            0xA2.toByte(), 0xD8.toByte(), 0x0F,
            0xC7.toByte(), 0x06, 0xDA.toByte(), 0x0F, 0x08, 0x00,
            0x8A.toByte(), 0x1E, 0xD8.toByte(), 0x0F,
            0xD0.toByte(), 0x2E, 0xD8.toByte(), 0x0F,
            0xFF.toByte(), 0x0E, 0xDA.toByte(), 0x0F,
            0xF6.toByte(), 0xC3.toByte(), 0x01,
            0x75, 0x03, 0xE9.toByte(), 0x02, 0x00,
            0x09, 0xD0.toByte(),
            0xD1.toByte(), 0xE2.toByte(),
            0x4E, 0x74, 0x03, 0xE9.toByte(), 0xC7.toByte(), 0xFF.toByte(),
            0xF8.toByte(), 0x5F, 0x5E, 0x5A, 0x5B, 0xC3.toByte(),
        )
        val csBase = 0x10000
        val infBitsIp = 0x052C
        for (i in bits.indices) {
            cpu.writePhysByte(csBase + infBitsIp + i, bits[i].toInt() and 0xFF)
        }
        // dummy inf_pull_byte at 0x57E: STC; RET
        cpu.writePhysByte(csBase + 0x057E, 0xF9)
        cpu.writePhysByte(csBase + 0x057F, 0xC3)
        cpu.writePhysByte(csBase + 0x0FD8, 0x54) // 0b01010100 → 3 LSBs are 0,0,1 = 4
        cpu.writePhysByte(csBase + 0x0FD9, 0x00)
        cpu.writePhysByte(csBase + 0x0FDA, 0x08)
        cpu.writePhysByte(csBase + 0x0FDB, 0x00)
        cpu.writePhysByte(csBase + 0x0F00, 0x00) // return IP 0x0200
        cpu.writePhysByte(csBase + 0x0F01, 0x02)
        cpu.setIp(infBitsIp)

        var steps = 0
        while (cpu.getIp() != 0x0200 && steps < 200) {
            assertTrue(cpu.executeSingleInstruction(), "inf_bits step $steps")
            steps++
        }
        assertEquals(0x0200, cpu.getIp(), "inf_bits must RET")
        assertEquals(4, cpu.getReg16(REG_AX), "CX=3 must not return the whole bitbuf 0x54")
    }

    @Test
    fun xorAxAxClearsAh() {
        val cpu = cpuAt()
        cpu.setReg16(REG_AX, 0x54FF)
        cpu.writeInstruction(byteArrayOf(0x31, 0xC0.toByte()))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0, cpu.getReg16(REG_AX))
    }

    @Test
    fun decSiAfterShlSetsZf() {
        val cpu = cpuAt()
        cpu.setReg16(REG_SI, 1)
        cpu.setReg16(REG_DX, 2)
        cpu.writePhysByte(0x10200, 0xD1)
        cpu.writePhysByte(0x10201, 0xE2) // SHL DX, 1 → DX=4, ZF=0
        cpu.writePhysByte(0x10202, 0x4E) // DEC SI → 0, ZF=1
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0, cpu.getFlag(FLAG_ZF), "SHL DX,1 of 2 is nonzero")
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(1, cpu.getFlag(FLAG_ZF), "DEC SI to 0 must set ZF (inf_bits loop)")
        assertEquals(0, cpu.getReg16(REG_SI))
    }

    @Test
    fun stosbWritesEsDiNotDs() {
        val cpu = cpuAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_ES, 0x3000)
        cpu.setReg16(REG_DI, 0x0100)
        cpu.setReg8(REG_AL, 0xAB)
        cpu.setReg8(FLAG_DF, 0)
        cpu.writeInstruction(byteArrayOf(0xAA.toByte())) // STOSB
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xAB, cpu.getMem(0x30100), "STOSB must write ES:DI")
        assertEquals(0, cpu.getMem(0x20100), "STOSB must not write DS:DI")
        assertEquals(0x0101, cpu.getReg16(REG_DI))
    }

    @Test
    fun opcodeC1On80286IsShlImm8() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0200)
        cpu.setReg16(REG_DI, 0x0001)
        cpu.writeInstruction(byteArrayOf(0xC1.toByte(), 0xE7.toByte(), 0x01))

        assertTrue(cpu.executeSingleInstruction())
        assertEquals(2, cpu.getReg16(REG_DI))
        assertEquals(0x0203, cpu.getIp())
    }
}
