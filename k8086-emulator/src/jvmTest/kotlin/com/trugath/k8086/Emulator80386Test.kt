package com.trugath.k8086

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.cpu.Emulator80286
import com.trugath.k8086.cpu.Emulator80386
import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.FLAG_OF
import com.trugath.k8086.cpu.FLAG_ZF
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_BP
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_FS
import com.trugath.k8086.cpu.REG_GS
import com.trugath.k8086.cpu.REG_SI
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.getFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Emulator80386Test {
    private fun Emulator80386.placeAt(cs: Int = 0x1000, ip: Int = 0x0100) {
        setReg16(REG_CS, cs)
        setIp(ip)
    }

    private fun Emulator80386.writeCode(vararg bytes: Int) {
        val base = (getReg16(REG_CS) shl 4) + getIp()
        bytes.forEachIndexed { i, b -> writePhysByte(base + i, b) }
    }

    private fun Emulator80386.stepBytes(vararg bytes: Int) {
        writeCode(*bytes)
        assertTrue(executeSingleInstruction())
    }

    @Test
    fun modelAndResetCr0() {
        val cpu = Emulator80386()
        assertEquals(CpuModel.I80386, cpu.model)
        assertEquals(Emulator80386.CR0_RESET, cpu.cr0)
        assertEquals(Emulator80386.CR0_RESET and 0xFFFF, cpu.machineStatusWord)
    }

    @Test
    fun resetClearsFsGsHighHalvesAndCr0() {
        val cpu = Emulator80386()
        cpu.setReg16(REG_FS, 0x1111)
        cpu.setReg16(REG_GS, 0x2222)
        cpu.setReg32Value(REG_AX, 0xDEADBEEF.toInt())
        cpu.cr0 = Emulator80386.CR0_RESET or Emulator80386.CR0_TS
        cpu.reset()
        assertEquals(0, cpu.getReg16(REG_FS))
        assertEquals(0, cpu.getReg16(REG_GS))
        assertEquals(0, cpu.getReg32(REG_AX))
        assertEquals(Emulator80386.CR0_RESET, cpu.cr0)
        assertEquals(0xFFFF, cpu.getReg16(REG_CS))
        assertEquals(0, cpu.getIp())
    }

    @Test
    fun fsGsOverridesAndMovSreg() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_AX, 0x3000)
        cpu.stepBytes(0x8E, 0xE0) // MOV FS, AX
        assertEquals(0x3000, cpu.getReg16(REG_FS))

        cpu.setReg16(REG_FS, 0x2000)
        cpu.writePhysByte(0x20000, 0x5A)
        cpu.stepBytes(0x64) // FS:
        cpu.stepBytes(0xA0, 0x00, 0x00) // MOV AL, [0]
        assertEquals(0x5A, cpu.getReg8(0))

        cpu.setReg16(REG_AX, 0x4000)
        cpu.stepBytes(0x8E, 0xE8) // MOV GS, AX
        assertEquals(0x4000, cpu.getReg16(REG_GS))
        cpu.writePhysByte(0x40000, 0xA5)
        cpu.stepBytes(0x65) // GS:
        cpu.stepBytes(0xA0, 0x00, 0x00)
        assertEquals(0xA5, cpu.getReg8(0))
    }

    @Test
    fun pushPopFsGs() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_FS, 0x1234)
        cpu.setReg16(REG_GS, 0x5678)
        cpu.stepBytes(0x0F, 0xA0) // PUSH FS
        assertEquals(0x00FE, cpu.getReg16(REG_SP))
        assertEquals(0x34, cpu.readPhysByte(0x200FE))
        assertEquals(0x12, cpu.readPhysByte(0x200FF))
        cpu.stepBytes(0x0F, 0xA8) // PUSH GS
        assertEquals(0x00FC, cpu.getReg16(REG_SP))

        cpu.setReg16(REG_FS, 0)
        cpu.setReg16(REG_GS, 0)
        cpu.stepBytes(0x0F, 0xA9) // POP GS
        assertEquals(0x5678, cpu.getReg16(REG_GS))
        cpu.stepBytes(0x0F, 0xA1) // POP FS
        assertEquals(0x1234, cpu.getReg16(REG_FS))
        assertEquals(0x0100, cpu.getReg16(REG_SP))
    }

    @Test
    fun sixteenBitMovPreservesEaxHighHalf() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg32Value(REG_AX, 0x12345678)
        cpu.stepBytes(0xB8, 0xCD, 0xAB) // MOV AX, 0xABCD
        assertEquals(0x1234ABCD, cpu.getReg32(REG_AX))
    }

    @Test
    fun operandSizePrefixLoadsEax() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg32Value(REG_AX, 0)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0xB8, 0x78, 0x56, 0x34, 0x12) // MOV EAX, 12345678h
        assertEquals(0x12345678, cpu.getReg32(REG_AX))
        assertEquals(0x0106, cpu.getIp())
    }

    @Test
    fun pushPop32RoundTrip() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg32Value(REG_BX, 0xAABBCCDDu.toInt())
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x53) // PUSH EBX
        assertEquals(0x00FC, cpu.getReg16(REG_SP))
        assertEquals(0xDD, cpu.readPhysByte(0x200FC))
        assertEquals(0xCC, cpu.readPhysByte(0x200FD))
        assertEquals(0xBB, cpu.readPhysByte(0x200FE))
        assertEquals(0xAA, cpu.readPhysByte(0x200FF))

        cpu.setReg32Value(REG_BX, 0)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x5B) // POP EBX
        assertEquals(0xAABBCCDDu.toInt(), cpu.getReg32(REG_BX))
        assertEquals(0x0100, cpu.getReg16(REG_SP))
    }

    @Test
    fun pushadPopadRoundTrip() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0200)
        cpu.setReg32Value(REG_AX, 0x11111111)
        cpu.setReg32Value(REG_CX, 0x22222222)
        cpu.setReg32Value(REG_DX, 0x33333333)
        cpu.setReg32Value(REG_BX, 0x44444444)
        cpu.setReg32Value(REG_BP, 0x55555555)
        cpu.setReg32Value(REG_SI, 0x66666666)
        cpu.setReg32Value(REG_DI, 0x77777777)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x60) // PUSHAD
        assertEquals(0x01E0, cpu.getReg16(REG_SP)) // 8 * 4

        cpu.setReg32Value(REG_AX, 0)
        cpu.setReg32Value(REG_CX, 0)
        cpu.setReg32Value(REG_DX, 0)
        cpu.setReg32Value(REG_BX, 0)
        cpu.setReg32Value(REG_BP, 0)
        cpu.setReg32Value(REG_SI, 0)
        cpu.setReg32Value(REG_DI, 0)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x61) // POPAD
        assertEquals(0x11111111, cpu.getReg32(REG_AX))
        assertEquals(0x22222222, cpu.getReg32(REG_CX))
        assertEquals(0x33333333, cpu.getReg32(REG_DX))
        assertEquals(0x44444444, cpu.getReg32(REG_BX))
        assertEquals(0x55555555, cpu.getReg32(REG_BP))
        assertEquals(0x66666666, cpu.getReg32(REG_SI))
        assertEquals(0x77777777, cpu.getReg32(REG_DI))
        assertEquals(0x0200, cpu.getReg16(REG_SP))
    }

    @Test
    fun cwdeAndCdq() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg32Value(REG_AX, 0x0000FF80)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x98) // CWDE: sign-extend AX → EAX
        assertEquals(0xFFFFFF80.toInt(), cpu.getReg32(REG_AX))

        cpu.stepBytes(0x66)
        cpu.stepBytes(0x99) // CDQ: sign-extend EAX → EDX:EAX
        assertEquals(-1, cpu.getReg32(REG_DX))
    }

    @Test
    fun smswLmswMovCr0AndClts() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.stepBytes(0x0F, 0x01, 0xE0) // SMSW AX
        assertEquals(Emulator80386.CR0_RESET and 0xFFFF, cpu.getReg16(REG_AX))

        cpu.setReg16(REG_AX, Emulator80386.CR0_TS)
        cpu.stepBytes(0x0F, 0x01, 0xF0) // LMSW AX
        assertEquals(Emulator80386.CR0_TS, cpu.cr0 and Emulator80386.CR0_TS)
        assertEquals(Emulator80386.CR0_TS, cpu.machineStatusWord and Emulator80386.CR0_TS)

        cpu.setReg32Value(REG_AX, 0)
        cpu.stepBytes(0x0F, 0x20, 0xC0) // MOV EAX, CR0
        assertEquals(cpu.cr0, cpu.getReg32(REG_AX))

        cpu.setReg32Value(REG_AX, Emulator80386.CR0_RESET or Emulator80386.CR0_TS)
        cpu.stepBytes(0x0F, 0x22, 0xC0) // MOV CR0, EAX
        assertEquals(Emulator80386.CR0_TS, cpu.cr0 and Emulator80386.CR0_TS)

        cpu.stepBytes(0x0F, 0x06) // CLTS
        assertEquals(0, cpu.cr0 and Emulator80386.CR0_TS)
    }

    @Test
    fun movzxMovsxAndSetcc() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_AX, 0xFF80.toShort().toInt() and 0xFFFF)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x0F, 0xB6, 0xD8) // MOVZX EBX, AL
        assertEquals(0x80, cpu.getReg32(REG_BX))

        cpu.stepBytes(0x66)
        cpu.stepBytes(0x0F, 0xBE, 0xD8) // MOVSX EBX, AL
        assertEquals(0xFFFFFF80.toInt(), cpu.getReg32(REG_BX))

        cpu.setReg32Value(REG_AX, 0x12345678)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x31, 0xC0) // XOR EAX,EAX → ZF=1
        assertEquals(0, cpu.getReg32(REG_AX))
        assertEquals(1, cpu.getFlag(FLAG_ZF))

        cpu.stepBytes(0x0F, 0x94, 0xC3) // SETZ BL
        assertEquals(1, cpu.getReg8(6))
    }

    @Test
    fun bitTestBtsAndBsf() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_AX, 0x0010) // bit 4 set
        cpu.setReg16(REG_CX, 4)
        cpu.stepBytes(0x0F, 0xA3, 0xC8) // BT AX, CX
        assertEquals(1, cpu.getFlag(FLAG_CF))

        cpu.setReg16(REG_AX, 0x0001)
        cpu.setReg16(REG_CX, 3)
        cpu.stepBytes(0x0F, 0xAB, 0xC8) // BTS AX, CX
        assertEquals(0x0009, cpu.getReg16(REG_AX))
        assertEquals(0, cpu.getFlag(FLAG_CF))

        cpu.setReg16(REG_AX, 0x0080)
        cpu.setReg16(REG_BX, 0)
        cpu.stepBytes(0x0F, 0xBC, 0xD8) // BSF BX, AX
        assertEquals(7, cpu.getReg16(REG_BX))
        assertEquals(0, cpu.getFlag(FLAG_ZF))
    }

    @Test
    fun shldAndImulRm() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_AX, 0x1234)
        cpu.setReg16(REG_BX, 0x5678)
        cpu.stepBytes(0x0F, 0xA4, 0xD8, 0x04) // SHLD AX, BX, 4
        // 0x1234 << 4 | top of 0x5678 = 0x2345
        assertEquals(0x2345, cpu.getReg16(REG_AX))

        cpu.setReg16(REG_AX, 7)
        cpu.setReg16(REG_BX, 6)
        cpu.stepBytes(0x0F, 0xAF, 0xC3) // IMUL AX, BX
        assertEquals(42, cpu.getReg16(REG_AX))
        assertEquals(0, cpu.getFlag(FLAG_CF))
        assertEquals(0, cpu.getFlag(FLAG_OF))
    }

    @Test
    fun jccNearTakenAndNotTaken() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setFlagsValue(cpu.getFlags() or 0x40) // ZF=1
        cpu.stepBytes(0x0F, 0x84, 0x10, 0x00) // JE +0x10
        assertEquals(0x0114, cpu.getIp())

        cpu.placeAt(ip = 0x0200)
        cpu.setFlagsValue(cpu.getFlags() and 0x40.inv()) // ZF=0
        cpu.writeCode(0x0F, 0x84, 0x10, 0x00)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x0204, cpu.getIp())
    }

    @Test
    fun lfsLoadsOffsetAndSegment() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x3000)
        cpu.writePhysByte(0x30000, 0x34) // offset
        cpu.writePhysByte(0x30001, 0x12)
        cpu.writePhysByte(0x30002, 0x00) // segment
        cpu.writePhysByte(0x30003, 0x40)
        cpu.stepBytes(0x0F, 0xB4, 0x06, 0x00, 0x00) // LFS AX, [0]
        assertEquals(0x1234, cpu.getReg16(REG_AX))
        assertEquals(0x4000, cpu.getReg16(REG_FS))
    }

    @Test
    fun addressSizePrefixIsAccepted() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.stepBytes(0x67) // addrsize
        cpu.stepBytes(0x90) // NOP
        assertEquals(0x0102, cpu.getIp())
    }

    @Test
    fun movToCsRaisesUd() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        // INT 6 vector → 5000:0000
        cpu.writePhysByte(0x18, 0x00)
        cpu.writePhysByte(0x19, 0x00)
        cpu.writePhysByte(0x1A, 0x00)
        cpu.writePhysByte(0x1B, 0x50)
        cpu.stepBytes(0x8E, 0xC8) // MOV CS, AX
        assertEquals(0x5000, cpu.getReg16(REG_CS))
        assertEquals(0x0000, cpu.getIp())
    }

    @Test
    fun dwordAccessAtFffdFaults() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0000)
        // INT 13 vector → 5000:0000
        cpu.writePhysByte(0x34, 0x00)
        cpu.writePhysByte(0x35, 0x00)
        cpu.writePhysByte(0x36, 0x00)
        cpu.writePhysByte(0x37, 0x50)
        cpu.setReg32Value(REG_AX, 0x11223344)
        // PUSH EAX with SP=0 → new SP=FFFC, last byte at FFFF — ok
        // Force a faulting push: SP=1 → new SP=FFFD, last byte would be 0000 → #GP
        cpu.setReg16(REG_SP, 0x0001)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x50) // PUSH EAX
        assertEquals(0x5000, cpu.getReg16(REG_CS), "dword stack wrap should #GP")
    }

    @Test
    fun physicalAddressUses32BitBusUnlike286() {
        val cpu386 = Emulator80386()
        val cpu286 = Emulator80286()
        assertEquals(0x12345678, cpu386.physicalAddress(0x12345678))
        assertEquals(0x345678, cpu286.physicalAddress(0x12345678))
    }

    @Test
    fun allowsFifteenByteEncodingsUnlike286() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_DS, 0x3000)
        cpu.setReg16(REG_BX, 0)
        // INT 13 vector — if falsely #GP'd we'd land at 5000:0000
        cpu.writePhysByte(0x34, 0x00)
        cpu.writePhysByte(0x35, 0x00)
        cpu.writePhysByte(0x36, 0x00)
        cpu.writePhysByte(0x37, 0x50)
        // 14 prefix bytes + 1-byte NOP = 15 (legal on 386, #GP on 286)
        val code = IntArray(14) { 0x26 } + intArrayOf(0x90)
        code.forEachIndexed { i, b -> cpu.writePhysByte(0x10100 + i, b) }
        var steps = 0
        while (cpu.getReg16(REG_CS) == 0x1000 && cpu.getIp() < 0x0100 + code.size && steps < 32) {
            assertTrue(cpu.executeSingleInstruction())
            steps++
        }
        assertEquals(0x1000, cpu.getReg16(REG_CS), "15-byte prefixed NOP must not #GP on 386")
        assertEquals(0x0100 + code.size, cpu.getIp())
    }
}
