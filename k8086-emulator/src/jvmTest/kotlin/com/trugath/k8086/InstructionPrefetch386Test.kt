package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator80386
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstructionPrefetch386Test {
    private fun cpuWithCode(vararg code: Int): Emulator80386 = Emulator80386().apply {
        setReg16(REG_CS, 0x1000)
        setReg16(REG_DS, 0x1000)
        setReg16(REG_SS, 0x2000)
        setReg16(REG_SP, 0x0100)
        setIp(0x0100)
        code.forEachIndexed { index, byte -> writePhysByte(0x10100 + index, byte) }
    }

    @Test
    fun codeStorePreservesQueuedInstructionButDataReadSeesStore() {
        // MOV byte [0108], F4; MOV AL, [0108]; NOP; HLT.
        val cpu = cpuWithCode(0xC6, 0x06, 0x08, 0x01, 0xF4, 0xA0, 0x08, 0x01, 0x90, 0xF4)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xF4, cpu.readPhysByte(0x10108))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0xF4, cpu.getReg16(REG_AX) and 0xFF)
        assertTrue(cpu.executeSingleInstruction())
        assertFalse(cpu.isHalted(), "The queued NOP must survive its memory overwrite")
        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.isHalted())
    }

    @Test
    fun queuedImmediateSurvivesCodeStore() {
        // MOV byte [0106], F4; MOV AL, A9; HLT.
        val cpu = cpuWithCode(0xC6, 0x06, 0x06, 0x01, 0xF4, 0xB0, 0xA9, 0xF4)
        repeat(2) { assertTrue(cpu.executeSingleInstruction()) }
        assertEquals(0xF4, cpu.readPhysByte(0x10106))
        assertEquals(0xA9, cpu.getReg16(REG_AX) and 0xFF)
    }

    @Test
    fun untakenNearConditionalJumpPreservesQueuedInstruction() {
        // MOV byte [0109], F4; JZ +0 (ZF=0); NOP; HLT.
        val cpu = cpuWithCode(0xC6, 0x06, 0x09, 0x01, 0xF4, 0x0F, 0x84, 0, 0, 0x90, 0xF4)
        cpu.setFlagsValue(0)
        repeat(3) { assertTrue(cpu.executeSingleInstruction()) }
        assertFalse(cpu.isHalted())
        assertEquals(0x010A, cpu.getIp())
    }

    @Test
    fun jumpToSequentialAddressFlushesQueuedInstruction() {
        // MOV byte [0107], F4; JMP +0; NOP; HLT.
        val cpu = cpuWithCode(0xC6, 0x06, 0x07, 0x01, 0xF4, 0xEB, 0, 0x90, 0xF4)
        repeat(3) { assertTrue(cpu.executeSingleInstruction()) }
        assertTrue(cpu.isHalted(), "Even a jump to the next instruction flushes prefetch")
    }

    @Test
    fun interruptToSequentialAddressFlushesQueuedInstruction() {
        val cpu = cpuWithCode(0xC6, 0x06, 0x05, 0x01, 0xF4, 0x90, 0xF4)
        cpu.writePhysByte(0x80, 0x05)
        cpu.writePhysByte(0x81, 0x01)
        cpu.writePhysByte(0x82, 0)
        cpu.writePhysByte(0x83, 0x10)
        assertTrue(cpu.executeSingleInstruction())
        cpu.serviceInterrupt(0x20)
        assertTrue(cpu.executeSingleInstruction())
        assertTrue(cpu.isHalted())
    }
}
