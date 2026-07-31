package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import com.trugath.k8086.api.IoDevice
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Emulator8086Test {
    
    @BeforeEach
    fun setUp() {
        // Tests create their own emulator instances
    }
    
    // --- Phase 0: real IBM 5155/5160 ROM BIOS mapping + 8088 reset vector.

    @Test
    fun testSystemRomResetVectorJumpsToPostEntry() {
        TestAssets.assumeRomsPresent()

        val emu = Emulator8086()
        emu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)

        // 8088 powers on at FFFF:0000 (linear 0xFFFF0).
        assertEquals(0xFFFF, emu.getReg16(REG_CS), "CS should be 0xFFFF at reset")
        assertEquals(0x0000, emu.getIp(), "IP should be 0x0000 at reset")

        // The reset vector holds EA 5B E0 00 F0 = JMP FAR F000:E05B (POST entry).
        assertTrue(emu.executeSingleInstruction(), "Reset far-jump should execute")
        assertEquals(0xF000, emu.getReg16(REG_CS), "CS should be F000 after the reset far jump")
        assertEquals(0xE05B, emu.getIp(), "IP should be E05B (POST entry) after the reset far jump")
    }

    @Test
    fun testSegmentOffsetWrapsAtTwentyBits() {
        val emu = Emulator8086()
        emu.setReg16(REG_CS, 0xFFFF)
        emu.setIp(0x0010) // FFFF:0010 wraps to physical 00000
        emu.writePhysByte(0x00000, 0x90) // NOP

        assertTrue(emu.executeSingleInstruction())
        assertEquals(0x0011, emu.getIp())
    }

    @Test
    fun testInstructionDecodeWrapsAtSixteenBitIp() {
        val emu = Emulator8086()
        emu.setReg16(REG_CS, 0x1000)
        emu.setIp(0xFFFF)
        emu.writePhysByte(0x1FFFF, 0xB0) // MOV AL, imm8 at CS:FFFF
        emu.writePhysByte(0x10000, 0x7A) // immediate wraps to CS:0000

        assertTrue(emu.executeSingleInstruction())
        assertEquals(0x7A, emu.getReg8(REG_AL))
        assertEquals(0x0001, emu.getIp())
    }

    @Test
    fun testSystemRomIsWriteProtected() {
        TestAssets.assumeRomsPresent()

        val emu = Emulator8086()
        emu.loadSystemRoms(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath)

        val resetByte = emu.getMem(0xFFFF0)
        // MOV byte [0xFFFF0], 0x00 via a direct store using the CPU write path.
        emu.setReg16(REG_CS, 0x0000)
        emu.setIp(0x0000)
        emu.setReg16(REG_ES, 0xFFFF)
        emu.setReg16(REG_DI, 0x0000)
        // Write through the emulator's guest-store path (STOSB with AL=0x00).
        emu.setReg8(REG_AL, 0x00)
        emu.writeInstruction(byteArrayOf(0xAA.toByte())) // STOSB -> ES:DI
        emu.executeSingleInstruction()
        assertEquals(resetByte, emu.getMem(0xFFFF0), "ROM byte must be unchanged by a guest write")
    }

    // --- Phase 1: I/O bus device dispatch. Mapped ports route to a device; unmapped
    // ports float high (open bus) and read back 0xFF, as on the real XT.

    @Test
    fun testIoBusRoutesMappedPortsToDevice() {
        val emu = Emulator8086()

        val captured = HashMap<Int, Int>()
        val device = object : IoDevice {
            override fun ioReadByte(port: Int): Int = if (port == 0x3D9) 0xA5 else 0x00
            override fun ioWriteByte(port: Int, value: Int) { captured[port] = value }
        }
        val bus = IoBus()
        bus.map(device, 0x3D8, 0x3D9)
        emu.attachIoBus(bus)

        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.setReg8(REG_AL, 0x5C)
        // OUT 0x3D8, AL is 4 bytes as DX-form; use imm8 form on a low port instead by
        // driving DX and the DX-form OUT: MOV DX,0x3D8 is awkward here, so exercise the
        // device via the word/byte helpers using the DX-indirect OUT (EE) with DX set.
        emu.setReg16(REG_DX, 0x3D8)
        emu.writeInstruction(byteArrayOf(0xEE.toByte())) // OUT DX, AL
        assertTrue(emu.executeSingleInstruction(), "OUT DX,AL should execute")
        assertEquals(0x5C, captured[0x3D8], "Device should receive the OUT to a mapped port")

        // IN AL, DX from 0x3D9 should read from the device.
        emu.setIp(0x200)
        emu.setReg16(REG_DX, 0x3D9)
        emu.writeInstruction(byteArrayOf(0xEC.toByte())) // IN AL, DX
        assertTrue(emu.executeSingleInstruction(), "IN AL,DX should execute")
        assertEquals(0xA5, emu.getReg8(REG_AL), "IN should read from the mapped device")
    }

    @Test
    fun testIoBusUnmappedPortsReadOpenBus() {
        val emu = Emulator8086()
        emu.attachIoBus(IoBus()) // no devices mapped
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.writeInstruction(byteArrayOf(0xE4.toByte(), 0x51.toByte())) // IN AL, 0x51
        assertTrue(emu.executeSingleInstruction(), "IN should execute")
        // An undecoded port floats high on the XT bus, so IN reads 0xFF regardless of
        // any prior OUT to that port.
        assertEquals(0xFF, emu.getReg8(REG_AL), "Unmapped port should read open-bus 0xFF")
    }

    // Test MOV reg, imm (8-bit)
    @Test
    fun testMovReg8Imm() {
        val testEmulator = Emulator8086()
        
        // MOV AL, 0x42 (B0 42)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.writeInstruction(byteArrayOf(0xB0.toByte(), 0x42.toByte()))
        
        val initialIp = testEmulator.getIp()
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x42, testEmulator.getReg8(REG_AL), "AL should be 0x42")
        assertEquals(initialIp + 2, testEmulator.getIp(), "IP should increment by 2")
    }
    
    // Test MOV reg, imm (16-bit)
    @Test
    fun testMovReg16Imm() {
        val testEmulator = Emulator8086()
        
        // MOV AX, 0x1234 (B8 34 12)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.writeInstruction(byteArrayOf(0xB8.toByte(), 0x34.toByte(), 0x12.toByte()))
        
        val initialIp = testEmulator.getIp()
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x1234, testEmulator.getReg16(REG_AX), "AX should be 0x1234")
        assertEquals(initialIp + 3, testEmulator.getIp(), "IP should increment by 3")
    }
    
    // Test MOV reg, reg (8-bit)
    @Test
    fun testMovReg8Reg8() {
        val testEmulator = Emulator8086()
        
        // MOV AL, BL (88 D8) - mod=11, reg=000 (AL), rm=011 (BL)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0xAB)
        testEmulator.writeInstruction(byteArrayOf(0x88.toByte(), 0xD8.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0xAB, testEmulator.getReg8(REG_AL), "AL should equal BL (0xAB)")
    }
    
    // Test MOV reg, reg (16-bit)
    @Test
    fun testMovReg16Reg16() {
        val testEmulator = Emulator8086()
        
        // MOV AX, BX (89 D8) - mod=11, reg=000 (AX), rm=011 (BX)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_BX, 0x5678)
        testEmulator.writeInstruction(byteArrayOf(0x89.toByte(), 0xD8.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x5678, testEmulator.getReg16(REG_AX), "AX should equal BX (0x5678)")
    }
    
    // Test ADD reg, imm
    @Test
    fun testAddRegImm() {
        val testEmulator = Emulator8086()
        
        // ADD AL, 0x10 (04 10)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x20)
        testEmulator.writeInstruction(byteArrayOf(0x04.toByte(), 0x10.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x30, testEmulator.getReg8(REG_AL), "AL should be 0x30 (0x20 + 0x10)")
    }
    
    // Test ADD reg, reg
    @Test
    fun testAddRegReg() {
        val testEmulator = Emulator8086()
        
        // ADD AL, BL (00 D8) - mod=11, reg=000 (ADD), rm=011 (BL)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x10)
        testEmulator.setReg8(REG_BL, 0x20)
        testEmulator.writeInstruction(byteArrayOf(0x00.toByte(), 0xD8.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x30, testEmulator.getReg8(REG_AL), "AL should be 0x30 (0x10 + 0x20)")
    }
    
    // Test SUB reg, imm
    @Test
    fun testSubRegImm() {
        val testEmulator = Emulator8086()
        
        // SUB AL, 0x05 (2C 05)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x10)
        testEmulator.writeInstruction(byteArrayOf(0x2C.toByte(), 0x05.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x0B, testEmulator.getReg8(REG_AL), "AL should be 0x0B (0x10 - 0x05)")
    }
    
    // Test AND reg, imm
    @Test
    fun testAndRegImm() {
        val testEmulator = Emulator8086()
        
        // AND AL, 0x0F (24 0F)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x3A)
        testEmulator.writeInstruction(byteArrayOf(0x24.toByte(), 0x0F.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x0A, testEmulator.getReg8(REG_AL), "AL should be 0x0A (0x3A & 0x0F)")
    }
    
    // Test OR reg, imm
    @Test
    fun testOrRegImm() {
        val testEmulator = Emulator8086()
        
        // OR AL, 0x0F (0C 0F)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0xA0)
        testEmulator.writeInstruction(byteArrayOf(0x0C.toByte(), 0x0F.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0xAF, testEmulator.getReg8(REG_AL), "AL should be 0xAF (0xA0 | 0x0F)")
    }
    
    // Test XOR reg, imm
    @Test
    fun testXorRegImm() {
        val testEmulator = Emulator8086()
        
        // XOR AL, 0xFF (34 FF)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x55)
        testEmulator.writeInstruction(byteArrayOf(0x34.toByte(), 0xFF.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0xAA, testEmulator.getReg8(REG_AL), "AL should be 0xAA (0x55 ^ 0xFF)")
    }
    
    // Test CMP reg, imm
    @Test
    fun testCmpRegImm() {
        val testEmulator = Emulator8086()
        
        // CMP AL, 0x10 (3C 10)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x20)
        testEmulator.writeInstruction(byteArrayOf(0x3C.toByte(), 0x10.toByte()))
        
        val initialAl = testEmulator.getReg8(REG_AL)
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(initialAl, testEmulator.getReg8(REG_AL), "AL should not change after CMP")
        assertEquals(0, testEmulator.getFlag(FLAG_ZF), "ZF should be 0 (0x20 != 0x10)")
    }
    
    // Test INC reg (8-bit)
    @Test
    fun testIncReg8() {
        val testEmulator = Emulator8086()
        
        // INC AL (FE C0) - mod=11, reg=000 (INC), rm=000 (AL)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x42)
        testEmulator.writeInstruction(byteArrayOf(0xFE.toByte(), 0xC0.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x43, testEmulator.getReg8(REG_AL), "AL should be 0x43 (0x42 + 1)")
    }
    
    // Test DEC reg (8-bit)
    @Test
    fun testDecReg8() {
        val testEmulator = Emulator8086()
        
        // DEC AL (FE C8) - mod=11, reg=001 (DEC), rm=000 (AL)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x42)
        testEmulator.writeInstruction(byteArrayOf(0xFE.toByte(), 0xC8.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x41, testEmulator.getReg8(REG_AL), "AL should be 0x41 (0x42 - 1)")
    }
    
    // Test PUSH reg
    @Test
    fun testPushReg() {
        val testEmulator = Emulator8086()
        
        // PUSH AX (50) - reg=000 (AX)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg16(REG_AX, 0x1234)
        testEmulator.writeInstruction(byteArrayOf(0x50.toByte()))
        
        val initialSp = testEmulator.getReg16(REG_SP)
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(initialSp - 2, testEmulator.getReg16(REG_SP), "SP should decrement by 2")
        
        // Check value on stack
        val stackAddr = testEmulator.segregPublic(REG_SS, REG_ZERO, testEmulator.getReg16(REG_SP))
        val memArray = testEmulator.getMemArray()
        val stackValue = ByteBuffer.wrap(memArray, stackAddr, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(0x1234, stackValue, "Stack should contain 0x1234")
    }
    
    @Test
    fun testPushSpStoresPostDecrementValueOn8086() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.writeInstruction(byteArrayOf(0x54.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0x00FE, testEmulator.getReg16(REG_SP))
        val stackAddr = testEmulator.segregPublic(REG_SS, REG_ZERO, 0x00FE)
        val stackValue = ByteBuffer.wrap(testEmulator.getMemArray(), stackAddr, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(0x00FE, stackValue)
    }

    @Test
    fun testFfPushSpStoresPostDecrementValue() {
        val emu = Emulator8086()
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.setReg16(REG_SS, 0x2000)
        emu.setReg16(REG_SP, 0x0100)
        // FF F4 = PUSH SP (reg form, /6)
        emu.writeInstruction(byteArrayOf(0xFF.toByte(), 0xF4.toByte()))

        assertTrue(emu.executeSingleInstruction())
        assertEquals(0x00FE, emu.getReg16(REG_SP))
        val stackAddr = emu.segregPublic(REG_SS, REG_ZERO, 0x00FE)
        val stackValue = ByteBuffer.wrap(emu.getMemArray(), stackAddr, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(0x00FE, stackValue)
    }

    @Test
    fun testCallSpUsesPreDecrementSpAsTarget() {
        val emu = Emulator8086()
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.setReg16(REG_SS, 0x2000)
        emu.setReg16(REG_SP, 0x0455)
        // FF D4 = CALL SP
        emu.writeInstruction(byteArrayOf(0xFF.toByte(), 0xD4.toByte()))

        assertTrue(emu.executeSingleInstruction())
        assertEquals(0x0453, emu.getReg16(REG_SP))
        assertEquals(0x0455, emu.getIp(), "CALL SP must jump to the pre-push SP")
    }

    // Test POP reg
    @Test
    fun testPopReg() {
        val testEmulator = Emulator8086()
        
        // POP AX (58) - reg=000 (AX)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x00FE)
        // Put value on stack
        val stackAddr = testEmulator.segregPublic(REG_SS, REG_ZERO, testEmulator.getReg16(REG_SP))
        val stackBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x5678.toShort()).array()
        testEmulator.setMem(stackAddr, stackBytes[0].toUByte().toInt())
        testEmulator.setMem(stackAddr + 1, stackBytes[1].toUByte().toInt())
        
        testEmulator.writeInstruction(byteArrayOf(0x58.toByte()))
        
        val initialSp = testEmulator.getReg16(REG_SP)
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        val finalSp = testEmulator.getReg16(REG_SP)
        val finalAx = testEmulator.getReg16(REG_AX)
        // C code: R_M_POP increments SP by 2, then reads from old location
        assertEquals(initialSp + 2, finalSp, "SP should increment by 2 (was $initialSp, got $finalSp)")
        assertEquals(0x5678, finalAx, "AX should be 0x5678 (got 0x${finalAx.toString(16)})")
    }
    
    // Test JMP short
    @Test
    fun testJmpShort() {
        val testEmulator = Emulator8086()
        
        // JMP +10 (EB 0A)
        // For opcode 0xEB, i_reg4bit = 0xEB & 7 = 3, so i_w = 3 & 1 = 1 and i_d = 3/2 & 1 = 1.
        // C code: reg_ip += 3 - i_d (= 2), then (since i_d && i_w) reg_ip += (char)i_data0 (0x0A).
        // This matches real 8086 semantics: JMP short target = address-after-instruction (IP+2) + disp8.
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.writeInstruction(byteArrayOf(0xEB.toByte(), 0x0A.toByte()))

        val initialIp = testEmulator.getIp()
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        val finalIp = testEmulator.getIp()
        // Total: initialIp + 2 (instruction length) + 0x0A (displacement) = initialIp + 12
        val expectedIp = initialIp + 2 + 0x0A
        assertEquals(expectedIp, finalIp, "IP should be $expectedIp (was $initialIp, got $finalIp)")
    }
    
    @Test
    fun testUndocumented60OpcodeAliasesJoOn8086() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x0200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_OF, 1)
        testEmulator.writeInstruction(byteArrayOf(0x60.toByte(), 0x05.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0x0207, testEmulator.getIp())
    }

    @Test
    fun testMovFromSegmentAliasesHighModRmRegisterBit() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x0200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_ES, 0xB362)
        // MOV BP, ES with ModR/M reg=4, which aliases reg=0 on 8086.
        testEmulator.writeInstruction(byteArrayOf(0x8C.toByte(), 0xE5.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0xB362, testEmulator.getReg16(REG_BP))
    }

    // Test TEST reg, reg
    @Test
    fun testTestRegReg() {
        val testEmulator = Emulator8086()
        
        // TEST AL, AL (84 C0) - mod=11, reg=000 (TEST), rm=000 (AL)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x00)
        testEmulator.writeInstruction(byteArrayOf(0x84.toByte(), 0xC0.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x00, testEmulator.getReg8(REG_AL), "AL should not change")
        assertEquals(1, testEmulator.getFlag(FLAG_ZF), "ZF should be 1 (0x00 & 0x00 == 0)")
    }
    
    // Test NOP
    @Test
    fun testNop() {
        val testEmulator = Emulator8086()
        
        // NOP (90)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        val initialIp = testEmulator.getIp()
        testEmulator.writeInstruction(byteArrayOf(0x90.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(initialIp + 1, testEmulator.getIp(), "IP should increment by 1")
    }
    
    // Test CLC (Clear Carry Flag)
    @Test
    fun testClc() {
        val testEmulator = Emulator8086()
        
        // CLC (F8)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_CF, 1)
        testEmulator.writeInstruction(byteArrayOf(0xF8.toByte()))
        
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should be 0")
    }
    
    // Test STC (Set Carry Flag)
    @Test
    fun testStc() {
        val testEmulator = Emulator8086()

        // STC (F9)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_CF, 0)
        testEmulator.writeInstruction(byteArrayOf(0xF9.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be 1")
    }

    // Regression test for the segreg() operator-precedence bug: Kotlin's infix `and`
    // binds looser than `+`, so `16*seg + (ofs+offset) and 0xFFFF` masked the WHOLE
    // linear address instead of just the offset. Invisible with 4K-aligned segments
    // (where 16*seg is already a multiple of 0x10000), so use a non-aligned segment.
    @Test
    fun testSegregOnlyMasksOffsetNotWholeAddress() {
        val testEmulator = Emulator8086()

        testEmulator.setReg16(REG_CS, 0x1234)
        testEmulator.setIp(0x0010)
        // MOV AL, 0x77 (B0 77)
        testEmulator.writeInstruction(byteArrayOf(0xB0.toByte(), 0x77.toByte()))

        // Correct linear address is seg*16+offset, unmasked; check the byte actually
        // landed there directly (not via segreg again, to avoid a self-cancelling bug).
        val expectedAddr = 0x1234 * 16 + 0x0010
        assertEquals(0xB0, testEmulator.getMem(expectedAddr), "Opcode byte should be at seg*16+offset")
        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x77, testEmulator.getReg8(REG_AL), "AL should be set from the instruction fetched at the correct linear address")

        // Offset should still wrap at 64K within the segment (only the offset is masked).
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0xFFFE)
        val wrapAddr = testEmulator.segregPublic(REG_SS, REG_SP, 4)
        val expectedWrapAddr = 0x2000 * 16 + 0x0002
        assertEquals(expectedWrapAddr, wrapAddr, "Offset (0xFFFE+4) should wrap to 0x0002 within the segment, not carry into the segment base")
    }

    // Regression test for pcInterrupt(): software INT must fire regardless of IF
    // (only the hardware timer/keyboard tick is IF-gated, by the caller), and must
    // not touch CF.
    @Test
    fun testInterruptFiresRegardlessOfInterruptFlagAndLeavesCarryUnchanged() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg8(FLAG_IF, 0) // interrupts disabled
        testEmulator.setReg8(FLAG_CF, 1) // sentinel: must survive the interrupt untouched

        // Interrupt vector 3 -> CS:IP = 0x9000:0x0050
        val vectorAddr = 3 * 4
        testEmulator.setMem(vectorAddr, 0x50)
        testEmulator.setMem(vectorAddr + 1, 0x00)
        testEmulator.setMem(vectorAddr + 2, 0x00)
        testEmulator.setMem(vectorAddr + 3, 0x90)

        // INT3 (CC)
        testEmulator.writeInstruction(byteArrayOf(0xCC.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "INT3 should execute")

        assertEquals(0x0050, testEmulator.getIp(), "IP should jump to the INT3 vector even though IF=0")
        assertEquals(0x9000, testEmulator.getReg16(REG_CS), "CS should be loaded from the vector")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF must be left unchanged by the interrupt")
        assertEquals(0, testEmulator.getFlag(FLAG_IF), "IF should be cleared on interrupt entry")
    }

    // Regression test for XCHG AX,reg16 (opcode 16 -> 24 fallthrough that was missing).
    @Test
    fun testXchgAxReg16() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x1111)
        testEmulator.setReg16(REG_BX, 0x2222)
        // XCHG AX, BX (93)
        testEmulator.writeInstruction(byteArrayOf(0x93.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "XCHG should execute")
        assertEquals(0x2222, testEmulator.getReg16(REG_AX), "AX should now hold BX's old value")
        assertEquals(0x1111, testEmulator.getReg16(REG_BX), "BX should now hold AX's old value")
    }

    // Regression test for setAFOfArith(): AF was hardcoded off regs8[REG_AL] regardless
    // of the actual destination register.
    @Test
    fun testAddSetsAuxiliaryFlagBasedOnActualDestinationNotAl() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_BX, 0x000F)
        testEmulator.setReg16(REG_CX, 0x0001)
        testEmulator.setReg8(REG_AL, 0x00) // AL has no nibble carry - would mask the bug
        // ADD BX, CX (01 CB): mod=11, reg=001(CX, source), rm=011(BX, dest)
        testEmulator.writeInstruction(byteArrayOf(0x01.toByte(), 0xCB.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "ADD should execute")
        assertEquals(0x0010, testEmulator.getReg16(REG_BX), "BX should be 0x000F + 0x0001 = 0x0010")
        assertEquals(1, testEmulator.getFlag(FLAG_AF), "AF should reflect BX's nibble carry, not AL's")
    }

    // Regression test for setAFOfArith(): OF's dead "opSource < 0" branch meant
    // signed-overflow-on-subtraction was never detected.
    @Test
    fun testSubSetsOverflowFlagOnSignedOverflow() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x80) // -128
        // SUB AL, 1 (2C 01)
        testEmulator.writeInstruction(byteArrayOf(0x2C.toByte(), 0x01.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "SUB should execute")
        assertEquals(0x7F, testEmulator.getReg8(REG_AL), "AL should wrap to 0x7F")
        assertEquals(1, testEmulator.getFlag(FLAG_OF), "OF should be set: -128 - 1 signed-overflows")
    }

    // Regression tests for DAA/DAS: the low-nibble-adjust CF check compared an
    // untruncated Int against itself (e.g. `al < al+6`), which is always true,
    // forcing CF=1 unconditionally whenever the adjust branch triggered.
    @Test
    fun testDaaDoesNotForceCarryWhenNoWraparoundOccurs() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x0A) // low nibble 0xA > 9, triggers the +6 adjust
        testEmulator.setReg8(FLAG_AF, 0)
        testEmulator.setReg8(FLAG_CF, 0)
        // DAA (27): 0x0A + 6 = 0x10, no unsigned-byte wraparound -> CF should stay 0
        testEmulator.writeInstruction(byteArrayOf(0x27.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DAA should execute")
        assertEquals(0x10, testEmulator.getReg8(REG_AL), "AL should be 0x0A + 6 = 0x10")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should NOT be set: no wraparound occurred")
    }

    @Test
    fun testDaaSetsCarryWhenWraparoundOccurs() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0xFF)
        testEmulator.setReg8(FLAG_AF, 0)
        testEmulator.setReg8(FLAG_CF, 0)
        // DAA (27): 0xFF + 6 = 0x105 -> truncates to 0x05 (wraps) -> CF=1, which then
        // also triggers the high-nibble +0x60 cascade: 0x05 + 0x60 = 0x65
        testEmulator.writeInstruction(byteArrayOf(0x27.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DAA should execute")
        assertEquals(0x65, testEmulator.getReg8(REG_AL), "AL should be 0x65 after both adjust stages")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be set: wraparound occurred")
    }

    @Test
    fun testDaaAfOnlyBoundaryDoesNotTriggerHighAdjust() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x9A)
        testEmulator.setReg8(FLAG_AF, 1)
        testEmulator.setReg8(FLAG_CF, 0)
        testEmulator.writeInstruction(byteArrayOf(0x27.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0xA0, testEmulator.getReg8(REG_AL))
        assertEquals(0, testEmulator.getFlag(FLAG_CF))
        assertEquals(1, testEmulator.getFlag(FLAG_AF))
    }

    @Test
    fun testDasDoesNotForceCarryWhenNoWraparoundOccurs() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x0A) // low nibble 0xA > 9, triggers the -6 adjust
        testEmulator.setReg8(FLAG_AF, 0)
        testEmulator.setReg8(FLAG_CF, 0)
        // DAS (2F): 0x0A - 6 = 0x04, no unsigned-byte underflow -> CF should stay 0
        testEmulator.writeInstruction(byteArrayOf(0x2F.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DAS should execute")
        assertEquals(0x04, testEmulator.getReg8(REG_AL), "AL should be 0x0A - 6 = 0x04")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should NOT be set: no wraparound occurred")
    }

    @Test
    fun testDasAfOnlyAdjustDoesNotSetCarry() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x03)
        testEmulator.setReg8(FLAG_AF, 1) // force the low-nibble branch (low nibble 3 is not > 9)
        testEmulator.setReg8(FLAG_CF, 0)
        // The low-stage byte borrow neither selects the high stage nor sets CF;
        // 8086 silicon derives CF from the high-adjust predicate.
        testEmulator.writeInstruction(byteArrayOf(0x2F.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DAS should execute")
        assertEquals(0xFD, testEmulator.getReg8(REG_AL), "AL should contain only the low-stage adjustment")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should remain clear without a high adjustment")
    }

    @Test
    fun testDasZeroWithAfBorrowsWithoutHighAdjust() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x00)
        testEmulator.setReg8(FLAG_AF, 1)
        testEmulator.setReg8(FLAG_CF, 0)
        testEmulator.writeInstruction(byteArrayOf(0x2F.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0xFA, testEmulator.getReg8(REG_AL))
        assertEquals(0, testEmulator.getFlag(FLAG_CF))
    }

    // Regression test for getRegAddr(): C's GET_REG_ADDR macro is
    // `2*reg_id + reg_id/4 & 7` where `&` binds lower than `+`, i.e.
    // `(2*reg_id + reg_id/4) & 7`. Kotlin's infix `and` also binds looser than `+`,
    // so the previous parenthesization computed a different (wrong) address for
    // byte reg codes 4-7 (AH/CH/DH/BH), aliasing into unrelated registers instead.
    // MOV AH,imm8 (0xB4) exercises this directly via the "MOV reg,imm" opcode group.
    @Test
    fun testMovToAhUsesCorrectRegisterAddress() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x0000)
        // MOV AH, 0x42 (B4 42)
        testEmulator.writeInstruction(byteArrayOf(0xB4.toByte(), 0x42.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "Instruction should execute")
        assertEquals(0x42, testEmulator.getReg8(REG_AH), "AH should be 0x42")
        assertEquals(0x00, testEmulator.getReg8(REG_AL), "AL should be unaffected")
        assertEquals(0x4200, testEmulator.getReg16(REG_AX), "AX should combine the updated AH with unchanged AL")
    }

    // --- xlat opcode 6 (previously entirely unimplemented): TEST r/m,imm16 and
    // NOT/NEG/MUL/IMUL/DIV/IDIV reg, via the 0xF6/0xF7 GRP3 byte-form opcodes.
    // ModRM byte for each: 11(mod=reg-direct) iii(sub-opcode) 011(rm=BL) = 0xC0 | (iii<<3) | 3.

    @Test
    fun testGrp3TestDoesNotModifyDestination() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0x30)
        // TEST BL, 0x0F (F6 C3 0F)
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xC3.toByte(), 0x0F.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "TEST should execute")
        assertEquals(0x30, testEmulator.getReg8(REG_BL), "TEST must not modify the destination")
        assertEquals(1, testEmulator.getFlag(FLAG_ZF), "ZF should be 1: 0x30 & 0x0F == 0")
    }

    @Test
    fun testGrp3TestAliasReg1ConsumesImmediate() {
        val emu = Emulator8086()
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.setReg8(REG_BL, 0x30)
        // F6 CB 0F = TEST BL, 0x0F via undocumented /1 alias of /0
        emu.writeInstruction(byteArrayOf(0xF6.toByte(), 0xCB.toByte(), 0x0F.toByte()))

        assertTrue(emu.executeSingleInstruction())
        assertEquals(0x203, emu.getIp(), "alias TEST must consume the imm8")
        assertEquals(0x30, emu.getReg8(REG_BL))
        assertEquals(1, emu.getFlag(FLAG_ZF))
    }

    @Test
    fun testRepIdivNegatesQuotient() {
        val emu = Emulator8086()
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.setReg16(REG_AX, 0x0B9F) // 2975
        emu.setReg8(REG_BL, 0xBE) // -66
        // F3 F6 FB = REP IDIV BL → quotient negated vs plain IDIV
        emu.writeInstruction(byteArrayOf(0xF3.toByte(), 0xF6.toByte(), 0xFB.toByte()))

        assertTrue(emu.executeSingleInstruction()) // REP prefix
        assertTrue(emu.executeSingleInstruction()) // IDIV
        assertEquals(0x2D, emu.getReg8(REG_AL), "REP IDIV should negate the quotient")
        assertEquals(0x05, emu.getReg8(REG_AH), "remainder stays from the pre-negate division")
    }

    @Test
    fun testGrp3Not() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0x0F)
        // NOT BL (F6 D3)
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xD3.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "NOT should execute")
        assertEquals(0xF0, testEmulator.getReg8(REG_BL), "BL should be bitwise complement of 0x0F")
    }

    @Test
    fun testGrp3NegNonZero() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0x01)
        // NEG BL (F6 DB)
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xDB.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "NEG should execute")
        assertEquals(0xFF, testEmulator.getReg8(REG_BL), "NEG of 0x01 should be 0xFF")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be set: operand was nonzero")
    }

    @Test
    fun testGrp3NegZero() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0x00)
        // NEG BL (F6 DB)
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xDB.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "NEG should execute")
        assertEquals(0x00, testEmulator.getReg8(REG_BL), "NEG of 0 should be 0")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should be clear: operand was zero")
    }

    @Test
    fun testGrp3MulByteWithOverflow() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 200)
        testEmulator.setReg8(REG_BL, 200)
        // MUL BL (F6 E3): 200*200 = 40000 = 0x9C40
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xE3.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "MUL should execute")
        assertEquals(0x9C40, testEmulator.getReg16(REG_AX), "AX should be 200*200 = 0x9C40")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be set: AH != 0")
        assertEquals(1, testEmulator.getFlag(FLAG_OF), "OF should be set: AH != 0")
    }

    @Test
    fun testGrp3MulByteNoOverflow() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 10)
        testEmulator.setReg8(REG_BL, 10)
        // MUL BL (F6 E3): 10*10 = 100, fits in AL alone
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xE3.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "MUL should execute")
        assertEquals(100, testEmulator.getReg16(REG_AX), "AX should be 10*10=100")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should be clear: AH == 0")
    }

    @Test
    fun testGrp3ImulByteNoOverflow() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0xFE) // -2
        testEmulator.setReg8(REG_BL, 0xFD) // -3
        // IMUL BL (F6 EB): -2 * -3 = 6
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xEB.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "IMUL should execute")
        assertEquals(6, testEmulator.getReg16(REG_AX), "AX should be -2 * -3 = 6")
        assertEquals(0, testEmulator.getFlag(FLAG_CF), "CF should be clear: result fits in a signed byte")
    }

    @Test
    fun testGrp3DivByte() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 203)
        testEmulator.setReg8(REG_BL, 10)
        // DIV BL (F6 F3): 203 / 10 = 20 remainder 3
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xF3.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DIV should execute")
        assertEquals(20, testEmulator.getReg8(REG_AL), "AL should hold the quotient 20")
        assertEquals(3, testEmulator.getReg8(REG_AH), "AH should hold the remainder 3")
    }

    @Test
    fun testGrp3DivByZeroTriggersDivideErrorInterrupt() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg16(REG_AX, 100)
        testEmulator.setReg8(REG_BL, 0)
        // Interrupt vector 0 -> CS:IP = 0x9000:0x0060
        testEmulator.setMem(0, 0x60)
        testEmulator.setMem(1, 0x00)
        testEmulator.setMem(2, 0x00)
        testEmulator.setMem(3, 0x90)
        // DIV BL (F6 F3)
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xF3.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DIV should execute (via INT 0)")
        assertEquals(0x0060, testEmulator.getIp(), "IP should jump to the divide-error vector")
        assertEquals(0x9000, testEmulator.getReg16(REG_CS), "CS should be loaded from vector 0")
    }

    @Test
    fun testGrp3DivQuotientOverflowTriggersDivideErrorInterrupt() {
        val testEmulator = Emulator8086()

        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg16(REG_AX, 0x0100) // 256
        testEmulator.setReg8(REG_BL, 1)
        // Interrupt vector 0 -> CS:IP = 0x9000:0x0060
        testEmulator.setMem(0, 0x60)
        testEmulator.setMem(1, 0x00)
        testEmulator.setMem(2, 0x00)
        testEmulator.setMem(3, 0x90)
        // DIV BL (F6 F3): quotient 256 does not fit in AL (0-255)
        testEmulator.writeInstruction(byteArrayOf(0xF6.toByte(), 0xF3.toByte()))

        assertTrue(testEmulator.executeSingleInstruction(), "DIV should execute (via INT 0)")
        assertEquals(0x0060, testEmulator.getIp(), "IP should jump to the divide-error vector: quotient overflow")
        assertEquals(0x9000, testEmulator.getReg16(REG_CS), "CS should be loaded from vector 0")
    }

    // --- Shift/rotate group (xlat 12), via 0xD0 (byte, shift/rotate by 1) and
    // 0xD2 (byte, shift/rotate by CL). ModRM = 11(mod) iii(reg=op) rm.

    @Test
    fun testRolByOne() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x81)
        // ROL AL, 1 (D0 C0)
        testEmulator.writeInstruction(byteArrayOf(0xD0.toByte(), 0xC0.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "ROL should execute")
        assertEquals(0x03, testEmulator.getReg8(REG_AL), "0x81 rotated left by 1 is 0x03")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should carry the rotated-out MSB")
    }

    @Test
    fun testShlByOne() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0x81)
        // SHL BL, 1 (D0 E3)
        testEmulator.writeInstruction(byteArrayOf(0xD0.toByte(), 0xE3.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "SHL should execute")
        assertEquals(0x02, testEmulator.getReg8(REG_BL), "0x81 shifted left by 1 is 0x02")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should carry the shifted-out MSB")
    }

    @Test
    fun testShrByOne() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_BL, 0x01)
        // SHR BL, 1 (D0 EB)
        testEmulator.writeInstruction(byteArrayOf(0xD0.toByte(), 0xEB.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "SHR should execute")
        assertEquals(0x00, testEmulator.getReg8(REG_BL), "0x01 shifted right by 1 is 0x00")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should carry the shifted-out LSB")
    }

    @Test
    fun testShlByClCount() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x01)
        testEmulator.setReg8(REG_CL, 3)
        // SHL AL, CL (D2 E0)
        testEmulator.writeInstruction(byteArrayOf(0xD2.toByte(), 0xE0.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "SHL by CL should execute")
        assertEquals(0x08, testEmulator.getReg8(REG_AL), "0x01 shifted left by 3 is 0x08")
    }

    @Test
    fun test8086PreservesUndefinedOverflowForMultiBitRotate() {
        val cpu = Emulator8086()
        cpu.setIp(0x200)
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg8(REG_AL, 0x81)
        cpu.setReg8(REG_CL, 2)
        cpu.setReg8(FLAG_OF, 1)
        cpu.writeInstruction(byteArrayOf(0xD2.toByte(), 0xC0.toByte())) // ROL AL, CL

        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x06, cpu.getReg8(REG_AL))
        assertEquals(1, cpu.getFlag(FLAG_OF), "8086 leaves OF undefined for count > 1")
    }

    @Test
    fun test8088DefinesOverflowForMultiBitRotate() {
        val cpu = Emulator8088()
        cpu.setIp(0x200)
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg8(REG_AL, 0x81)
        cpu.setReg8(REG_CL, 2)
        cpu.setReg8(FLAG_OF, 1)
        cpu.writeInstruction(byteArrayOf(0xD2.toByte(), 0xC0.toByte())) // ROL AL, CL

        assertTrue(cpu.executeSingleInstruction())
        assertEquals(0x06, cpu.getReg8(REG_AL))
        assertEquals(0, cpu.getFlag(FLAG_OF), "8088 defines OF for every non-zero count")
    }

    // --- String ops (xlat 17/18) and REP prefix (xlat 23).

    @Test
    fun testMovsbCopiesByteAndAdvancesPointers() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        testEmulator.setReg16(REG_ES, 0x1000)
        testEmulator.setReg16(REG_SI, 0x0300)
        testEmulator.setReg16(REG_DI, 0x0400)
        testEmulator.setReg8(FLAG_DF, 0)
        val srcAddr = 0x1000 * 16 + 0x0300
        val dstAddr = 0x1000 * 16 + 0x0400
        testEmulator.setMem(srcAddr, 0xAB)
        // MOVSB (A4)
        testEmulator.writeInstruction(byteArrayOf(0xA4.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "MOVSB should execute")
        assertEquals(0xAB, testEmulator.getMem(dstAddr), "Destination byte should be copied from source")
        assertEquals(0x0301, testEmulator.getReg16(REG_SI), "SI should advance by 1")
        assertEquals(0x0401, testEmulator.getReg16(REG_DI), "DI should advance by 1")
    }

    @Test
    fun testStosbStoresAlAndAdvancesDi() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_ES, 0x1000)
        testEmulator.setReg16(REG_DI, 0x0500)
        testEmulator.setReg8(REG_AL, 0x5A)
        testEmulator.setReg8(FLAG_DF, 0)
        val dstAddr = 0x1000 * 16 + 0x0500
        // STOSB (AA)
        testEmulator.writeInstruction(byteArrayOf(0xAA.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "STOSB should execute")
        assertEquals(0x5A, testEmulator.getMem(dstAddr), "AL should be stored at ES:DI")
        assertEquals(0x0501, testEmulator.getReg16(REG_DI), "DI should advance by 1")
    }

    @Test
    fun testLodsbLoadsAlAndAdvancesSi() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        testEmulator.setReg16(REG_SI, 0x0600)
        testEmulator.setReg8(FLAG_DF, 0)
        val srcAddr = 0x1000 * 16 + 0x0600
        testEmulator.setMem(srcAddr, 0x7C)
        // LODSB (AC)
        testEmulator.writeInstruction(byteArrayOf(0xAC.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "LODSB should execute")
        assertEquals(0x7C, testEmulator.getReg8(REG_AL), "AL should be loaded from DS:SI")
        assertEquals(0x0601, testEmulator.getReg16(REG_SI), "SI should advance by 1")
    }

    @Test
    fun testRepStosbFillsMultipleBytesAndClearsCx() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_ES, 0x1000)
        testEmulator.setReg16(REG_DI, 0x0700)
        testEmulator.setReg8(REG_AL, 0x11)
        testEmulator.setReg16(REG_CX, 5)
        testEmulator.setReg8(FLAG_DF, 0)
        val baseAddr = 0x1000 * 16 + 0x0700
        // REP STOSB (F3 AA) - the prefix and the string op are separate fetch/decode
        // steps in the real CPU loop, so exercise them as two instructions.
        testEmulator.writeInstruction(byteArrayOf(0xF3.toByte(), 0xAA.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "REP prefix should execute")
        assertTrue(testEmulator.executeSingleInstruction(), "STOSB should execute (repeated)")
        for (i in 0 until 5) {
            assertEquals(0x11, testEmulator.getMem(baseAddr + i), "Byte $i should be filled")
        }
        assertEquals(0x0705, testEmulator.getReg16(REG_DI), "DI should advance by 5")
        assertEquals(0, testEmulator.getReg16(REG_CX), "CX should be 0 after REP completes")
    }

    @Test
    fun testCmpsbSetsZeroFlagWithoutModifyingMemory() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        testEmulator.setReg16(REG_ES, 0x1000)
        testEmulator.setReg16(REG_SI, 0x0300)
        testEmulator.setReg16(REG_DI, 0x0400)
        testEmulator.setReg8(FLAG_DF, 0)
        val srcAddr = 0x1000 * 16 + 0x0300
        val dstAddr = 0x1000 * 16 + 0x0400
        testEmulator.setMem(srcAddr, 0x55)
        testEmulator.setMem(dstAddr, 0x55)
        // CMPSB (A6)
        testEmulator.writeInstruction(byteArrayOf(0xA6.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CMPSB should execute")
        assertEquals(1, testEmulator.getFlag(FLAG_ZF), "ZF should be set: bytes are equal")
        assertEquals(0x55, testEmulator.getMem(srcAddr), "CMPSB must not modify the source byte")
        assertEquals(0x55, testEmulator.getMem(dstAddr), "CMPSB must not modify the destination byte")
        assertEquals(0x0301, testEmulator.getReg16(REG_SI), "SI should advance by 1")
        assertEquals(0x0401, testEmulator.getReg16(REG_DI), "DI should advance by 1")
    }

    @Test
    fun testScasbComparesAlAgainstEsDiByte() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_ES, 0x1000)
        testEmulator.setReg16(REG_DI, 0x0500)
        testEmulator.setReg8(REG_AL, 0x42)
        testEmulator.setReg8(FLAG_DF, 0)
        val dstAddr = 0x1000 * 16 + 0x0500
        testEmulator.setMem(dstAddr, 0x42)
        // SCASB (AE)
        testEmulator.writeInstruction(byteArrayOf(0xAE.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "SCASB should execute")
        assertEquals(1, testEmulator.getFlag(FLAG_ZF), "ZF should be set: AL matches ES:DI")
        assertEquals(0x0501, testEmulator.getReg16(REG_DI), "DI should advance by 1")
    }

    // --- LOOP/JCXZ (xlat 13).

    @Test
    fun testLoopJumpsAndDecrementsCxWhileNonzero() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_CX, 2)
        // LOOP +0x0A (E2 0A)
        testEmulator.writeInstruction(byteArrayOf(0xE2.toByte(), 0x0A.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "LOOP should execute")
        assertEquals(1, testEmulator.getReg16(REG_CX), "CX should decrement to 1")
        assertEquals(0x200 + 2 + 0x0A, testEmulator.getIp(), "Should jump: CX was nonzero after decrementing")
    }

    @Test
    fun testLoopDoesNotJumpWhenCxReachesZero() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_CX, 1)
        // LOOP +0x0A (E2 0A)
        testEmulator.writeInstruction(byteArrayOf(0xE2.toByte(), 0x0A.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "LOOP should execute")
        assertEquals(0, testEmulator.getReg16(REG_CX), "CX should decrement to 0")
        assertEquals(0x202, testEmulator.getIp(), "Should NOT jump: CX reached 0 after decrementing")
    }

    @Test
    fun testJcxzJumpsWhenCxIsZero() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_CX, 0)
        // JCXZ +0x0A (E3 0A)
        testEmulator.writeInstruction(byteArrayOf(0xE3.toByte(), 0x0A.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "JCXZ should execute")
        assertEquals(0x200 + 2 + 0x0A, testEmulator.getIp(), "Should jump: CX is 0")
        assertEquals(0, testEmulator.getReg16(REG_CX), "CX should be unchanged (JCXZ doesn't decrement)")
    }

    // --- CALL/RET/IRET (xlat 5, 14, 19, 32).

    @Test
    fun testCallNearPushesReturnAddressAndJumps() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        // CALL near +0x0010 (E8 10 00)
        testEmulator.writeInstruction(byteArrayOf(0xE8.toByte(), 0x10.toByte(), 0x00.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CALL should execute")
        assertEquals(0x00FE, testEmulator.getReg16(REG_SP), "SP should decrement by 2")
        val stackAddr = testEmulator.segregPublic(REG_SS, REG_ZERO, testEmulator.getReg16(REG_SP))
        val returnAddr = ByteBuffer.wrap(testEmulator.getMemArray(), stackAddr, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(0x0203, returnAddr, "Pushed return address should be just after the 3-byte CALL")
        assertEquals(0x0213, testEmulator.getIp(), "IP should jump to 0x0203 + 0x0010")
    }

    @Test
    fun testRetPopsReturnAddressAndJumps() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x300)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x00FE)
        val stackAddr = testEmulator.segregPublic(REG_SS, REG_ZERO, 0x00FE)
        val bytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x0250.toShort()).array()
        testEmulator.setMem(stackAddr, bytes[0].toUByte().toInt())
        testEmulator.setMem(stackAddr + 1, bytes[1].toUByte().toInt())
        // RET (C3)
        testEmulator.writeInstruction(byteArrayOf(0xC3.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "RET should execute")
        assertEquals(0x0250, testEmulator.getIp(), "IP should be popped from the stack")
        assertEquals(0x0100, testEmulator.getReg16(REG_SP), "SP should increment by 2")
    }

    @Test
    fun testCallFarAndRetfRoundTrip() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        // CALL FAR 0x9000:0x0050 (9A 50 00 00 90)
        testEmulator.writeInstruction(byteArrayOf(0x9A.toByte(), 0x50.toByte(), 0x00.toByte(), 0x00.toByte(), 0x90.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CALL FAR should execute")
        assertEquals(0x0050, testEmulator.getIp(), "IP should jump to the far target offset")
        assertEquals(0x9000, testEmulator.getReg16(REG_CS), "CS should be loaded with the far target segment")
        assertEquals(0x00FC, testEmulator.getReg16(REG_SP), "SP should decrement by 4 (CS and return IP)")

        // RETF (CB)
        testEmulator.writeInstruction(byteArrayOf(0xCB.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "RETF should execute")
        assertEquals(0x0205, testEmulator.getIp(), "IP should be restored to the return address")
        assertEquals(0x1000, testEmulator.getReg16(REG_CS), "CS should be restored to the original segment")
    }

    @Test
    fun testIretRestoresCsIpAndFlagsAfterInterrupt() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x0200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg8(FLAG_CF, 1) // sentinel that must survive the round trip

        // Interrupt vector 3 -> CS:IP = 0x9000:0x0050
        testEmulator.setMem(3 * 4, 0x50)
        testEmulator.setMem(3 * 4 + 1, 0x00)
        testEmulator.setMem(3 * 4 + 2, 0x00)
        testEmulator.setMem(3 * 4 + 3, 0x90)

        // INT3 (CC) at 0x1000:0x0200
        testEmulator.writeInstruction(byteArrayOf(0xCC.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "INT3 should execute")
        assertEquals(0x0050, testEmulator.getIp())
        assertEquals(0x9000, testEmulator.getReg16(REG_CS))

        // IRET (CF) at 0x9000:0x0050
        testEmulator.writeInstruction(byteArrayOf(0xCF.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "IRET should execute")
        assertEquals(0x0201, testEmulator.getIp(), "IP should be restored to just after INT3")
        assertEquals(0x1000, testEmulator.getReg16(REG_CS), "CS should be restored")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be restored from the pushed flags")
    }

    // --- INT imm8 / INTO (xlat 39/40); INT3 (xlat 38) already covered in Phase 1.

    @Test
    fun testIntImm8JumpsToVector() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        // Interrupt vector 0x21 -> CS:IP = 0x9000:0x0070
        testEmulator.setMem(0x21 * 4, 0x70)
        testEmulator.setMem(0x21 * 4 + 1, 0x00)
        testEmulator.setMem(0x21 * 4 + 2, 0x00)
        testEmulator.setMem(0x21 * 4 + 3, 0x90)
        // INT 0x21 (CD 21)
        testEmulator.writeInstruction(byteArrayOf(0xCD.toByte(), 0x21.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "INT should execute")
        assertEquals(0x0070, testEmulator.getIp())
        assertEquals(0x9000, testEmulator.getReg16(REG_CS))
    }

    @Test
    fun testIntoFiresWhenOverflowFlagSet() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg8(FLAG_OF, 1)
        // Interrupt vector 4 -> CS:IP = 0x9000:0x0080
        testEmulator.setMem(4 * 4, 0x80)
        testEmulator.setMem(4 * 4 + 1, 0x00)
        testEmulator.setMem(4 * 4 + 2, 0x00)
        testEmulator.setMem(4 * 4 + 3, 0x90)
        // INTO (CE)
        testEmulator.writeInstruction(byteArrayOf(0xCE.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "INTO should execute")
        assertEquals(0x0080, testEmulator.getIp(), "Should jump to the overflow vector: OF is set")
    }

    @Test
    fun testIntoDoesNotFireWhenOverflowFlagClear() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_OF, 0)
        // INTO (CE)
        testEmulator.writeInstruction(byteArrayOf(0xCE.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "INTO should execute (as a no-op interrupt)")
        assertEquals(0x201, testEmulator.getIp(), "Should just advance past INTO: OF is clear")
    }

    @Test
    fun testNmiFiresEvenWhenInterruptsDisabled() {
        val emu = Emulator8086()
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.setReg16(REG_SS, 0x2000)
        emu.setReg16(REG_SP, 0x0100)
        emu.setReg8(FLAG_IF, 0) // CLI
        // INT 2 vector → 9000:0080
        emu.setMem(2 * 4, 0x80)
        emu.setMem(2 * 4 + 1, 0x00)
        emu.setMem(2 * 4 + 2, 0x00)
        emu.setMem(2 * 4 + 3, 0x90)
        emu.requestNmi()
        assertTrue(emu.isNmiPending())
        assertTrue(emu.serviceNmiIfPending())
        assertFalse(emu.isNmiPending())
        assertEquals(0x9000, emu.getReg16(REG_CS))
        assertEquals(0x0080, emu.getIp())
        assertEquals(0, emu.getFlag(FLAG_IF))
    }

    @Test
    fun testLastInstructionCyclesUsesOpcodeTable() {
        val emu = Emulator8086()
        emu.setIp(0x200)
        emu.setReg16(REG_CS, 0x1000)
        emu.writeInstruction(byteArrayOf(0x90.toByte())) // NOP
        assertTrue(emu.executeSingleInstruction())
        assertEquals(CycleTables.BASE[0x90], emu.lastInstructionCycles)
    }

    // --- LES/LDS (xlat 37).

    @Test
    fun testLesLoadsRegisterAndEs() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        val ptrAddr = 0x1000 * 16 + 0x0500
        testEmulator.setMem(ptrAddr, 0x34)
        testEmulator.setMem(ptrAddr + 1, 0x12)
        testEmulator.setMem(ptrAddr + 2, 0x78)
        testEmulator.setMem(ptrAddr + 3, 0x56)
        // LES BX, [0x0500] (C4 1E 00 05)
        testEmulator.writeInstruction(byteArrayOf(0xC4.toByte(), 0x1E.toByte(), 0x00.toByte(), 0x05.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "LES should execute")
        assertEquals(0x1234, testEmulator.getReg16(REG_BX), "BX should hold the far pointer's offset")
        assertEquals(0x5678, testEmulator.getReg16(REG_ES), "ES should hold the far pointer's segment")
    }

    @Test
    fun testLdsLoadsRegisterAndDs() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        val ptrAddr = 0x1000 * 16 + 0x0500
        testEmulator.setMem(ptrAddr, 0xAA)
        testEmulator.setMem(ptrAddr + 1, 0x00)
        testEmulator.setMem(ptrAddr + 2, 0x11)
        testEmulator.setMem(ptrAddr + 3, 0x22)
        // LDS BX, [0x0500] (C5 1E 00 05)
        testEmulator.writeInstruction(byteArrayOf(0xC5.toByte(), 0x1E.toByte(), 0x00.toByte(), 0x05.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "LDS should execute")
        assertEquals(0x00AA, testEmulator.getReg16(REG_BX), "BX should hold the far pointer's offset")
        assertEquals(0x2211, testEmulator.getReg16(REG_DS), "DS should hold the far pointer's segment")
    }

    // --- AAA/AAS/AAM/AAD (xlat 29, 41, 42); DAA/DAS already covered in Phase 1.

    @Test
    fun testAaaAdjustsAlAndAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x000A)
        // AAA (37)
        testEmulator.writeInstruction(byteArrayOf(0x37.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "AAA should execute")
        assertEquals(0x00, testEmulator.getReg8(REG_AL), "AL should be adjusted to 0x00")
        assertEquals(0x01, testEmulator.getReg8(REG_AH), "AH should be incremented to 0x01")
        assertEquals(1, testEmulator.getFlag(FLAG_AF), "AF should be set")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be set")
    }

    @Test
    fun testAaaAlOverflowDoesNotDoubleIncrementAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0xA3FF)
        testEmulator.writeInstruction(byteArrayOf(0x37.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0xA405, testEmulator.getReg16(REG_AX))
    }

    @Test
    fun testAasAdjustsAlAndAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x000A)
        // AAS (3F)
        testEmulator.writeInstruction(byteArrayOf(0x3F.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "AAS should execute")
        assertEquals(0x04, testEmulator.getReg8(REG_AL), "AL should be adjusted to 0x04")
        assertEquals(0xFF, testEmulator.getReg8(REG_AH), "AH should be decremented to 0xFF")
    }

    @Test
    fun testAasAlUnderflowDoesNotDoubleDecrementAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x9803)
        testEmulator.setReg8(FLAG_AF, 1)
        testEmulator.writeInstruction(byteArrayOf(0x3F.toByte()))

        assertTrue(testEmulator.executeSingleInstruction())
        assertEquals(0x970D, testEmulator.getReg16(REG_AX))
    }

    @Test
    fun testAamConvertsAlToUnpackedBcd() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 31)
        // AAM (D4 0A)
        testEmulator.writeInstruction(byteArrayOf(0xD4.toByte(), 0x0A.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "AAM should execute")
        assertEquals(3, testEmulator.getReg8(REG_AH), "AH should be 31/10=3")
        assertEquals(1, testEmulator.getReg8(REG_AL), "AL should be 31%10=1")
    }

    @Test
    fun testAadConvertsUnpackedBcdToAl() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AH, 3)
        testEmulator.setReg8(REG_AL, 1)
        // AAD (D5 0A)
        testEmulator.writeInstruction(byteArrayOf(0xD5.toByte(), 0x0A.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "AAD should execute")
        assertEquals(31, testEmulator.getReg8(REG_AL), "AL should be 1 + 10*3 = 31")
        assertEquals(0, testEmulator.getReg8(REG_AH), "AH should be cleared")
    }

    // --- PUSHF/POPF/SAHF/LAHF (xlat 33-36).

    @Test
    fun testPushfPopfRoundTripsFlags() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_SS, 0x2000)
        testEmulator.setReg16(REG_SP, 0x0100)
        testEmulator.setReg8(FLAG_CF, 1)
        testEmulator.setReg8(FLAG_ZF, 1)
        // PUSHF (9C)
        testEmulator.writeInstruction(byteArrayOf(0x9C.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "PUSHF should execute")

        testEmulator.setReg8(FLAG_CF, 0)
        testEmulator.setReg8(FLAG_ZF, 0)
        // POPF (9D)
        testEmulator.writeInstruction(byteArrayOf(0x9D.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "POPF should execute")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be restored by POPF")
        assertEquals(1, testEmulator.getFlag(FLAG_ZF), "ZF should be restored by POPF")
    }

    @Test
    fun testSahfLoadsCfAndZfFromAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AH, 0x41) // bit0=CF=1, bit6=ZF=1
        // SAHF (9E)
        testEmulator.writeInstruction(byteArrayOf(0x9E.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "SAHF should execute")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be loaded from AH bit 0")
        assertEquals(1, testEmulator.getFlag(FLAG_ZF), "ZF should be loaded from AH bit 6")
    }

    @Test
    fun testLahfStoresFlagsIntoAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_CF, 1)
        testEmulator.setReg8(FLAG_ZF, 1)
        // LAHF (9F)
        testEmulator.writeInstruction(byteArrayOf(0x9F.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "LAHF should execute")
        val ah = testEmulator.getReg8(REG_AH)
        assertEquals(1, (ah shr 0) and 1, "AH bit 0 should reflect CF")
        assertEquals(1, (ah shr 6) and 1, "AH bit 6 should reflect ZF")
    }

    // --- IN/OUT (xlat 21/22).

    @Test
    fun testOutWritesByteToIoPort() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x99)
        // OUT 0x50, AL (E6 50)
        testEmulator.writeInstruction(byteArrayOf(0xE6.toByte(), 0x50.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "OUT should execute")
        assertEquals(0x99, testEmulator.getIoPort(0x50), "Port 0x50 should receive AL's value")
    }

    @Test
    fun testInReadsByteFromIoPort() {
        val testEmulator = Emulator8086()
        // Route port 0x51 to a device that drives 0x77 on the data bus.
        val bus = IoBus()
        bus.map(object : IoDevice {
            override fun ioReadByte(port: Int): Int = 0x77
            override fun ioWriteByte(port: Int, value: Int) {}
        }, 0x51)
        testEmulator.attachIoBus(bus)
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        // IN AL, 0x51 (E4 51)
        testEmulator.writeInstruction(byteArrayOf(0xE4.toByte(), 0x51.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "IN should execute")
        assertEquals(0x77, testEmulator.getReg8(REG_AL), "AL should receive port 0x51's value")
    }

    // --- CBW/CWD/SALC/XLAT/CMC (xlat 30/31/43/44/45).

    @Test
    fun testCbwSignExtendsNegativeAlIntoAh() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x80)
        // CBW (98)
        testEmulator.writeInstruction(byteArrayOf(0x98.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CBW should execute")
        assertEquals(0xFF, testEmulator.getReg8(REG_AH), "AH should be sign-extended to 0xFF")
    }

    @Test
    fun testCbwDoesNotExtendPositiveAl() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(REG_AL, 0x7F)
        // CBW (98)
        testEmulator.writeInstruction(byteArrayOf(0x98.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CBW should execute")
        assertEquals(0x00, testEmulator.getReg8(REG_AH), "AH should be 0x00 for a positive AL")
    }

    @Test
    fun testCwdSignExtendsAxIntoDx() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x8000)
        // CWD (99)
        testEmulator.writeInstruction(byteArrayOf(0x99.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CWD should execute")
        assertEquals(0xFFFF, testEmulator.getReg16(REG_DX), "DX should be sign-extended to 0xFFFF")
    }

    @Test
    fun testSalcSetsAlFromCarryFlag() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_CF, 1)
        // SALC (D6)
        testEmulator.writeInstruction(byteArrayOf(0xD6.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "SALC should execute")
        assertEquals(0xFF, testEmulator.getReg8(REG_AL), "AL should be 0xFF when CF=1")
    }

    @Test
    fun testXlatLoadsAlFromTable() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        testEmulator.setReg16(REG_BX, 0x0500)
        testEmulator.setReg8(REG_AL, 0x03)
        val addr = 0x1000 * 16 + 0x0500 + 3
        testEmulator.setMem(addr, 0x9A)
        // XLAT (D7)
        testEmulator.writeInstruction(byteArrayOf(0xD7.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "XLAT should execute")
        assertEquals(0x9A, testEmulator.getReg8(REG_AL), "AL should be loaded from DS:BX+AL")
    }

    @Test
    fun testCmcTogglesCarryFlag() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg8(FLAG_CF, 0)
        // CMC (F5)
        testEmulator.writeInstruction(byteArrayOf(0xF5.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "CMC should execute")
        assertEquals(1, testEmulator.getFlag(FLAG_CF), "CF should be toggled to 1")
    }

    // --- Segment override prefix (xlat 27).

    @Test
    fun testSegmentOverridePrefixRedirectsMemoryAccess() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_DS, 0x1000)
        testEmulator.setReg16(REG_ES, 0x2000)
        testEmulator.setReg16(REG_BX, 0x0050)
        val dsAddr = 0x1000 * 16 + 0x0050
        val esAddr = 0x2000 * 16 + 0x0050
        testEmulator.setMem(dsAddr, 0x11) // decoy: must NOT be read due to the override
        testEmulator.setMem(esAddr, 0x22) // must be read because of the ES: override
        // ES: MOV AL, [BX] (26 8A 07)
        testEmulator.writeInstruction(byteArrayOf(0x26.toByte(), 0x8A.toByte(), 0x07.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "Segment-override prefix should execute")
        assertTrue(testEmulator.executeSingleInstruction(), "MOV should execute using the ES override")
        assertEquals(0x22, testEmulator.getReg8(REG_AL), "Should read from ES:BX, not DS:BX")
    }

    // --- XCHG reg, r/m (xlat 24 via a direct 0x86/0x87 encoding, distinct from the
    // XCHG AX,reg16 form already covered in Phase 1).

    @Test
    fun testXchgRegRm() {
        val testEmulator = Emulator8086()
        testEmulator.setIp(0x200)
        testEmulator.setReg16(REG_CS, 0x1000)
        testEmulator.setReg16(REG_AX, 0x1234)
        testEmulator.setReg16(REG_BX, 0x5678)
        // XCHG AX, BX (87 C3): mod=11, reg=000(AX), rm=011(BX)
        testEmulator.writeInstruction(byteArrayOf(0x87.toByte(), 0xC3.toByte()))
        assertTrue(testEmulator.executeSingleInstruction(), "XCHG should execute")
        assertEquals(0x5678, testEmulator.getReg16(REG_AX), "AX should hold BX's old value")
        assertEquals(0x1234, testEmulator.getReg16(REG_BX), "BX should hold AX's old value")
    }

    // --- Multi-disk boot device selection (setupBootDisks(), extracted from run()
    // so it's testable without invoking the blocking main loop).

    @Test
    fun testSetupBootDisksOpensFloppyAndBootsFromIt() {
        val testEmulator = Emulator8086()

        val floppyFile = File.createTempFile("k8086-floppy", ".img")
        floppyFile.deleteOnExit()
        RandomAccessFile(floppyFile, "rw").use {
            it.setLength(512)
            it.write(byteArrayOf(0x42.toByte()))
        }

        testEmulator.setupBootDisks(floppyImage = floppyFile.absolutePath, hardDiskImage = null)
        assertEquals(0, testEmulator.getReg8(REG_DL), "DL should be 0 (floppy boot) when no hard disk is specified")
        assertTrue(testEmulator.isDiskOpen(0), "Floppy image should be opened as disk[0]")
    }

    @Test
    fun testSetupBootDisksBootsFromHardDiskWhenAtPrefixed() {
        val testEmulator = Emulator8086()

        val hdFile = File.createTempFile("k8086-harddisk", ".img")
        hdFile.deleteOnExit()
        RandomAccessFile(hdFile, "rw").use { it.setLength(4096) } // 8 sectors

        testEmulator.setupBootDisks(hardDiskImage = "@" + hdFile.absolutePath)
        assertEquals(0x80, testEmulator.getReg8(REG_DL), "DL should be 0x80: hard disk boot requested via @ prefix")
        assertEquals(8, testEmulator.getReg16(REG_AX), "AX should hold the hard disk size in sectors (4096/512)")
    }

    @Test
    fun testSetupBootDisksWithoutAtPrefixDoesNotBootFromHardDisk() {
        val testEmulator = Emulator8086()

        val hdFile = File.createTempFile("k8086-harddisk", ".img")
        hdFile.deleteOnExit()
        RandomAccessFile(hdFile, "rw").use { it.setLength(4096) }

        testEmulator.setupBootDisks(hardDiskImage = hdFile.absolutePath) // no '@' prefix
        assertEquals(0, testEmulator.getReg8(REG_DL), "DL should remain 0: hard disk boot was not requested")
    }

    @Test
    fun testSetupBootDisksProvisionsBlank10MbHardDiskWhenMissing() {
        val testEmulator = Emulator8086()

        val dir = File.createTempFile("k8086-hd-new", "").apply { delete() }
        val hdFile = File(dir.parentFile, dir.name + ".img")
        hdFile.deleteOnExit()
        assertFalse(hdFile.exists(), "precondition: image must not exist yet")

        testEmulator.setupBootDisks(hardDiskImage = "@" + hdFile.absolutePath)

        assertEquals(XT_HARD_DISK_BYTES, hdFile.length(), "missing image is provisioned to 10 MB")
        // 306 cyl × 4 heads × 17 spt = 20808 sectors → CX:AX = 0x0000:0x5148.
        val sectors = XT_HARD_DISK_BYTES / 512
        val reported = (testEmulator.getReg16(REG_CX).toLong() shl 16) or testEmulator.getReg16(REG_AX).toLong()
        assertEquals(sectors, reported, "sector count reported in CX:AX")

        val hd = HdInt13(testEmulator)
        val g = hd.geometryFor(testEmulator.diskImage(0x80)!!)
        assertEquals(306, g.cylinders)
        assertEquals(4, g.heads)
        assertEquals(17, g.sectorsPerTrack)

        testEmulator.closeDisks()
    }

    @Test
    fun testSetupBootDisksProvisionsEmptyStubHardDisk() {
        val testEmulator = Emulator8086()

        val hdFile = File.createTempFile("k8086-hd-stub", ".img")
        hdFile.deleteOnExit()
        assertEquals(0L, hdFile.length(), "precondition: zero-length stub")

        testEmulator.setupBootDisks(hardDiskImage = hdFile.absolutePath)
        assertEquals(XT_HARD_DISK_BYTES, hdFile.length(), "empty stub is grown to 10 MB")

        testEmulator.closeDisks()
    }

    // --- CGA adapter mode control (production display path is Cga.kt, not the
    // removed Hercules/0x3B8 updateGraphicsDisplay path).

    @Test
    fun testCgaTextModeClearsGraphicsBit() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x29) // 80-col text, video enable
        assertEquals(0, cga.modeControlValue() and 0x02, "Text mode must clear graphics bit")
    }

    @Test
    fun testCgaGraphicsModeSetsGraphicsBit() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        cga.ioWriteByte(0x3D8, 0x2A) // graphics + video enable
        assertEquals(0x02, cga.modeControlValue() and 0x02, "Graphics mode must set bit 1")
        cga.ioWriteByte(0x3D8, 0x29)
        assertEquals(0, cga.modeControlValue() and 0x02, "Returning to text clears graphics bit")
    }
}
