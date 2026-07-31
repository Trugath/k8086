package com.trugath.k8086.cpu

import com.trugath.k8086.api.CpuModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the 256-entry decode maps for each CPU model so profile extraction
 * cannot silently remap opcodes.
 */
class DecodeCharacterizationTest {
    @Test
    fun staticProfilesMatchCpuLookups() {
        for (model in listOf(CpuModel.I8086, CpuModel.I8088, CpuModel.I80286)) {
            val profile = DecodeProfiles.forModel(model)
            val cpu = when (model) {
                CpuModel.I8086 -> Emulator8086()
                CpuModel.I8088 -> Emulator8088()
                CpuModel.I80286 -> Emulator80286()
            }
            for (opcode in 0..255) {
                assertEquals(profile.xlatOpcodeFor(opcode), cpu.xlatOpcodeFor(opcode), "$model xlat@$opcode")
                assertEquals(profile.xlatSubfunctionFor(opcode), cpu.xlatSubfunctionFor(opcode), "$model sub@$opcode")
                assertEquals(profile.iModSizeFor(opcode), cpu.iModSizeFor(opcode), "$model mod@$opcode")
                assertEquals(profile.stdFlagsFor(opcode), cpu.stdFlagsFor(opcode), "$model flags@$opcode")
                assertEquals(profile.baseInstSizeFor(opcode), cpu.baseInstSizeFor(opcode), "$model base@$opcode")
                assertEquals(profile.iWSizeFor(opcode), cpu.iWSizeFor(opcode), "$model iw@$opcode")
            }
        }
    }

    @Test
    fun i8086DecodeTablesMatchLegacyArrays() {
        val probe = DecodeProbe.i8086()
        for (opcode in 0..255) {
            assertEquals(DecodeTables.XLAT_OPCODE[opcode].toInt() and 0xFF, probe.xlat(opcode), "xlat@$opcode")
            assertEquals(DecodeTables.XLAT_SUBFUNCTION[opcode].toInt() and 0xFF, probe.sub(opcode), "sub@$opcode")
            assertEquals(DecodeTables.I_MOD_SIZE[opcode].toInt() and 0xFF, probe.modSize(opcode), "mod@$opcode")
            assertEquals(DecodeTables.STD_FLAGS[opcode].toInt() and 0xFF, probe.flags(opcode), "flags@$opcode")
            assertEquals(DecodeTables.BASE_INST_SIZE[opcode].toInt() and 0xFF, probe.baseSize(opcode), "base@$opcode")
            assertEquals(DecodeTables.I_W_SIZE[opcode].toInt() and 0xFF, probe.immWSize(opcode), "iw@$opcode")
        }
    }

    @Test
    fun i8088DecodeTablesMatchI8086() {
        val i8086 = DecodeProbe.i8086()
        val i8088 = DecodeProbe.i8088()
        for (opcode in 0..255) {
            assertEquals(i8086.xlat(opcode), i8088.xlat(opcode), "xlat@$opcode")
            assertEquals(i8086.sub(opcode), i8088.sub(opcode), "sub@$opcode")
            assertEquals(i8086.modSize(opcode), i8088.modSize(opcode), "mod@$opcode")
            assertEquals(i8086.flags(opcode), i8088.flags(opcode), "flags@$opcode")
            assertEquals(i8086.baseSize(opcode), i8088.baseSize(opcode), "base@$opcode")
            assertEquals(i8086.immWSize(opcode), i8088.immWSize(opcode), "iw@$opcode")
        }
    }

    @Test
    fun i80286Remaps186And0FOpcodes() {
        val probe = DecodeProbe.i80286()
        assertEquals(49, probe.xlat(0x60)) // PUSHA
        assertEquals(50, probe.xlat(0x61)) // POPA
        assertEquals(51, probe.xlat(0x62)) // BOUND
        assertEquals(52, probe.xlat(0x68)) // PUSH imm16
        assertEquals(52, probe.xlat(0x69)) // IMUL imm16
        assertEquals(52, probe.xlat(0x6A)) // PUSH imm8
        assertEquals(52, probe.xlat(0x6B)) // IMUL imm8
        assertEquals(57, probe.xlat(0x6C)) // INS
        assertEquals(57, probe.xlat(0x6F)) // OUTS
        assertEquals(12, probe.xlat(0xC0)) // shift imm8
        assertEquals(12, probe.xlat(0xC1))
        assertEquals(1, probe.sub(0xC0))
        assertEquals(54, probe.xlat(0xC8)) // ENTER
        assertEquals(55, probe.xlat(0xC9)) // LEAVE
        assertEquals(56, probe.xlat(0x0F)) // escape
        assertEquals(0, probe.baseSize(0x0F))
        assertEquals(1, probe.modSize(0x62))
        assertEquals(1, probe.modSize(0xC0))
        assertEquals(FLAGS_UPDATE_SZP, probe.flags(0x69))
        assertEquals(FLAGS_UPDATE_SZP, probe.flags(0x6B))
    }

