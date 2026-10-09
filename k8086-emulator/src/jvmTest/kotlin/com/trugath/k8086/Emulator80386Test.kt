package com.trugath.k8086

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.cpu.Emulator80286
import com.trugath.k8086.cpu.Emulator80386
import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.FLAG_OF
import com.trugath.k8086.cpu.FLAG_ZF
import com.trugath.k8086.cpu.REG_AL
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_BP
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_ES
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
        flushInstructionPrefetch() // Host installs a fresh instruction for this isolated probe.
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
    fun operandSizeAddEaxImm32PreservesFsAndFlags() {
        // 66 05 imm32 must not clobber FS (scratch sits next to FS) and must set
        // CF/OF/SF from the full 32-bit sum (SingleStep 6605 vectors).
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_FS, 0xB38C)
        cpu.setReg32Value(REG_AX, 0xA3802EAFu.toInt())
        cpu.setFlagsValue(0xFFFC04C7u.toInt())
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x05, 0xC4, 0x95, 0xBA, 0xB5) // ADD EAX, B5BA95C4h
        assertEquals(0x593AC473, cpu.getReg32(REG_AX))
        assertEquals(0xB38C, cpu.getReg16(REG_FS), "imm32 scratch must not alias FS")
        assertEquals(0x0106, cpu.getIp())
        assertEquals(1, cpu.getFlag(FLAG_CF))
        assertEquals(1, cpu.getFlag(FLAG_OF))
        assertEquals(0, cpu.getFlag(FLAG_ZF))
    }


    @Test
    fun setoWithGsOverrideWritesMemory() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_GS, 0x0200)
        cpu.setReg16(REG_BX, 0)
        cpu.setReg16(REG_SI, 0)
        cpu.setFlagsValue(0x0802) // OF set
        cpu.writePhysByte(0x2000, 0)
        cpu.stepBytes(0x65) // GS:
        cpu.stepBytes(0x0F, 0x90, 0x00) // SETO [bx+si]
        assertEquals(1, cpu.readPhysByte(0x2000))
    }

    @Test
    fun lockAddMemWithStackedSegmentOverridesHalts() {
        // F0 26 65 00 /r F4 — LOCK + ES + GS + ADD m8,r8 + HLT (SingleStep 00 #499).
        val cpu = Emulator80386()
        cpu.placeAt(cs = 0x1000, ip = 0x0100)
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_ES, 0x2000)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_FS, 0x2000)
        cpu.setReg16(REG_GS, 0x2000)
        cpu.setReg16(REG_BX, 0)
        cpu.setReg16(REG_SI, 0)
        cpu.setReg16(REG_BP, 0)
        cpu.writePhysByte(0x20000, 0x10)
        cpu.writeCode(0xF0, 0x26, 0x65, 0x00, 0x38, 0xF4) // ADD [bx+si], bh
        var steps = 0
        while (!cpu.isHalted() && steps < 20) {
            assertTrue(cpu.executeSingleInstruction(), "step $steps")
            steps++
        }
        assertTrue(cpu.isHalted(), "halted after $steps steps")
    }

    @Test
    fun lockPushRegisterRaisesUd() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        // IVT[6] → 3000:0000
        cpu.writePhysByte(0x18, 0x00)
        cpu.writePhysByte(0x19, 0x00)
        cpu.writePhysByte(0x1A, 0x00)
        cpu.writePhysByte(0x1B, 0x30)
        cpu.stepBytes(0xF0) // LOCK
        cpu.stepBytes(0x50) // PUSH AX → #UD
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0000, cpu.getIp())
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
    fun shldCountAboveWidthRotatesSource() {
        // 386EX: 16-bit SHLD with count 16..31 rotates SRC by (count-16) into DEST.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DI, 0x6A94)
        cpu.setReg16(REG_SP, 0x43BE)
        cpu.stepBytes(0x0F, 0xA4, 0xE7, 0xF7) // SHLD DI, SP, 0xF7 (count=23)
        assertEquals(0xDF21, cpu.getReg16(REG_DI))
    }

    @Test
    fun shldWordAtFfffRaisesGp() {
        // 16-bit memory SHLD at offset FFFFh must #GP(0) on 386 (no wrap).
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_DI, 0xFFFF)
        cpu.setReg16(REG_SI, 0x1234)
        cpu.setReg16(REG_SS, 0x3000)
        cpu.setReg16(REG_SP, 0x0100)
        // IVT #13 → 2000:0000 (HLT)
        cpu.writePhysByte(0x34, 0x00)
        cpu.writePhysByte(0x35, 0x00)
        cpu.writePhysByte(0x36, 0x00)
        cpu.writePhysByte(0x37, 0x20)
        cpu.writePhysByte(0x20000, 0xF4)
        cpu.stepBytes(0x0F, 0xA4, 0x35, 0x01) // SHLD [DI], SI, 1
        assertEquals(0x2000, cpu.getReg16(REG_CS), "SHLD m16 at FFFFh must deliver #GP")
        assertEquals(0x0000, cpu.getIp())
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
    fun jccNearDwordWith66hUsesRel32() {
        val cpu = Emulator80386()
        cpu.placeAt(ip = 0x0100)
        cpu.setFlagsValue(cpu.getFlags() or 0x800) // OF=1 → JO taken
        // 66 0F 80 rel32=+0x10 → land at 0x0100+7+0x10 = 0x0117
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x0F, 0x80, 0x10, 0x00, 0x00, 0x00)
        assertEquals(0x0117, cpu.getIp())

        cpu.placeAt(ip = 0x0200)
        cpu.setFlagsValue(cpu.getFlags() and 0x800.inv()) // OF=0 → not taken
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x0F, 0x80, 0x10, 0x00, 0x00, 0x00)
        assertEquals(0x0207, cpu.getIp(), "66h JO must consume 4-byte displacement")
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
    fun lssDwordWith66hLoadsM1632() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x3000)
        cpu.setReg32Value(REG_AX, 0xAABBCCDDu.toInt())
        // m16:32 at DS:0 → offset 0x11223344, SS=0x5566
        cpu.writePhysByte(0x30000, 0x44)
        cpu.writePhysByte(0x30001, 0x33)
        cpu.writePhysByte(0x30002, 0x22)
        cpu.writePhysByte(0x30003, 0x11)
        cpu.writePhysByte(0x30004, 0x66)
        cpu.writePhysByte(0x30005, 0x55)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x0F, 0xB2, 0x06, 0x00, 0x00) // LSS EAX, [0]
        assertEquals(0x11223344, cpu.getReg32(REG_AX))
        assertEquals(0x5566, cpu.getReg16(REG_SS))
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
    fun dwordPushWrapsButPopGprAtFffeFaultsWhilePopEsWraps() {
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        // INT 12 (#SS) vector → 5000:0000
        cpu.writePhysByte(0x30, 0x00)
        cpu.writePhysByte(0x31, 0x00)
        cpu.writePhysByte(0x32, 0x00)
        cpu.writePhysByte(0x33, 0x50)
        cpu.setReg32Value(REG_AX, 0x11223344)
        // 386EX: PUSH EAX from SP=FFFF wraps (→FFFB); POP EAX at SP=FFFE is #SS.
        cpu.setReg16(REG_SP, 0xFFFF)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x50) // PUSH EAX
        assertEquals(0x1000, cpu.getReg16(REG_CS), "dword push from SP=FFFF must wrap, not fault")
        assertEquals(0xFFFB, cpu.getReg16(REG_SP))

        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.writePhysByte(0x2FFFE, 0x34)
        cpu.writePhysByte(0x2FFFF, 0x12)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x07) // POP ES — word selector + SP+=4, wrap OK
        assertEquals(0x1000, cpu.getReg16(REG_CS), "66h POP ES at SP=FFFE must not #SS")
        assertEquals(0x1234, cpu.getReg16(REG_ES))
        assertEquals(0x0002, cpu.getReg16(REG_SP))

        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setReg32Value(REG_AX, 0x11223344)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x58) // POP EAX → #SS
        assertEquals(0x5000, cpu.getReg16(REG_CS), "dword pop at SP=FFFE should #SS")
        assertEquals(0xFFF8, cpu.getReg16(REG_SP), "faulting POP must not commit SP+=4 before #SS frame")
    }

    @Test
    fun physicalAddressUses32BitBusUnlike286() {
        val cpu386 = Emulator80386()
        val cpu286 = Emulator80286()
        assertEquals(0x12345678, cpu386.physicalAddress(0x12345678))
        assertEquals(0x345678, cpu286.physicalAddress(0x12345678))
    }

    @Test
    fun realModePopfAllowsIoplUnlike286() {
        // Frontier EL2SETUP: or ah,0xf0 / popf / pushf — IOPL must stick on 386.
        val cpu386 = Emulator80386()
        cpu386.placeAt()
        cpu386.setReg16(REG_SS, 0x2000)
        cpu386.setReg16(REG_SP, 0x0100)
        cpu386.setFlagsValue(0x0002)
        cpu386.stepBytes(0x9C) // PUSHF
        cpu386.stepBytes(0x58) // POP AX
        cpu386.stepBytes(0x80, 0xCC, 0xF0) // OR AH,0xF0
        cpu386.stepBytes(0x50) // PUSH AX
        cpu386.stepBytes(0x9D) // POPF
        cpu386.stepBytes(0x9C) // PUSHF
        cpu386.stepBytes(0x58) // POP AX
        assertEquals(0xF0, cpu386.getReg8(1) and 0xF0, "386 real-mode POPF must keep IOPL/NT")

        val cpu286 = Emulator80286()
        cpu286.setReg16(REG_CS, 0x1000)
        cpu286.setIp(0x0100)
        cpu286.setReg16(REG_SS, 0x2000)
        cpu286.setReg16(REG_SP, 0x0100)
        cpu286.setFlagsValue(0x0002)
        fun Emulator80286.step(vararg bytes: Int) {
            val base = (getReg16(REG_CS) shl 4) + getIp()
            bytes.forEachIndexed { i, b -> writePhysByte(base + i, b) }
            assertTrue(executeSingleInstruction())
        }
        cpu286.step(0x9C)
        cpu286.step(0x58)
        cpu286.step(0x80, 0xCC, 0xF0)
        cpu286.step(0x50)
        cpu286.step(0x9D)
        cpu286.step(0x9C)
        cpu286.step(0x58)
        assertEquals(0, cpu286.getReg8(1) and 0xF0, "286 real-mode POPF must clear IOPL/NT")
    }

    @Test
    fun popfdLoadsAcBit() {
        // 386EX PUSHFD zero-extends FLAGS (high half 0); POPFD still accepts AC.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setFlagsValue(0x0002)
        cpu.setReg32Value(REG_AX, 0x00040002)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x50) // PUSH EAX
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x9D) // POPFD
        assertEquals(0x40000, cpu.getFlags() and 0x40000, "POPFD must load AC")
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x9C) // PUSHFD
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x58) // POP EAX
        assertEquals(0, cpu.getReg32(REG_AX) and 0xFFFF0000.toInt(), "PUSHFD high half is 0 on 386EX")
    }

    @Test
    fun popfdAtFffeRaisesSsPreservingFlagsOnStack() {
        // POPFD at SP=FFFE → #SS; interrupt frame must push pre-fault FLAGS
        // (not the zero pop() return). 669D.MOO exception vectors.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setFlagsValue(0x0C53)
        // IVT #SS → 5000:0000
        cpu.writePhysByte(0x30, 0x00)
        cpu.writePhysByte(0x31, 0x00)
        cpu.writePhysByte(0x32, 0x00)
        cpu.writePhysByte(0x33, 0x50)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x9D) // POPFD
        assertEquals(0x5000, cpu.getReg16(REG_CS))
        assertEquals(0xFFF8, cpu.getReg16(REG_SP))
        val flagsLo = cpu.readPhysByte(0x2FFFC)
        val flagsHi = cpu.readPhysByte(0x2FFFD)
        assertEquals(0x0C53, flagsLo or (flagsHi shl 8), "fault frame FLAGS must be pre-POPFD")
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

    @Test
    fun operandSizeRetfPopsSelectorFromDwordSlot() {
        // 66h RETF consumes a dword CS slot and discards its upper 16 bits.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        cpu.setReg16(REG_CS, 0x1000)
        // Stack image (low→high addresses): EIP=000005B8, CS=6982
        cpu.writePhysByte(0x20100, 0xB8)
        cpu.writePhysByte(0x20101, 0x05)
        cpu.writePhysByte(0x20102, 0x00)
        cpu.writePhysByte(0x20103, 0x00)
        cpu.writePhysByte(0x20104, 0x82)
        cpu.writePhysByte(0x20105, 0x69)
        cpu.writePhysByte(0x20106, 0xAB)
        cpu.writePhysByte(0x20107, 0xCD)
        cpu.stepBytes(0x66) // operand-size prefix (hold for next opcode)
        cpu.stepBytes(0xCB) // RETF
        assertEquals(0x6982, cpu.getReg16(REG_CS), "66h RETF must pop 16-bit CS after 32-bit EIP")
        assertEquals(0x05B8, cpu.getIp())
        assertEquals(0x0108, cpu.getReg16(REG_SP))
    }

    @Test
    fun addressSizeSibDisp32Mov() {
        // 67 8B 34 5D 00 F0 00 00 — MOV SI, [EBX*2+disp32] (mod=0,rm=4,SIB index=EBX scale=2 base=none)
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg32Value(REG_BX, 0)
        cpu.writePhysByte(0x2F000, 0x78)
        cpu.writePhysByte(0x2F001, 0x56)
        cpu.stepBytes(0x67)
        cpu.stepBytes(0x8B, 0x34, 0x5D, 0x00, 0xF0, 0x00, 0x00)
        assertEquals(0x5678, cpu.getReg16(REG_SI))
        assertEquals(0x0108, cpu.getIp(), "67h MOV r,r/m with SIB+disp32 must consume 7 opcode bytes")
    }

    @Test
    fun addressSizeInvalidSibScalesBase() {
        // 67 8A 44 A0 05 — MOV AL, [EAX*4+5] via invalid SIB (index=4/none, scale=4, base=EAX).
        // 386EX applies scale to base; Intel manuals document these SIB rows as unused.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg32Value(REG_AX, 0x10) // EAX*4+5 = 0x45
        cpu.writePhysByte(0x20045, 0xAB)
        cpu.stepBytes(0x67)
        cpu.stepBytes(0x8A, 0x44, 0xA0, 0x05)
        assertEquals(0xAB, cpu.getReg8(REG_AL))
    }


    @Test
    fun operandSizeAndEbxImm32AdvancesIp() {
        // 66 81 E3 FF FF 00 00 = AND EBX, 0000FFFFh — must consume 7 bytes after CS:IP.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg32Value(REG_BX, 0xAABBCCDDu.toInt())
        cpu.stepBytes(0x66)
        cpu.stepBytes(0x81, 0xE3, 0xFF, 0xFF, 0x00, 0x00)
        assertEquals(0x0000CCDD, cpu.getReg32(REG_BX))
        assertEquals(0x0107, cpu.getIp(), "66h AND r/m32,imm32 must skip 4-byte immediate")
    }

    @Test
    fun operandSizeMovMemImm32AdvancesIp() {
        // 66 C7 06 34 12 78 56 34 12 = MOV dword [0x1234], 0x12345678
        // Over-skip used to land mid-stream (INT ABh) and smash SP → GUEST_FAULT 0000:0000.
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x2000)
        for (i in 0 until 4) cpu.writePhysByte(0x21234 + i, 0)
        cpu.stepBytes(0x66)
        cpu.stepBytes(0xC7, 0x06, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12)
        assertEquals(0x78, cpu.readPhysByte(0x21234))
        assertEquals(0x56, cpu.readPhysByte(0x21235))
        assertEquals(0x34, cpu.readPhysByte(0x21236))
        assertEquals(0x12, cpu.readPhysByte(0x21237))
        assertEquals(0x0109, cpu.getIp(), "66h MOV m32,imm32 [disp16] must consume 8 bytes after C7")
    }

    @Test
    fun repMovsdCopiesDwords() {
        // F3 66 A5: CX counts dwords; each iter must move 4 bytes (not 2 with SI/DI += 4).
        val cpu = Emulator80386()
        cpu.placeAt()
        cpu.setReg16(REG_DS, 0x2000)
        cpu.setReg16(REG_ES, 0x3000)
        cpu.setReg16(REG_SI, 0x0000)
        cpu.setReg16(REG_DI, 0x0000)
        cpu.setReg16(REG_CX, 2)
        // src: AA BB CC DD  11 22 33 44
        val src = intArrayOf(0xAA, 0xBB, 0xCC, 0xDD, 0x11, 0x22, 0x33, 0x44)
        src.forEachIndexed { i, b -> cpu.writePhysByte(0x20000 + i, b) }
        for (i in 0 until 8) cpu.writePhysByte(0x30000 + i, 0)
        cpu.stepBytes(0xF3) // REP
        cpu.stepBytes(0x66) // operand size
        cpu.stepBytes(0xA5) // MOVSW → MOVSD
        assertEquals(0, cpu.getReg16(REG_CX))
        assertEquals(8, cpu.getReg16(REG_SI))
        assertEquals(8, cpu.getReg16(REG_DI))
        for (i in src.indices) {
            assertEquals(src[i], cpu.readPhysByte(0x30000 + i), "dst byte $i")
        }
    }
}
