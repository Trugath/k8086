package com.trugath.k8086

import com.trugath.k8086.bus.IoBus
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.cpu.Emulator8088
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.REG_SS
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.*
import com.trugath.k8086.video.Cga
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * CheckIt 2.1 Video Characters/Second is essentially XT BIOS INT 10h teletype
 * (AH=0E) throughput with CGA snow-wait. A real IBM PC/XT scores ~1027 c/s.
 */
class CgaVideoThroughputTest {
    @Test
    fun int10TeletypeThroughputNearXtCheckItVideo() {
        assumeTrue(TestAssets.u18.isFile && TestAssets.u19.isFile)

        val cpu = Emulator8088()
        cpu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)
        val pic = Pic8259()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x29)
        val bus = IoBus()
        bus.map(cga, (0x3D0..0x3DF).toList())
        bus.map(pic, listOf(0x20, 0x21))
        cpu.attachIoBus(bus)
        cpu.attachInterruptSource(pic)
        pic.ioWriteByte(0x21, 0xFF)

        fun w8(a: Int, v: Int) = cpu.writePhysByte(a, v)
        fun w16(a: Int, v: Int) {
            w8(a, v and 0xFF)
            w8(a + 1, (v shr 8) and 0xFF)
        }
        w8(0x449, 3)
        w16(0x44A, 80)
        w16(0x44C, 0x1000)
        w16(0x44E, 0)
        w16(0x450, 0)
        w8(0x462, 0)
        w16(0x463, 0x3D4)
        w8(0x465, 0x29)
        w8(0x466, 0x30)
        w16(0x410, 0x002D)
        w16(0x40, 0xF065)
        w16(0x42, 0xF000)

        val body = mutableListOf<Int>()
        fun emit(vararg b: Int) = b.forEach { body.add(it and 0xFF) }
        emit(0xC7, 0x06, 0x00, 0x02, 0x00, 0x00) // mov word [0200], 0
        val loop = body.size
        emit(0xB4, 0x0E, 0xB0, 0x41, 0xBB, 0x07, 0x00, 0xCD, 0x10) // INT 10h AH=0E
        emit(0xFF, 0x06, 0x00, 0x02) // inc word [0200]
        val jmp = body.size
        emit(0xEB, 0)
        body[jmp + 1] = (loop - (jmp + 2)) and 0xFF

        cpu.setReg16(REG_SS, 0x3000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_ES, 0x1000)
        cpu.setFlagsValue(0)
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setIp(0x0100)
        for (i in body.indices) cpu.writePhysByte(0x10100 + i, body[i])

        var cycles = 0L
        var instr = 0
        val budget = 4_772_727L
        while (cycles < budget && instr < 30_000_000) {
            if (!cpu.executeSingleInstruction()) break
            instr++
            val charged = Machine.peripheralCyclesFor(cpu.lastInstructionCycles)
            cga.tickCpuCycles(charged)
            cycles += charged.toLong()
        }

        val chars = cpu.readPhysByte(0x10200) or (cpu.readPhysByte(0x10201) shl 8)
        val cps = chars * 4_772_727.0 / cycles.toDouble()
        // XT CheckIt ≈ 1027 with IBM snow-wait; rmDOS teletype is faster (~2×).
        assertTrue(
            cps in 900.0..2500.0,
            "INT 10h AH=0E cps=$cps (chars=$chars) should be in a plausible XT teletype band",
        )
    }

    @Test
    fun snowWaitHandshakeStillOneWritePerScanline() {
        val cpu = Emulator8088()
        val cga = Cga(cpu, showWindow = false)
        cpu.attachIoBus(IoBus().also { it.map(cga, (0x3D0..0x3DF).toList()) })

        val body = mutableListOf<Int>()
        fun emit(vararg b: Int) = b.forEach { body.add(it and 0xFF) }
        emit(0xC7, 0x06, 0x00, 0x02, 0x00, 0x00)
        emit(0xB8, 0x00, 0xB8, 0x8E, 0xC0) // ES=B800
        emit(0xBF, 0x00, 0x00)
        emit(0xBB, 0x41, 0x07)
        val loop = body.size
        emit(0xBA, 0xDA, 0x03)
        emit(0xEC, 0xA8, 0x01, 0x75, 0xFB) // wait active
        emit(0xFA)
        emit(0xEC, 0xA8, 0x01, 0x74, 0xFB) // wait blank
        emit(0x8B, 0xC3, 0xAB, 0xFB)
        emit(0xFF, 0x06, 0x00, 0x02)
        emit(0x81, 0xFF, 0xA0, 0x0F) // cmp di, 4000
        emit(0x72, 0x03, 0xBF, 0x00, 0x00) // jb +3 / mov di,0
        val jmp = body.size
        emit(0xEB, 0)
        body[jmp + 1] = (loop - (jmp + 2)) and 0xFF

        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setIp(0x0100)
        for (i in body.indices) cpu.writePhysByte(0x10100 + i, body[i])

        var cycles = 0L
        var instr = 0
        val budget = 4_772_727L
        while (cycles < budget && instr < 5_000_000) {
            if (!cpu.executeSingleInstruction()) break
            instr++
            val charged = Machine.peripheralCyclesFor(cpu.lastInstructionCycles)
            cga.tickCpuCycles(charged)
            cycles += charged.toLong()
        }
        val writes = cpu.readPhysByte(0x10200) or (cpu.readPhysByte(0x10201) shl 8)
        val wps = writes * 4_772_727.0 / cycles.toDouble()
        // One snow-safe word per scanline ≈ ACTIVE_LINES * (cpuHz/frame) ≈ 12k/s.
        assertTrue(wps in 11_000.0..13_000.0, "snow-wait writes/sec=$wps")
    }
}
