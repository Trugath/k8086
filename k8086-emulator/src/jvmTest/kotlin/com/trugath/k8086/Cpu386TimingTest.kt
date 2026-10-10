package com.trugath.k8086

import com.trugath.k8086.cpu.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Cpu386TimingTest {
    private fun prepare(cpu: Emulator8086, vararg code: Int): Emulator8086 = cpu.apply {
        setReg16(REG_CS, 0x1000)
        setReg16(REG_DS, 0x1000)
        setReg16(REG_ES, 0x2000)
        setReg16(REG_AX, 2)
        setReg16(REG_BX, 3)
        setIp(0x100)
        code.forEachIndexed { i, byte -> writePhysByte(0x10100 + i, byte) }
    }

    @Test
    fun multiplyUses386BoundWithoutChanging8088Timing() {
        val fast = prepare(Emulator80386(), 0xF7, 0xE3) // MUL BX
        val xt = prepare(Emulator8086(), 0xF7, 0xE3)
        for (cpu in listOf(fast, xt)) {
            assertTrue(cpu.executeSingleInstruction())
            assertEquals(6, cpu.getReg16(REG_AX))
        }
        assertEquals(22, fast.lastInstructionCycles)
        assertEquals(124, xt.lastInstructionCycles)
    }

    @Test
    fun memoryMultiplyAndDwordDivideAccountForTheirOperands() {
        val mem = prepare(Emulator80386(), 0xF7, 0x26, 0x00, 0x02)
        mem.writePhysByte(0x10200, 3)
        assertTrue(mem.executeSingleInstruction())
        assertEquals(25, mem.lastInstructionCycles)
        val wide = prepare(Emulator80386(), 0x66, 0xF7, 0xF3) // DIV EBX
        wide.setReg32Value(REG_AX, 100)
        wide.setReg32Value(REG_DX, 0)
        repeat(2) { assertTrue(wide.executeSingleInstruction()) }
        assertEquals(38, wide.lastInstructionCycles)
        assertEquals(33, wide.getReg16(REG_AX))
    }

    @Test
    fun wordAluAndShiftsDoNotInherit8088Costs() {
        val cpu = prepare(Emulator80386(), 0x01, 0xD8, 0xD1, 0xE0)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(2, cpu.lastInstructionCycles)
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(3, cpu.lastInstructionCycles)
        assertEquals(10, cpu.getReg16(REG_AX))
    }

    @Test
    fun repCountsFinalQuantumAndChargesSetupOnce() {
        for (count in listOf(0, 3, REP_ITER_QUANTUM, REP_ITER_QUANTUM + 3)) {
            val cpu = prepare(Emulator80386(), 0xF3, 0xA4)
            cpu.setReg16(REG_CX, count)
            cpu.setFlagsValue(0x200) // allow interruptible quanta
            var cycles = 0
            var steps = 0
            do {
                assertTrue(cpu.executeSingleInstruction())
                cycles += cpu.lastInstructionCycles
                assertTrue(++steps < 20)
            } while (cpu.getIp() != 0x102)
            assertEquals(5 + 4 * count, cycles, "REP MOVSB count=$count")
            assertEquals(count, cpu.getReg16(REG_DI))
        }
    }
}