    @Test
    fun i8086Leaves186OpcodesAsJccAlias() {
        val probe = DecodeProbe.i8086()
        // 0x60..0x6F are all zeros in the 8086 XLAT table (Jcc family).
        for (opcode in 0x60..0x6F) {
            assertEquals(0, probe.xlat(opcode), "8086 xlat@$opcode")
        }
        // 0x0F is POP CS on 808x (xlat 26).
        assertEquals(26, probe.xlat(0x0F))
    }

    @Test
    fun peekInstructionLengthIncludesPrefixes() {
        val cpu = Emulator8086()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        // ES: NOP
        cpu.writePhysByte(0x10100, 0x26)
        cpu.writePhysByte(0x10101, 0x90)
        assertEquals(2, cpu.peekInstructionLengthAtCsIp())
        // plain ADD AL, imm8
        cpu.setIp(0x0200)
        cpu.writePhysByte(0x10200, 0x04)
        cpu.writePhysByte(0x10201, 0x01)
        assertEquals(2, cpu.peekInstructionLengthAtCsIp())
    }

    @Test
    fun resetSetsCanonicalPowerOnState() {
        val cpu = Emulator8086()
        cpu.setReg16(REG_AX, 0x1234)
        cpu.setReg16(REG_DS, 0x1111)
        cpu.setReg16(REG_ES, 0x2222)
        cpu.setReg16(REG_SS, 0x3333)
        cpu.setReg16(REG_SP, 0x4444)
        cpu.setIp(0x5555)
        cpu.setFlagsValue(0xFFFF)
        cpu.reset()
        assertEquals(0xFFFF, cpu.getReg16(REG_CS))
        assertEquals(0, cpu.getReg16(REG_DS))
        assertEquals(0, cpu.getReg16(REG_ES))
        assertEquals(0, cpu.getReg16(REG_SS))
        assertEquals(0, cpu.getReg16(REG_SP))
        assertEquals(0, cpu.getIp())
        assertEquals(0, cpu.getFlag(FLAG_IF))
        assertEquals(0, cpu.getFlag(FLAG_TF))
        assertTrue(!cpu.isTrapPending())
    }

    @Test
    fun i80286DivideErrorUsesInstructionStartIpWithPrefix() {
        val cpu = Emulator80286()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0x0100)
        // Point INT 0 vector at a HLT so we can observe the pushed IP.
        cpu.writePhysByte(0x0000, 0x00) // IP low
        cpu.writePhysByte(0x0001, 0x50) // IP = 0x5000
        cpu.writePhysByte(0x0002, 0x00)
        cpu.writePhysByte(0x0003, 0xF0) // CS = 0xF000
        cpu.writePhysByte(0xF5000, 0xF4) // HLT

        cpu.setReg16(REG_AX, 0x0001)
        cpu.setReg8(REG_CL, 0) // divisor 0
        // ES: DIV CL
        cpu.writePhysByte(0x10100, 0x26)
        cpu.writePhysByte(0x10101, 0xF6)
        cpu.writePhysByte(0x10102, 0xF1)

        assertTrue(cpu.executeSingleInstruction()) // prefix
        assertTrue(cpu.executeSingleInstruction()) // DIV → #DE
        // 286 pushes the IP of the first prefix byte (0x0100).
        val sp = cpu.getReg16(REG_SP)
        val pushedIp = cpu.readPhysByte(0x20000 + sp) or (cpu.readPhysByte(0x20000 + sp + 1) shl 8)
        assertEquals(0x0100, pushedIp)
    }
}
