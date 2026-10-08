package com.trugath.k8086.cpu

/**
 * Real-mode 80386 0x0F escape: 286 SMSW/LMSW/CLTS plus MOV CRn, SETcc,
 * Jcc rel16, MOVZX/MOVSX, bit ops, double shifts, LSS/LFS/LGS, IMUL r,r/m.
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

    fun execute0FEscape() {
        val ip = cpu.getIp()
        val op2 = cpu.readPhysByte(csIp(ip + 1))
        when (op2) {
            0x06 -> { // CLTS
                cr0 = cr0 and Emulator80386.CR0_TS.inv()
                cpu.setIp((ip + 2) and 0xFFFF)
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
                cpu.setIp((ip + 2) and 0xFFFF)
            }
            0xA1 -> { // POP FS
                cpu.iW = true
                val v = cpu.pop()
                if (cpu.pendingException < 0) cpu.setReg16(REG_FS, v)
                cpu.setIp((ip + 2) and 0xFFFF)
            }
            0xA8 -> { // PUSH GS
                cpu.iW = true
                cpu.push(cpu.getReg16(REG_GS))
                cpu.setIp((ip + 2) and 0xFFFF)
            }
            0xA9 -> { // POP GS
                cpu.iW = true
                val v = cpu.pop()
                if (cpu.pendingException < 0) cpu.setReg16(REG_GS, v)
                cpu.setIp((ip + 2) and 0xFFFF)
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
            else -> cpu.setIp((ip + 2) and 0xFFFF)
        }
    }

    private fun execute0F01(ip: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val reg = (modrm shr 3) and 7
        val mod = modrm shr 6
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
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
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeMovFromCr(ip: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val crn = (modrm shr 3) and 7
        val rm = modrm and 7
        if ((modrm shr 6) != 3) {
            cpu.raiseException(6)
            cpu.setIp((ip + 3) and 0xFFFF)
            return
        }
        val value = when (crn) {
            0 -> cr0
            2 -> cr2
            3 -> cr3
            else -> 0
        }
        cpu.setReg32(rm, value)
        cpu.setIp((ip + 3) and 0xFFFF)
    }

    private fun executeMovToCr(ip: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val crn = (modrm shr 3) and 7
        val rm = modrm and 7
        if ((modrm shr 6) != 3) {
            cpu.raiseException(6)
            cpu.setIp((ip + 3) and 0xFFFF)
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
        cpu.setIp((ip + 3) and 0xFFFF)
    }

    private fun executeJccNear(ip: Int, op2: Int) {
        val rel = cpu.readPhysByte(csIp(ip + 2)) or
            (cpu.readPhysByte(csIp(ip + 3)) shl 8)
        val signed = rel.toShort().toInt()
        val cond = (op2 shr 1) and 7
        val invert = (op2 and 1) != 0
        val taken = invert xor cpu.jccTaken(cond)
        cpu.setIp(((ip + 4) + if (taken) signed else 0) and 0xFFFF)
    }

    private fun executeSetcc(ip: Int, op2: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val cond = (op2 shr 1) and 7
        val invert = (op2 and 1) != 0
        val taken = invert xor cpu.jccTaken(cond)
        val value = if (taken) 1 else 0
        if (mod == 3) {
            val addr = REGS_BASE + ((2 * rm + rm / 4) and 7)
            cpu.writePhysByte(addr, value)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            cpu.writePhysByte(addr, value)
        }
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeBitTest(ip: Int, op2: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val bitIndex = if (cpu.operandSizeOverrideEn != 0) {
            cpu.reg32u(reg)
        } else {
            cpu.getReg16(reg)
        }
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val bit = bitIndex and (width - 1)
        val base = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            // Bit offset may span words; keep the simple in-operand form.
            if (width == 32) {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8) or
                    (cpu.readPhysByte(addr + 2) shl 16) or (cpu.readPhysByte(addr + 3) shl 24)
            } else {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
            }
        }
        val mask = 1 shl bit
        cpu.setCF((base and mask) != 0)
        val result = when (op2) {
            0xAB -> base or mask // BTS
            0xB3 -> base and mask.inv() // BTR
            0xBB -> base xor mask // BTC
            else -> base // BT
        }
        if (op2 != 0xA3) {
            if (mod == 3) {
                if (width == 32) cpu.setReg32(rm, result) else cpu.setReg16(rm, result)
            } else {
                val addr = cpu.effectiveAddressLinear(mod, rm, disp)
                cpu.writePhysByte(addr, result)
                cpu.writePhysByte(addr + 1, result shr 8)
                if (width == 32) {
                    cpu.writePhysByte(addr + 2, result shr 16)
                    cpu.writePhysByte(addr + 3, result shr 24)
                }
            }
        }
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeBitTestImm(ip: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, body) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val imm = cpu.readPhysByte(csIp(ip + body))
        val consumed = body + 1
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val bit = imm and (width - 1)
        val base = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            if (width == 32) {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8) or
                    (cpu.readPhysByte(addr + 2) shl 16) or (cpu.readPhysByte(addr + 3) shl 24)
            } else {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
            }
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
            } else {
                val addr = cpu.effectiveAddressLinear(mod, rm, disp)
                cpu.writePhysByte(addr, result)
                cpu.writePhysByte(addr + 1, result shr 8)
                if (width == 32) {
                    cpu.writePhysByte(addr + 2, result shr 16)
                    cpu.writePhysByte(addr + 3, result shr 24)
                }
            }
        }
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeBitScan(ip: Int, forward: Boolean) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val src = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            if (width == 32) {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8) or
                    (cpu.readPhysByte(addr + 2) shl 16) or (cpu.readPhysByte(addr + 3) shl 24)
            } else {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
            }
        }
        if (src == 0) {
            cpu.setFlagsValue((cpu.getFlags() and 0xFFBF) or 0x40) // ZF=1
        } else {
            var bit = if (forward) 0 else width - 1
            val step = if (forward) 1 else -1
            while ((src and (1 shl bit)) == 0) bit += step
            if (width == 32) cpu.setReg32(reg, bit) else cpu.setReg16(reg, bit)
            cpu.setFlagsValue(cpu.getFlags() and 0xFFBF) // ZF=0
        }
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeShld(ip: Int, op2: Int) {
        executeDoubleShift(ip, op2, left = true)
    }

    private fun executeShrd(ip: Int, op2: Int) {
        executeDoubleShift(ip, op2, left = false)
    }

    private fun executeDoubleShift(ip: Int, op2: Int, left: Boolean) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, body) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val immForm = op2 == 0xA4 || op2 == 0xAC
        val count = if (immForm) {
            cpu.readPhysByte(csIp(ip + body)) and 0x1F
        } else {
            cpu.getReg8(REG_CL) and 0x1F
        }
        val consumed = body + if (immForm) 1 else 0
        if (count == 0) {
            cpu.setIp((ip + consumed) and 0xFFFF)
            return
        }
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val mask = if (width == 32) -1 else 0xFFFF
        val dest = (if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            if (width == 32) {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8) or
                    (cpu.readPhysByte(addr + 2) shl 16) or (cpu.readPhysByte(addr + 3) shl 24)
            } else {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
            }
        }) and mask
        val src = (if (width == 32) cpu.reg32u(reg) else cpu.getReg16(reg)) and mask
        val result: Int
        val cf: Boolean
        if (left) {
            result = ((dest shl count) or (src ushr (width - count))) and mask
            cf = ((dest ushr (width - count)) and 1) != 0
        } else {
            result = ((dest ushr count) or (src shl (width - count))) and mask
            cf = ((dest ushr (count - 1)) and 1) != 0
        }
        if (mod == 3) {
            if (width == 32) cpu.setReg32(rm, result) else cpu.setReg16(rm, result)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            cpu.writePhysByte(addr, result)
            cpu.writePhysByte(addr + 1, result shr 8)
            if (width == 32) {
                cpu.writePhysByte(addr + 2, result shr 16)
                cpu.writePhysByte(addr + 3, result shr 24)
            }
        }
        cpu.setCF(cf)
        cpu.opResult = result
        cpu.setFlagsType = FLAGS_UPDATE_SZP
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeImulRm(ip: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val width = if (cpu.operandSizeOverrideEn != 0) 32 else 16
        val a = if (width == 32) cpu.reg32u(reg) else cpu.getReg16(reg).toShort().toInt()
        val b = if (mod == 3) {
            if (width == 32) cpu.reg32u(rm) else cpu.getReg16(rm).toShort().toInt()
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            if (width == 32) {
                cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8) or
                    (cpu.readPhysByte(addr + 2) shl 16) or (cpu.readPhysByte(addr + 3) shl 24)
            } else {
                (cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)).toShort().toInt()
            }
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
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeLoadFar(ip: Int, op2: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        if (mod == 3) {
            cpu.raiseException(6)
            cpu.setIp((ip + 3) and 0xFFFF)
            return
        }
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val addr = cpu.effectiveAddressLinear(mod, rm, disp)
        val off = cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
        val seg = cpu.readPhysByte(addr + 2) or (cpu.readPhysByte(addr + 3) shl 8)
        cpu.setReg16(reg, off)
        val sreg = when (op2) {
            0xB2 -> REG_SS
            0xB4 -> REG_FS
            else -> REG_GS
        }
        cpu.setReg16(sreg, seg)
        if (sreg == REG_SS) cpu.armSsInterruptShadow()
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeMovzx(ip: Int, op2: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val srcWidth = if (op2 == 0xB6) 1 else 2
        val src = if (mod == 3) {
            if (srcWidth == 1) {
                val addr = REGS_BASE + ((2 * rm + rm / 4) and 7)
                cpu.readPhysByte(addr)
            } else {
                cpu.getReg16(rm)
            }
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            if (srcWidth == 1) cpu.readPhysByte(addr)
            else cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
        }
        val zeroed = if (srcWidth == 1) src and 0xFF else src and 0xFFFF
        if (cpu.operandSizeOverrideEn != 0) cpu.setReg32(reg, zeroed)
        else cpu.setReg16(reg, zeroed)
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun executeMovsx(ip: Int, op2: Int) {
        val modrm = cpu.readPhysByte(csIp(ip + 2))
        val mod = modrm shr 6
        val reg = (modrm shr 3) and 7
        val rm = modrm and 7
        val (disp, consumed) = decodeModrmDisp(ip, mod, rm, opcodeBytes = 3)
        val srcWidth = if (op2 == 0xBE) 1 else 2
        val raw = if (mod == 3) {
            if (srcWidth == 1) {
                val addr = REGS_BASE + ((2 * rm + rm / 4) and 7)
                cpu.readPhysByte(addr)
            } else {
                cpu.getReg16(rm)
            }
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            if (srcWidth == 1) cpu.readPhysByte(addr)
            else cpu.readPhysByte(addr) or (cpu.readPhysByte(addr + 1) shl 8)
        }
        val signed = if (srcWidth == 1) raw.toByte().toInt() else raw.toShort().toInt()
        if (cpu.operandSizeOverrideEn != 0) cpu.setReg32(reg, signed)
        else cpu.setReg16(reg, signed and 0xFFFF)
        cpu.setIp((ip + consumed) and 0xFFFF)
    }

    private fun decodeModrmDisp(ip: Int, mod: Int, rm: Int, opcodeBytes: Int): Pair<Int, Int> {
        var consumed = opcodeBytes
        var disp = 0
        if (mod != 3) {
            when {
                mod == 0 && rm == 6 -> {
                    disp = cpu.readPhysByte(csIp(ip + opcodeBytes)) or
                        (cpu.readPhysByte(csIp(ip + opcodeBytes + 1)) shl 8)
                    consumed += 2
                }
                mod == 1 -> {
                    disp = cpu.readPhysByte(csIp(ip + opcodeBytes)).toByte().toInt()
                    consumed += 1
                }
                mod == 2 -> {
                    disp = cpu.readPhysByte(csIp(ip + opcodeBytes)) or
                        (cpu.readPhysByte(csIp(ip + opcodeBytes + 1)) shl 8)
                    consumed += 2
                }
            }
        }
        return disp to consumed
    }

    private fun readRm16(mod: Int, rm: Int, disp: Int): Int =
        if (mod == 3) {
            cpu.getReg16(rm)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            cpu.readPhysByte(addr) or (cpu.readPhysByte(
                (addr and 0xF0000) or (((addr and 0xFFFF) + 1) and 0xFFFF),
            ) shl 8)
        }

    private fun writeRm16(mod: Int, rm: Int, disp: Int, value: Int) {
        if (mod == 3) {
            cpu.setReg16(rm, value)
        } else {
            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
            cpu.writePhysByte(addr, value and 0xFF)
            cpu.writePhysByte(
                (addr and 0xF0000) or (((addr and 0xFFFF) + 1) and 0xFFFF),
                (value shr 8) and 0xFF,
            )
        }
    }

    private fun csIp(ip: Int): Int =
        cpu.physicalAddress((cpu.getReg16(REG_CS) shl 4) + (ip and 0xFFFF))
}
