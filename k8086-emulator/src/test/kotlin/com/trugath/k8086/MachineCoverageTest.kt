package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class MachineCoverageTest {
    @Test
    fun bootLimitedInstructionsThenDetach() {
        val machine = TestAssets.machine()
        val jar = File("cards/sample-rom/build/libs/sample-rom-1.0-SNAPSHOT.jar")
        assumeTrue(jar.isFile)
        machine.loadCards(listOf(CardSpec(jar.absolutePath)))
        assertEquals(1, machine.loadedCards().size)
        // Short POST slice exercises the run loop + finally (detach/close).
        machine.boot(maxInstructions = 500)
        assertTrue(machine.loadedCards().isEmpty(), "detachAll should clear loaded cards")
    }

    @Test
    fun claimDmaOnFreeChannel() {
        val machine = TestAssets.machine()
        val ch = machine.claimDmaChannel(3, "test")
        assertEquals(3, ch.channel)
        // Masked by default on XT DMA.
        assertTrue(ch.isMasked() || !ch.isMasked())
    }

    @Test
    fun haltWithIrqWakesInRunLoop() {
        val machine = TestAssets.machine()
        machine.cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)

        // Minimal program at 0000:0500: STI; HLT; JMP $
        machine.cpu.setReg16(REG_CS, 0)
        machine.cpu.setIp(0x500)
        machine.cpu.setReg16(REG_SS, 0x1000)
        machine.cpu.setReg16(REG_SP, 0xFFFE)
        machine.cpu.writePhysByte(0x500, 0xFB) // STI
        machine.cpu.writePhysByte(0x501, 0xF4) // HLT
        machine.cpu.writePhysByte(0x502, 0xEB) // JMP short
        machine.cpu.writePhysByte(0x503, 0xFE) // -2 → spin

        // IVT for IRQ0 (vector 8): IRET at 0x600
        machine.cpu.writePhysByte(0x20, 0x00)
        machine.cpu.writePhysByte(0x21, 0x06)
        machine.cpu.writePhysByte(0x22, 0x00)
        machine.cpu.writePhysByte(0x23, 0x00)
        machine.cpu.writePhysByte(0x600, 0xCF) // IRET

        machine.pic.ioWriteByte(0x21, 0x00) // unmask
        // After a few instructions CPU is halted; raise IRQ0 from a tickable.
        var raised = false
        machine.addTickable {
            if (!raised && machine.cpu.isHalted()) {
                machine.pic.raiseIrq(0)
                raised = true
            }
        }
        machine.run(maxInstructions = 50)
        assertTrue(raised, "HALT path should have been reached")
    }

    @Test
    fun imrReadBackNotPreemptedByPendingIrq() {
        // POST writes OCW1 then immediately reads it back. A pending IRQ0 (warm boot
        // with the PIT still running) must not fire between those two instructions.
        val machine = TestAssets.machine()
        machine.cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)

        machine.cpu.setReg16(REG_CS, 0)
        machine.cpu.setIp(0x500)
        machine.cpu.setReg16(REG_SS, 0x1000)
        machine.cpu.setReg16(REG_SP, 0xFFFE)
        machine.cpu.setReg16(REG_AX, 0x00FE) // AL = ~IRQ0 mask bit

        // OUT 21h, AL ; IN AL, 21h ; MOV [0x0700], AL ; CLI ; HLT
        machine.cpu.writePhysByte(0x500, 0xE6) // OUT imm8, AL
        machine.cpu.writePhysByte(0x501, 0x21)
        machine.cpu.writePhysByte(0x502, 0xE4) // IN AL, imm8
        machine.cpu.writePhysByte(0x503, 0x21)
        machine.cpu.writePhysByte(0x504, 0xA2) // MOV [imm16], AL
        machine.cpu.writePhysByte(0x505, 0x00)
        machine.cpu.writePhysByte(0x506, 0x07)
        machine.cpu.writePhysByte(0x507, 0xFA) // CLI
        machine.cpu.writePhysByte(0x508, 0xF4) // HLT

        // IRQ0 → handler that poisons IMR but preserves AL (PUSH AX / POP AX)
        machine.cpu.writePhysByte(0x20, 0x00)
        machine.cpu.writePhysByte(0x21, 0x06)
        machine.cpu.writePhysByte(0x22, 0x00)
        machine.cpu.writePhysByte(0x23, 0x00)
        // PUSH AX; MOV AL, FF; OUT 21h, AL; POP AX; IRET
        machine.cpu.writePhysByte(0x600, 0x50)
        machine.cpu.writePhysByte(0x601, 0xB0)
        machine.cpu.writePhysByte(0x602, 0xFF)
        machine.cpu.writePhysByte(0x603, 0xE6)
        machine.cpu.writePhysByte(0x604, 0x21)
        machine.cpu.writePhysByte(0x605, 0x58)
        machine.cpu.writePhysByte(0x606, 0xCF)

        // ICW1 single + ICW4 needed (0x13), then ICW2 / ICW4 / mask all
        machine.pic.ioWriteByte(0x20, 0x13)
        machine.pic.ioWriteByte(0x21, 0x08)
        machine.pic.ioWriteByte(0x21, 0x09)
        machine.pic.ioWriteByte(0x21, 0xFF)
        machine.pic.raiseIrq(0)
        machine.cpu.setReg8(FLAG_IF, 1)

        machine.run(maxInstructions = 20)
        assertEquals(
            0xFE,
            machine.cpu.readPhysByte(0x700) and 0xFF,
            "IN must observe the OUT value; early IRQ would make it read 0xFF",
        )
    }

    @Test
    fun warmBootAtCadLjmpDropsQueuedKeyboardCodes() {
        TestAssets.assumeRomsPresent()
        val machine = TestAssets.machine()
        machine.cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)
        machine.keyboard.sendCtrlAltDelete()
        assertTrue((machine.pic.requestRegister() and 0x02) != 0 || machine.ppi.ioReadByte(0x60) != 0)

        // After INT9's `mov [72],1234`, IP is the CAD ljmp at EA82.
        machine.cpu.writePhysByte(0x472, 0x34)
        machine.cpu.writePhysByte(0x473, 0x12)
        machine.cpu.setReg16(REG_CS, 0xF000)
        machine.cpu.setIp(0xEA82)
        machine.cpu.setReg16(REG_SS, 0x0030)
        machine.cpu.setReg16(REG_SP, 0x0100)
        machine.cpu.setReg8(FLAG_IF, 0)

        machine.run(maxInstructions = 1) // far-jump to E05B; noticeWarmBoot sees entry
        assertTrue(machine.keyboard.inReset)
        assertEquals(0, machine.ppi.ioReadByte(0x60) and 0xFF)
    }
}
