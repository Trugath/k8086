package com.trugath.k8086

import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.chipset.Pit8253
import com.trugath.k8086.chipset.Ppi8255
import com.trugath.k8086.cpu.CycleTables
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.FLAG_ZF
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.*
import com.trugath.k8086.video.Cga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CheckItCompatTest {
    @Test
    fun bdaRowsByteNotClobberedOnceSet() {
        val cpu = Emulator8086()
        cpu.setReg16(REG_CS, 0x1000) // not in BIOS ROM
        cpu.writePhysByte(0x484, 0)

        val cga = Cga(cpu, showWindow = false)
        // Avoid mode-OUT stamping before we observe the per-frame path: program
        // CRTC-less text enable with CS already outside BIOS and 40:84 still 0.
        cga.ioWriteByte(0x3D8, 0x29) // stamps once via onVideoConfigChanged
        assertEquals(24, cpu.readPhysByte(0x484), "leaving BIOS with rows=0 stamps 24")

        // CheckIt memory test patterns must stick — including a deliberate 00h
        // (was failing at 000484h with bits 3–4 when we rewrote 00h → 18h every frame).
        cpu.writePhysByte(0x484, 0xAA)
        repeat(4) { cga.tickCpuCycles(Cga.CYCLES_PER_FRAME) }
        assertEquals(0xAA, cpu.readPhysByte(0x484), "must not rewrite non-zero BDA 40:84")

        cpu.writePhysByte(0x484, 0)
        repeat(4) { cga.tickCpuCycles(Cga.CYCLES_PER_FRAME) }
        assertEquals(0, cpu.readPhysByte(0x484), "must not re-heal zero after the one-shot stamp")
    }

    @Test
    fun movSsArmsInterruptShadowForOneInstruction() {
        val cpu = Emulator8086()
        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x100)
        cpu.setReg16(REG_DS, 0)
        cpu.setReg16(REG_AX, 0x2000)
        // MOV SS, AX  (8E D0)
        cpu.writeInstruction(byteArrayOf(0x8E.toByte(), 0xD0.toByte()))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x2000, cpu.getReg16(REG_SS))
        assertTrue(cpu.pollSsInterruptShadow(), "MOV SS must arm IRQ shadow")
        assertFalse(cpu.pollSsInterruptShadow(), "shadow is one-shot")
    }

    @Test
    fun popSsDefersSingleStepLikeFixed8088() {
        // CheckIt 2.1 CPU Interrupt Bug (file ~0x29cf8): CLI, hook INT 1, set TF via
        // IRET, NOP, POP SS, DEC CX, HLT. Fixed silicon delays TF across POP SS so
        // DEC CX runs (CX→0) and the handler never leaves the CPU parked on HLT.
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setReg16(REG_CX, 1)
        cpu.setIp(0x0100)

        // INT 1 handler at 1000:0200 — if return IP > 0x0103 (after POP SS), finish;
        // else IRET to keep stepping.
        //   0200: push bp / mov bp,sp / cmp word [bp+2], 0x0103 / pop bp
        //   020A: ja 020D / iret
        //   020D: mov cx,0 / add sp,6 / iret   (clear CX like CheckIt's jcxz path)
        val handler = byteArrayOf(
            0x55,
            0x8B.toByte(), 0xEC.toByte(),
            0x81.toByte(), 0x7E, 0x02, 0x03, 0x01, // cmp [bp+2], 0x0103
            0x5D,
            0x77, 0x01, // ja +1 → 020D
            0xCF.toByte(), // iret
            0xB9.toByte(), 0x00, 0x00, // mov cx,0
            0x83.toByte(), 0xC4.toByte(), 0x06, // add sp,6
            0xCF.toByte(), // iret
        )
        for (i in handler.indices) cpu.writePhysByte(0x10200 + i, handler[i].toInt() and 0xFF)
        cpu.writePhysByte(0x00004, 0x00)
        cpu.writePhysByte(0x00005, 0x02)
        cpu.writePhysByte(0x00006, 0x00)
        cpu.writePhysByte(0x00007, 0x10)

        // 0100: nop / pop ss / dec cx / hlt
        // stack already has SS pushed (simulate CheckIt's push ss before TF)
        cpu.writePhysByte(0x2FFFE, 0x00)
        cpu.writePhysByte(0x2FFFF, 0x20) // POP SS → 0x2000 (same SS)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.writePhysByte(0x10100, 0x90) // nop
        cpu.writePhysByte(0x10101, 0x17) // pop ss
        cpu.writePhysByte(0x10102, 0x49) // dec cx
        cpu.writePhysByte(0x10103, 0xF4.toByte().toInt() and 0xFF) // hlt

        // Arm TF as after CheckIt's IRET-with-TF setup: next insn (NOP) will trap after.
        cpu.setFlagsValue(cpu.getFlags() or 0x100)
        cpu.trapFlag = true

        // Drive the same boundary rules as Machine.runOneIteration.
        var steps = 0
        while (steps++ < 64 && !cpu.isHalted()) {
            val ssShadow = cpu.ssIrqShadow
            cpu.ssIrqShadow = false
            if (!cpu.step()) break
            if (!ssShadow && cpu.isTrapPending()) cpu.serviceInterrupt(1)
            cpu.updateTrapPending()
        }

        assertFalse(cpu.isHalted(), "must not reach HLT when TF respects POP SS shadow")
        assertEquals(0, cpu.getReg16(REG_CX) and 0xFFFF, "DEC CX must run under the SS shadow")
        assertFalse(cpu.isTrapPending(), "trapFlag must clear after INT 1")
    }

    @Test
    fun trapFlagClearsAfterInt1Service() {
        // Regression for de1ccfc: skipping updateTrapPending after INT 1 left
        // trapFlag sticky and nested single-step forever.
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setIp(0x0100)
        // INT1 → IRET at 1000:0200
        cpu.writePhysByte(0x10200, 0xCF)
        cpu.writePhysByte(0x00004, 0x00)
        cpu.writePhysByte(0x00005, 0x02)
        cpu.writePhysByte(0x00006, 0x00)
        cpu.writePhysByte(0x00007, 0x10)
        cpu.writePhysByte(0x10100, 0x90) // nop
        cpu.writePhysByte(0x10101, 0x90) // nop
        cpu.trapFlag = true

        assertTrue(cpu.step()) // nop
        assertTrue(cpu.isTrapPending())
        cpu.serviceInterrupt(1)
        assertFalse(cpu.isTrapPending(), "pcInterrupt must clear trapFlag with FLAG_TF")
        cpu.updateTrapPending()
        assertFalse(cpu.isTrapPending())
    }


    @Test
    fun opcode0FIsPopCsOn8088() {
        // CheckIt CPU detection (and many other DOS tools) rely on 0x0F being POP CS
        // on 8088/8086. Treating it as a multi-byte no-op mis-identifies the CPU as a
        // V20 and yields 0 Dhrystone / video / math scores on the Main System Benchmark.
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.writePhysByte(0x2FFFE, 0x00)
        cpu.writePhysByte(0x2FFFF, 0x30) // word 0x3000
        cpu.writePhysByte(0x10100, 0x0F) // POP CS
        cpu.writePhysByte(0x10101, 0x90) // NOP must remain the next insn
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x3000, cpu.getReg16(REG_CS))
        assertEquals(0x0101, cpu.getIp(), "POP CS is one byte; must not skip the following opcode")
        assertEquals(0x0000, cpu.getReg16(REG_SP) and 0xFFFF)
    }

    @Test
    fun mulSetsZfFromHighHalfLike8088Silicon() {
        // CheckIt 2.1 CPU ID: xor al,al / mov al,40h / mul al / je <v20>
        // 40h*40h = 1000h → AL=0 but AH=10h. Silicon ZF reflects AH, so JE is not taken
        // (8088/8086 path). ZF from AL would stay set and mis-label the CPU as V20.
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x100)
        cpu.setReg16(REG_AX, 0) // ZF set by prior xor al,al
        cpu.setFlagsValue(0x0040)
        cpu.setReg16(REG_AX, 0x0040)
        // MUL AL (F6 E0)
        cpu.writeInstruction(byteArrayOf(0xF6.toByte(), 0xE0.toByte()))
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x1000, cpu.getReg16(REG_AX) and 0xFFFF)
        assertEquals(0, cpu.getFlag(FLAG_ZF), "ZF must clear from AH!=0, not set from AL==0")
    }

    @Test
    fun mulReg16Charges8088TypicalCycles() {
        // Flat BASE[F7]=70 under-counts MUL r16 (~118–133). CheckIt MHz probes
        // time MUL against PIT2, so this must be in band.
        val cpu = Emulator8088()
        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x100)
        cpu.setReg16(REG_AX, 0x1234)
        cpu.setReg16(REG_DI, 0x0003)
        cpu.writeInstruction(byteArrayOf(0xF7.toByte(), 0xE7.toByte())) // MUL DI
        assertTrue(cpu.executeSingleInstruction())
        assertEquals(
            CycleTables.grp3Cycles(reg = 4, word = true, memory = false),
            cpu.lastInstructionCycles,
        )
        assertTrue(cpu.lastInstructionCycles in 118..133)
    }

    @Test
    fun checkItStylePit2MulStopwatchScalesWithMulCycles() {
        // CheckIt 3.0 (and likely similar 2.1 paths): PIT2 @ 1.19318 MHz as stopwatch,
        // gate on, N× MUL DI, elapsed = -count. MHz ≈ K*N*1.19318/elapsed.
        // Undercounting MUL cycles shrinks elapsed and inflates MHz.
        val pic = Pic8259()
        val pit = Pit8253(pic)
        val ppi = Ppi8255(pit)
        val cpu = Emulator8088()

        // startPIT2-equivalent: gate off, mode 2 channel 2, reload 0 (=65536)
        ppi.ioWriteByte(0x61, ppi.portBValue() and 0xFC)
        pit.ioWriteByte(0x43, 0xB4)
        pit.ioWriteByte(0x42, 0x00)
        pit.ioWriteByte(0x42, 0x00)
        // Enable gate (PB0) like the measurement path
        ppi.ioWriteByte(0x61, (ppi.portBValue() and 0xFC) or 0x01)

        cpu.setReg16(REG_CS, 0)
        cpu.setIp(0x100)
        cpu.setReg16(REG_DI, 0x0003)
        val n = 1000
        repeat(n) {
            cpu.setReg16(REG_AX, 0x1234)
            cpu.setIp(0x100)
            cpu.writeInstruction(byteArrayOf(0xF7.toByte(), 0xE7.toByte())) // MUL DI
            assertTrue(cpu.executeSingleInstruction())
            pit.tickCpuCycles(cpu.lastInstructionCycles)
        }

        // Read without latch (CheckIt readPIT2): low then high, then NEG
        val lo = pit.ioReadByte(0x42) and 0xFF
        val hi = pit.ioReadByte(0x42) and 0xFF
        val elapsed = (-((hi shl 8) or lo)).toShort().toInt() and 0xFFFF

        val expectedPitTicks = n * CycleTables.grp3Cycles(4, word = true, memory = false) /
            Pit8253.CPU_CLOCKS_PER_PIT_CLOCK
        // Allow a few ticks of I/O skew; must not look like the old F7=70 model (~17.5k).
        assertTrue(
            abs(elapsed - expectedPitTicks) < 50,
            "elapsed=$elapsed expected≈$expectedPitTicks",
        )
        assertTrue(elapsed > 25_000, "MUL r16 stopwatch must not under-tick (was ~17.5k at 70 cyc)")
    }
}
