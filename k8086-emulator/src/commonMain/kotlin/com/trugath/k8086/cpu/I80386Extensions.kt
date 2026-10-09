package com.trugath.k8086.cpu

/**
 * Real-mode 80386 0x0F escape: 286 SMSW/LMSW/CLTS plus MOV CRn, SETcc,
 * Jcc rel16/rel32 (66h), MOVZX/MOVSX, bit ops, double shifts, LSS/LFS/LGS, IMUL r,r/m.
 *
 * Owned by [Emulator80386]; created once at construction — never allocated
 * on the instruction hot path. [baseInstSize] for 0x0F is 0 so this owns IP.
 */
internal class I80386Extensions(private val cpu: Emulator80386) {
    var cr0: Int = Emulator80386.CR0_RESET
    var cr2: Int = 0
    var cr3: Int = 0

    fun resetControlRegisters() {
        cr0 = Emulator80386.CR0_RESET
        cr2 = 0
        cr3 = 0
    }

    /**
     * LOCK + 0F is legal only for memory-modifying BTS/BTR/BTC (not plain BT).
     * Peek second opcode + ModR/M without advancing IP.
     */
    fun lock0FAllowed(): Boolean {
        val ip = cpu.getIp()
        val op2 = cpu.instructionRead8(ip + 1)
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        if (mod == 3) return false
        return when (op2) {
            0xAB, 0xB3, 0xBB -> true // BTS/BTR/BTC r/m, r
            0xBA -> {
                val reg = (modrm shr 3) and 7
                reg in 5..7 // /5../7 only — /4 BT is read-only → #UD with LOCK
            }
            else -> false
        }
    }

    fun execute0FEscape() {
        val ip = cpu.getIp()
        val op2 = cpu.instructionRead8(ip + 1)
        when (op2) {
            0x06 -> { // CLTS
                cr0 = cr0 and Emulator80386.CR0_TS.inv()
                cpu.regIp = (ip + 2) and 0xFFFF
            }
            0x01 -> execute0F01(ip)
            0x20 -> executeMovFromCr(ip) // MOV r32, CRn
            0x22 -> executeMovToCr(ip) // MOV CRn, r32
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F,
            -> executeJccNear(ip, op2)
            0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
            0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F,
            -> executeSetcc(ip, op2)
            0xA0 -> { // PUSH FS
                cpu.iW = true
                cpu.push(cpu.getReg16(REG_FS))
                cpu.regIp = (ip + 2) and 0xFFFF
            }
            0xA1 -> { // POP FS
                cpu.iW = true
                val v = cpu.popSelector()
                if (cpu.pendingException < 0) cpu.setReg16(REG_FS, v)
                cpu.regIp = (ip + 2) and 0xFFFF
            }
            0xA8 -> { // PUSH GS
                cpu.iW = true
                cpu.push(cpu.getReg16(REG_GS))
                cpu.regIp = (ip + 2) and 0xFFFF
            }
            0xA9 -> { // POP GS
                cpu.iW = true
                val v = cpu.popSelector()
                if (cpu.pendingException < 0) cpu.setReg16(REG_GS, v)
                cpu.regIp = (ip + 2) and 0xFFFF
            }
            0xA3, 0xAB, 0xB3, 0xBB -> executeBitTest(ip, op2) // BT/BTS/BTR/BTC r/m, r
            0xBA -> executeBitTestImm(ip) // BT/BTS/BTR/BTC r/m, imm8
            0xBC -> executeBitScan(ip, forward = true) // BSF
            0xBD -> executeBitScan(ip, forward = false) // BSR
            0xA4, 0xA5 -> executeShld(ip, op2) // SHLD
            0xAC, 0xAD -> executeShrd(ip, op2) // SHRD
            0xAF -> executeImulRm(ip) // IMUL r, r/m
            0xB2, 0xB4, 0xB5 -> executeLoadFar(ip, op2) // LSS/LFS/LGS
            0xB6, 0xB7 -> executeMovzx(ip, op2)
            0xBE, 0xBF -> executeMovsx(ip, op2)
            else -> cpu.regIp = (ip + 2) and 0xFFFF
        }
    }

