package com.trugath.k8086

import com.trugath.k8086.api.MemoryDevice
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.cpu.ALU_MOV
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.RAM_SIZE
import com.trugath.k8086.cpu.REGS_BASE
import com.trugath.k8086.cpu.REG_AL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VGA write-mode-1 latch copies require MOV to memory to be write-only.
 * Reading the destination first reloads latches from DI and turns the blit
 * into an identity copy.
 */
class MovMemWriteOnlyTest {
    @Test
    fun movToMmioDoesNotReadDestination() {
        val cpu = Emulator8086()
        var reads = 0
        var writes = 0
        var stored = 0x11
        val dev = object : MemoryDevice {
            override fun memReadByte(offset: Int): Int {
                reads++
                return stored
            }

            override fun memWriteByte(offset: Int, value: Int) {
                writes++
                stored = value and 0xFF
            }
        }
        cpu.memoryBus.map(MemoryRegion.Mmio(0xA0000, 16, dev), "probe")
        cpu.iW = false
        cpu.regs8[REG_AL] = 0x5A.toByte()

        cpu.memOp(0xA0000, ALU_MOV, REGS_BASE + REG_AL)

        assertEquals(0, reads, "MOV [mem],reg must not read destination (VGA latches)")
        assertEquals(1, writes)
        assertEquals(0x5A, stored)
        assertTrue(cpu.pendingException < 0)
    }
}