    private fun execute0F01(ip: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val reg = (modrm shr 3) and 7
        val mod = modrm shr 6
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        when (reg) {
            4 -> { // SMSW r/m16
                val value = cr0 and 0xFFFF
                writeRm16(mod, rm, disp, value)
            }
            6 -> { // LMSW r/m16 — low 4 bits only; PE may set but EA stays real
                val value = readRm16(mod, rm, disp)
                cr0 = (cr0 and Emulator80386.CR0_PE.inv() and
                    Emulator80386.CR0_MP.inv() and Emulator80386.CR0_EM.inv() and
                    Emulator80386.CR0_TS.inv()) or (value and 0x000F)
            }
            else -> { /* descriptor-table ops deferred */ }
        }
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeMovFromCr(ip: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val crn = (modrm shr 3) and 7
        val rm = modrm and 7
        if ((modrm shr 6) != 3) {
            cpu.raiseException(6)
            cpu.regIp = (ip + 3) and 0xFFFF
            return
        }
        val value = when (crn) {
            0 -> cr0
            2 -> cr2
            3 -> cr3
            else -> 0
        }
        cpu.setReg32(rm, value)
        cpu.regIp = (ip + 3) and 0xFFFF
    }

    private fun executeMovToCr(ip: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val crn = (modrm shr 3) and 7
        val rm = modrm and 7
        if ((modrm shr 6) != 3) {
            cpu.raiseException(6)
            cpu.regIp = (ip + 3) and 0xFFFF
            return
        }
        val value = cpu.reg32u(rm)
        when (crn) {
            0 -> {
                // Real-mode pass: accept PE/MP/EM/TS/ET/PG bits but keep real EA.
                cr0 = value
            }
            2 -> cr2 = value
            3 -> cr3 = value
        }
        cpu.regIp = (ip + 3) and 0xFFFF
    }

    private fun executeJccNear(ip: Int, op2: Int) {
        // 66h → rel32 (Intel SDM Jcc); real-mode EIP still wraps at 16 bits.
        val dispBytes = if (cpu.operandSizeOverrideEn != 0) 4 else 2
        var rel = 0
        for (i in 0 until dispBytes) {
            rel = rel or (cpu.instructionRead8(ip + 2 + i) shl (8 * i))
        }
        val signed = if (dispBytes == 4) rel else rel.toShort().toInt()
        val cond = (op2 shr 1) and 7
        val invert = (op2 and 1) != 0
        val taken = invert xor cpu.jccTaken(cond)
        val next = ip + 2 + dispBytes
        if (taken) cpu.flushInstructionPrefetch()
        cpu.regIp = (next + if (taken) signed else 0) and 0xFFFF
    }

    private fun executeSetcc(ip: Int, op2: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val cond = (op2 shr 1) and 7
        val invert = (op2 and 1) != 0
        val taken = invert xor cpu.jccTaken(cond)
        val value = if (taken) 1 else 0
        if (mod == 3) {
            val addr = REGS_BASE + ((2 * rm + rm / 4) and 7)
            cpu.writePhysByte(addr, value)
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            val addr = cpu.segreg(ea and 0xFF, REG_ZERO, (ea ushr 8) and 0xFFFF)
            cpu.writePhysByte(addr, value)
        }
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeBitTest(ip: Int, op2: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3, deferLimit = true)
        if (cpu.pendingException >= 0) return
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val bitIndex = if (width == 32) cpu.reg32u(reg) else cpu.getReg16(reg)
        if (mod == 3) {
            val bit = bitIndex and (width - 1)
            val base = if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
            val mask = 1 shl bit
            cpu.setCF((base and mask) != 0)
            if (op2 != 0xA3) {
                val result = when (op2) {
                    0xAB -> base or mask
                    0xB3 -> base and mask.inv()
                    0xBB -> base xor mask
                    else -> base
                }
                if (width == 32) cpu.setReg32(rm, result) else cpu.setReg16(rm, result)
            }
        } else {
            // Memory: signed bit index → byte displacement from EA, bit = index & 7.
            val signedIndex = if (width == 32) bitIndex else bitIndex.toShort().toInt()
            val byteOff = signedIndex shr 3
            val bit = signedIndex and 7
            val addr = bitMemoryAddress(mod, rm, disp, byteOff)
            if (cpu.pendingException >= 0) return
            val old = cpu.readPhysByte(addr)
            val mask = 1 shl bit
            cpu.setCF((old and mask) != 0)
            if (op2 != 0xA3) {
                val result = when (op2) {
                    0xAB -> old or mask
                    0xB3 -> old and mask.inv()
                    0xBB -> old xor mask
                    else -> old
                }
                cpu.writePhysByte(addr, result)
            }
        }
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    /**
     * Linear address of the byte containing a bit at [byteOff] from the ModR/M EA.
     * Offset arithmetic wraps at 16 bits inside the selected segment.
     */
    private fun bitMemoryAddress(mod: Int, rm: Int, disp: Int, byteOff: Int): Int {
        if (cpu.addressSizeOverrideEn != 0) {
            val width = if (cpu.operandSizeOverrideEn != 0) 4 else 2
            val operandOffset = cpu.rmOffset + (byteOff and -width)
            val offset = cpu.rmOffset + byteOff
            if ((operandOffset ushr 16) != 0 || operandOffset > 0x10000 - width) {
                cpu.raiseException(cpu.exceptionForWordAccess(cpu.rmSegment))
                return 0
            }
            return cpu.segreg(cpu.rmSegment, REG_ZERO, offset)
        }
        val packed = cpu.modRmComponentsPacked(rm, mod)
        val base = (packed and 0xFF).toInt()
        val index = ((packed shr 8) and 0xFF).toInt()
        val defaultSeg = ((packed shr 16) and 0xFF).toInt()
        val segReg = if (cpu.segOverrideEn != 0) cpu.segOverride else defaultSeg
        var offset = (if (base == REG_ZERO) 0 else cpu.getReg16(base)) +
            (if (index == REG_ZERO) 0 else cpu.getReg16(index))
        if (mod != 0 || rm == 6) offset += disp
        offset = (offset + byteOff) and 0xFFFF
        return cpu.segreg(segReg, REG_ZERO, offset)
    }

    private fun executeBitTestImm(ip: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, body) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val imm = cpu.instructionRead8(ip + body)
        val consumed = body + 1
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val bit = imm and (width - 1)
        var memSeg = -1
        var memOff = 0
        val base = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            memSeg = ea and 0xFF
            memOff = (ea shr 8) and 0xFFFF
            if (width == 32) {
                if (cpu.dwordOffsetFaults(memOff)) {
                    cpu.raiseException(cpu.exceptionForWordAccess(memSeg))
                    cpu.regIp = (ip + consumed) and 0xFFFF
                    return
                }
                cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, memOff)) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 1) and 0xFFFF)) shl 8) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 2) and 0xFFFF)) shl 16) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 3) and 0xFFFF)) shl 24)
            } else {
                cpu.readSegmentWord(memSeg, memOff)
            }
        }
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        val mask = 1 shl bit
        cpu.setCF((base and mask) != 0)
        val result = when (reg) {
            5 -> base or mask // BTS
            6 -> base and mask.inv() // BTR
            7 -> base xor mask // BTC
            else -> base // BT (/4)
        }
        if (reg in 5..7) {
            if (mod == 3) {
                if (width == 32) cpu.setReg32(rm, result) else cpu.setReg16(rm, result)
            } else if (width == 32) {
                cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, memOff), result)
                cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 1) and 0xFFFF), result shr 8)
                cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 2) and 0xFFFF), result shr 16)
                cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 3) and 0xFFFF), result shr 24)
            } else {
                cpu.writeSegmentWord(memSeg, memOff, result)
            }
        }
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeBitScan(ip: Int, forward: Boolean) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val src = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            val memSeg = ea and 0xFF
            val memOff = (ea shr 8) and 0xFFFF
            if (width == 32) {
                if (cpu.dwordOffsetFaults(memOff)) {
                    cpu.raiseException(cpu.exceptionForWordAccess(memSeg))
                    cpu.regIp = (ip + consumed) and 0xFFFF
                    return
                }
                cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, memOff)) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 1) and 0xFFFF)) shl 8) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 2) and 0xFFFF)) shl 16) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 3) and 0xFFFF)) shl 24)
            } else {
                cpu.readSegmentWord(memSeg, memOff)
            }
        }
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        if (src == 0) {
            // Clear ZF only — keep EFLAGS[31:16] (0xFFBF as a 16-bit mask zeroes them).
            cpu.setFlagsValue((cpu.getFlags() and 0x40.inv()) or 0x40)
        } else {
            var bit = if (forward) 0 else width - 1
            val step = if (forward) 1 else -1
            while ((src and (1 shl bit)) == 0) bit += step
            if (width == 32) cpu.setReg32(reg, bit) else cpu.setReg16(reg, bit)
            cpu.setFlagsValue(cpu.getFlags() and 0x40.inv())
        }
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeShld(ip: Int, op2: Int) {
        executeDoubleShift(ip, op2, left = true)
    }

    private fun executeShrd(ip: Int, op2: Int) {
        executeDoubleShift(ip, op2, left = false)
    }

    private fun executeDoubleShift(ip: Int, op2: Int, left: Boolean) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, body) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val immForm = op2 == 0xA4 || op2 == 0xAC
        val count = if (immForm) {
            cpu.instructionRead8(ip + body) and 0x1F
        } else {
            cpu.getReg8(REG_CL) and 0x1F
        }
        val consumed = body + if (immForm) 1 else 0
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val mask = if (width == 32) -1 else 0xFFFF
        val maskL = if (width == 32) 0xFFFFFFFFL else 0xFFFFL
        var memSeg = -1
        var memOff = 0
        // Probe the memory operand even when count==0 — 386EX still #GP/#SS on
        // wrap (offset FFFFh for 16-bit / FFFDh+ for 32-bit) before the no-op.
        val dest = (if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            memSeg = ea and 0xFF
            memOff = (ea shr 8) and 0xFFFF
            if (width == 32) {
                if (cpu.dwordOffsetFaults(memOff)) {
                    cpu.raiseException(cpu.exceptionForWordAccess(memSeg))
                    cpu.regIp = (ip + consumed) and 0xFFFF
                    return
                }
                cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, memOff)) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 1) and 0xFFFF)) shl 8) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 2) and 0xFFFF)) shl 16) or
                    (cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 3) and 0xFFFF)) shl 24)
            } else {
                cpu.readSegmentWord(memSeg, memOff)
            }
        }).toLong() and maskL
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        if (count == 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        val src = (if (width == 32) cpu.reg32u(reg) else cpu.getReg16(reg)).toLong() and maskL
        // 16-bit SHLD/SHRD with count 16..31: 386EX rotates the source by
        // (count - 16) into the destination (SingleStepTests/80386). A plain
        // double-width shift zeros those results. 32-bit opsize never hits
        // count >= width (count is masked to 5 bits).
        val result: Int
        val cf: Boolean
        if (left) {
            if (width == 16 && count >= 16) {
                val k = count - 16
                val s = src.toInt() and 0xFFFF
                result = if (k == 0) s else ((s shl k) or (s ushr (16 - k))) and 0xFFFF
                val pair = (dest shl 16) or src
                cf = ((pair ushr (32 - count)) and 1L) != 0L
            } else {
                val pair = (dest shl width) or src
                result = ((pair shl count) ushr width).toInt() and mask
                cf = ((pair ushr (2 * width - count)) and 1L) != 0L
            }
        } else {
            if (width == 16 && count >= 16) {
                val k = count - 16
                val s = src.toInt() and 0xFFFF
                result = if (k == 0) s else ((s ushr k) or (s shl (16 - k))) and 0xFFFF
                val pair = (src shl 16) or dest
                cf = ((pair ushr (count - 1)) and 1L) != 0L
            } else {
                val pair = (src shl width) or dest
                result = ((pair ushr count) and maskL).toInt()
                cf = ((pair ushr (count - 1)) and 1L) != 0L
            }
        }
        if (mod == 3) {
            if (width == 32) cpu.setReg32(rm, result) else cpu.setReg16(rm, result)
        } else if (width == 32) {
            if (cpu.dwordOffsetFaults(memOff)) {
                cpu.raiseException(cpu.exceptionForWordAccess(memSeg))
                cpu.regIp = (ip + consumed) and 0xFFFF
                return
            }
            cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, memOff), result)
            cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 1) and 0xFFFF), result shr 8)
            cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 2) and 0xFFFF), result shr 16)
            cpu.writePhysByte(cpu.segreg(memSeg, REG_ZERO, (memOff + 3) and 0xFFFF), result shr 24)
        } else {
            cpu.writeSegmentWord(memSeg, memOff, result)
            if (cpu.pendingException >= 0) {
                cpu.regIp = (ip + consumed) and 0xFFFF
                return
            }
        }
        cpu.setCF(cf)
        if (count == 1) {
            val sign = 1 shl (width - 1)
            cpu.setOF(((dest.toInt() xor result) and sign) != 0)
        }
        cpu.opResult = result
        cpu.setFlagsType = FLAGS_UPDATE_SZP
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeImulRm(ip: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val a = if (width == 32) cpu.reg32u(reg) else cpu.getReg16(reg).toShort().toInt()
        val b = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm).toShort().toInt()
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            val seg = ea and 0xFF
            val off = (ea shr 8) and 0xFFFF
            if (width == 32) {
                if (cpu.dwordOffsetFaults(off)) {
                    cpu.raiseException(cpu.exceptionForWordAccess(seg))
                    cpu.regIp = (ip + consumed) and 0xFFFF
                    return
                }
                cpu.readPhysByte(cpu.segreg(seg, REG_ZERO, off)) or
                    (cpu.readPhysByte(cpu.segreg(seg, REG_ZERO, (off + 1) and 0xFFFF)) shl 8) or
                    (cpu.readPhysByte(cpu.segreg(seg, REG_ZERO, (off + 2) and 0xFFFF)) shl 16) or
                    (cpu.readPhysByte(cpu.segreg(seg, REG_ZERO, (off + 3) and 0xFFFF)) shl 24)
            } else {
                cpu.readSegmentWord(seg, off).toShort().toInt()
            }
        }
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        val product = a.toLong() * b.toLong()
        if (width == 32) {
            cpu.setReg32(reg, product.toInt())
            val truncated = product.toInt().toLong()
            val overflow = product != truncated
            cpu.setCF(overflow)
            cpu.setOF(overflow)
        } else {
            cpu.setReg16(reg, product.toInt() and 0xFFFF)
            val truncated = product.toShort().toLong()
            val overflow = product != truncated
            cpu.setCF(overflow)
            cpu.setOF(overflow)
        }
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeLoadFar(ip: Int, op2: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        if (mod == 3) {
            cpu.raiseException(6)
            cpu.regIp = (ip + 3) and 0xFFFF
            return
        }
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val ea = effectiveAddressSegOff(mod, rm, disp)
        val memSeg = ea and 0xFF
        val memOff = (ea shr 8) and 0xFFFF
        // 16-bit opsize → m16:16; 66h → m16:32 (offset dword, then segment word).
        val far32 = cpu.operandSizeOverrideEn != 0
        if (far32) {
            if (cpu.dwordOffsetFaults(memOff) || cpu.wordOffsetFaults((memOff + 4) and 0xFFFF) ||
                (cpu.addressSizeOverrideEn != 0 && memOff > 0xFFFA)
            ) {
                cpu.raiseException(cpu.exceptionForWordAccess(memSeg))
                cpu.regIp = (ip + consumed) and 0xFFFF
                return
            }
            val off = cpu.readSegmentDword(memSeg, memOff)
            val seg = cpu.readSegmentWord(memSeg, (memOff + 4) and 0xFFFF)
            if (cpu.pendingException >= 0) {
                cpu.regIp = (ip + consumed) and 0xFFFF
                return
            }
            cpu.setReg32(reg, off)
            val sreg = when (op2) {
                0xB2 -> REG_SS
                0xB4 -> REG_FS
                else -> REG_GS
            }
            cpu.setReg16(sreg, seg)
            if (sreg == REG_SS) cpu.armSsInterruptShadow()
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        // Match LES/LDS: each word of the far pointer is checked for FFFFh wrap.
        if (cpu.wordOffsetFaults(memOff) || cpu.wordOffsetFaults((memOff + 2) and 0xFFFF) ||
            (cpu.addressSizeOverrideEn != 0 && memOff > 0xFFFC)
        ) {
            cpu.raiseException(cpu.exceptionForWordAccess(memSeg))
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        val off = cpu.readSegmentWord(memSeg, memOff)
        val seg = cpu.readSegmentWord(memSeg, (memOff + 2) and 0xFFFF)
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        cpu.setReg16(reg, off)
        val sreg = when (op2) {
            0xB2 -> REG_SS
            0xB4 -> REG_FS
            else -> REG_GS
        }
        cpu.setReg16(sreg, seg)
        if (sreg == REG_SS) cpu.armSsInterruptShadow()
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeMovzx(ip: Int, op2: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val srcWidth = if (op2 == 0xB6) 1 else 2
        val src = if (mod == 3) {
            if (srcWidth == 1) {
                val addr = REGS_BASE + ((2 * rm + rm / 4) and 7)
                cpu.readPhysByte(addr)
            } else {
                cpu.getReg16(rm)
            }
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            val memSeg = ea and 0xFF
            val memOff = (ea shr 8) and 0xFFFF
            if (srcWidth == 1) {
                cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, memOff))
            } else {
                cpu.readSegmentWord(memSeg, memOff)
            }
        }
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        val zeroed = if (srcWidth == 1) src and 0xFF else src and 0xFFFF
        if (cpu.operandSizeOverrideEn != 0) cpu.setReg32(reg, zeroed)
        else cpu.setReg16(reg, zeroed)
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun executeMovsx(ip: Int, op2: Int) {
        val modrm = cpu.instructionRead8(ip + 2)
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        if (cpu.pendingException >= 0) return
        val srcWidth = if (op2 == 0xBE) 1 else 2
        val raw = if (mod == 3) {
            if (srcWidth == 1) {
                val addr = REGS_BASE + ((2 * rm + rm / 4) and 7)
                cpu.readPhysByte(addr)
            } else {
                cpu.getReg16(rm)
            }
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            val memSeg = ea and 0xFF
            val memOff = (ea shr 8) and 0xFFFF
            if (srcWidth == 1) {
                cpu.readPhysByte(cpu.segreg(memSeg, REG_ZERO, memOff))
            } else {
                cpu.readSegmentWord(memSeg, memOff)
            }
        }
        if (cpu.pendingException >= 0) {
            cpu.regIp = (ip + consumed) and 0xFFFF
            return
        }
        val signed = if (srcWidth == 1) raw.toByte().toInt() else raw.toShort().toInt()
        if (cpu.operandSizeOverrideEn != 0) cpu.setReg32(reg, signed)
        else cpu.setReg16(reg, signed and 0xFFFF)
        cpu.regIp = (ip + consumed) and 0xFFFF
    }

    private fun decodeModrmDisp(ip: Int, mod: Int, rm: Int, opcodeBytes: Int, deferLimit: Boolean = false): Pair<Int, Int> {
        if (cpu.addressSizeOverrideEn != 0 && mod != 3) {
            // Reuse the primary decoder's SIB, displacement and segment-limit
            // handling, with IP pointing at the second opcode byte.
            cpu.iMod = mod
            cpu.iRm = rm
            val savedIp = cpu.regIp
            val savedException = cpu.pendingException
            cpu.regIp = ip + opcodeBytes - 2
            cpu.decodeRmReg()
            // BT/BTS/BTR/BTC add the signed bit-string displacement before
            // checking the segment limit; the unadjusted EA may be outside it.
            if (deferLimit) cpu.pendingException = savedException
            val extraBytes = cpu.modRm32ExtraBytes()
            cpu.regIp = savedIp
            return cpu.iData1 to (opcodeBytes + extraBytes)
        }
        var consumed = opcodeBytes
        var disp = 0
        if (mod != 3) {
            when {
                mod == 0 && rm == 6 -> {
                    disp = cpu.instructionRead8(ip + opcodeBytes) or
                        (cpu.instructionRead8(ip + opcodeBytes + 1) shl 8)
                    consumed += 2
                }
                mod == 1 -> {
                    disp = cpu.instructionRead8(ip + opcodeBytes).toByte().toInt()
                    consumed += 1
                }
                mod == 2 -> {
                    disp = cpu.instructionRead8(ip + opcodeBytes) or
                        (cpu.instructionRead8(ip + opcodeBytes + 1) shl 8)
                    consumed += 2
                }
            }
        }
        return disp to consumed
    }

    private fun effectiveAddressSegOff(mod: Int, rm: Int, disp: Int): Int =
        if (cpu.addressSizeOverrideEn != 0) {
            (cpu.rmSegment and 0xFF) or ((cpu.rmOffset and 0xFFFF) shl 8)
        } else {
            cpu.effectiveAddressSegOff(mod, rm, disp)
        }

    private fun readRm16(mod: Int, rm: Int, disp: Int): Int =
        if (mod == 3) {
            cpu.getReg16(rm)
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            cpu.readSegmentWord(ea and 0xFF, (ea shr 8) and 0xFFFF)
        }

    private fun writeRm16(mod: Int, rm: Int, disp: Int, value: Int) {
        if (mod == 3) {
            cpu.setReg16(rm, value)
        } else {
            val ea = effectiveAddressSegOff(mod, rm, disp)
            cpu.writeSegmentWord(ea and 0xFF, (ea shr 8) and 0xFFFF, value)
        }
    }

}
