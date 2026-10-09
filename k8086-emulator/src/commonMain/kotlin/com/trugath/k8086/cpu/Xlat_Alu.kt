package com.trugath.k8086.cpu

/**
 * ALU / arithmetic / shift / TEST xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

internal fun Emulator8086.executeOpcode8Body() {
    opToAddr = rmAddr
    iD = iD or (!iW)
    // Imm8 (sign-extended) vs imm16/imm32. With 66h, opcode 81 takes a 32-bit immediate;
    // IP must skip all four bytes here because setOpcode() remaps to a non-imm form whose
    // post-exec length no longer includes the immediate (under-skip → FE2 MCGA desync).
    val immBytes = when {
        iD -> 1
        operandSize32() -> 4
        else -> 2
    }
    val dispBytes = if (iModSize > 0 && iMod < 3) {
        if (addressSizeOverrideEn != 0) {
            modRm32ExtraBytes()
        } else {
            (iMod * (if (iMod != 3) 1 else 0) +
                2 * (if (iMod == 0 && iRm == 6) 1 else 0)) * iModSize
        }
    } else {
        0
    }
    val scratchValue = if (iD) {
        instructionRead8((regIp + 2 + dispBytes) and 0xFFFF).toByte().toInt()
    } else if (immBytes == 4) {
        // Imm32 starts after opcode+modrm+disp; iData2 alone is only correct when
        // dispBytes==0 (reg form / no disp). With [disp16] it holds the low imm
        // word only if decoder rebound it — always re-read from CS:IP.
        val immOff = 2 + dispBytes
        val b0 = instructionRead8((regIp + immOff) and 0xFFFF)
        val b1 = instructionRead8((regIp + immOff + 1) and 0xFFFF)
        val b2 = instructionRead8((regIp + immOff + 2) and 0xFFFF)
        val b3 = instructionRead8((regIp + immOff + 3) and 0xFFFF)
        b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    } else if (addressSizeOverrideEn != 0) {
        val immOff = 2 + dispBytes
        instructionRead8((regIp + immOff) and 0xFFFF) or
            (instructionRead8((regIp + immOff + 1) and 0xFFFF) shl 8)
    } else {
        iData2
    }
    // SCRATCH is outside EAX..EDI. For imm32, keep the high half in imm32Scratch
    // — REG_FS is the next 16-bit slot after REG_SCRATCH, so a linear dword write
    // would clobber FS (SingleStep 66 05 / 81 vectors).
    val scratchAddr = REGS_BASE + 2 * REG_SCRATCH
    guestWrite16(scratchAddr, scratchValue)
    // 66h: dword ops must see the full 32-bit immediate (imm8 sign-extended
    // or imm32). Without imm32Scratch, readOperand only gets 16 bits (6683.MOO).
    if (operandSize32()) {
        imm32Scratch = scratchValue
    }
    opFromAddr = scratchAddr
    regIp += immBytes
    extra = iReg
    setOpcode(0x08 * extra) // Decode like the reg,r/m variant of the same operation
}

// Shared body for OPCODE 9 (ADD|OR|ADC|SBB|AND|SUB|XOR|CMP|MOV reg, r/m).
internal fun Emulator8086.executeOpcode9Body() {
    when (extra) {
        0 -> { // ADD
            memOp(opToAddr, ALU_ADD, opFromAddr)
            // Carry mirrors the C reference's unsigned `op_result < op_dest`, where
            // op_result is truncated to the operand width. Kotlin Int `<` is signed,
            // so compare via Long for 32-bit opsize.
            setCF(unsignedLess(opResult and cfWidthMask(), opDest and cfWidthMask()))
        }
        1 -> { // OR
            memOp(opToAddr, ALU_OR, opFromAddr)
        }
        2 -> { // ADC
            val cf = regs8[FLAG_CF].toInt()
            memOp(opToAddr, ALU_ADD, opFromAddr)
            opResult += cf
            writeOperand(opToAddr, opResult)
            // Carry out on unsigned overflow. Truncating opResult to the operand width,
            // the sum carries when it wraps below the destination; the carry-in edge
            // (dest + 0xFFFF.. + 1 wraps back to dest) is caught by the equality term.
            val mask = cfWidthMask()
            val masked = opResult and mask
            val dest = opDest and mask
            setCF(unsignedLess(masked, dest) || (cf != 0 && masked == dest))
            setAFOfArith()
        }
        3 -> { // SBB
            val cf = regs8[FLAG_CF].toInt()
            memOp(opToAddr, ALU_SUB, opFromAddr)
            opResult -= cf
            writeOperand(opToAddr, opResult)
            // Borrow (unsigned) when the truncated difference exceeds the minuend; the
            // carry-in edge (subtrahend + borrow wraps to 0) is caught by the equality.
            val mask = cfWidthMask()
            val masked = opResult and mask
            val dest = opDest and mask
            setCF(unsignedGreater(masked, dest) || (cf != 0 && masked == dest))
            setAFOfArith()
        }
        4 -> { // AND
            memOp(opToAddr, ALU_AND, opFromAddr)
        }
        5 -> { // SUB
            memOp(opToAddr, ALU_SUB, opFromAddr)
            setCF(unsignedGreater(opResult and cfWidthMask(), opDest and cfWidthMask()))
        }
        6 -> { // XOR
            memOp(opToAddr, ALU_XOR, opFromAddr)
        }
        7 -> { // CMP
            memOp(opToAddr, ALU_SUB, opFromAddr, writeBack = false)
            // Borrow (unsigned): the subtrahend exceeds the minuend. Masking the raw
            // difference to the operand width makes 0 - 1 read back as 0xFF/0xFFFF,
            // matching the reference's unsigned comparison against op_dest.
            setCF(unsignedGreater(opResult and cfWidthMask(), opDest and cfWidthMask()))
        }
        8 -> { // MOV
            memOp(opToAddr, ALU_MOV, opFromAddr)
        }
    }
}

// Shared body for OPCODE 24 (NOP|XCHG reg, r/m). C code falls through from
// OPCODE 16 (XCHG AX, regs16) into this after setting up opToAddr/opFromAddr.
// --- xlat opcode group: alu ---

internal fun Emulator8086.executeXlat2(iReg4bit: Int) {
    // INC|DEC regs16 - OPCODE 2
    // Note: no setOpcode(0x10) re-decode here, unlike case 5's identical
    // INC|DEC branch - C's OPCODE_CHAIN falls from this case into case 5,
    // where `xlat_opcode_id == 5` gates that re-decode; since xlatOpcodeId
    // is definitionally 2 here (this is case 2, not a fallthrough), that
    // check could never fire, so it's omitted rather than dead code.
    iW = true
    iD = false
    iReg = iReg4bit
    decodeRmReg()
    iReg = extra
    val increment = 1 - 2 * iReg
    applyIncDec(opFromAddr, increment)
    setAFOfArith()
    setOF((opDest + 1 - iReg) == (1 shl (topBit() - 1)))
}

internal fun Emulator8086.executeXlat5() {
    // INC|DEC|JMP|CALL|PUSH - OPCODE 5
    if (iReg < 2) { // INC|DEC
        val increment = 1 - 2 * iReg
        applyIncDec(opFromAddr, increment)
        setAFOfArith()
        setOF((opDest + 1 - iReg) == (1 shl (topBit() - 1)))
        if (xlatOpcodeId == 5) {
            setOpcode(0x10) // Decode like ADC
        }
    } else if (iReg == 6 || iReg == 7) { // PUSH r/m (reg=7 is an 8086 alias of /6)
        val value = readOperand(rmAddr)
        if (pendingException >= 0) return
        // FF /6 PUSH SP: 8086 stores post-decrement SP; 286 stores pre-decrement.
        val pushed = if (!rmIsMemory && iRm == REG_SP) {
            valuePushedForSp(value)
        } else {
            value
        }
        push(pushed)
    } else { // JMP|CALL
        // 286: far CALL/JMP with a register operand is #UD.
        if ((iReg and 1) != 0 && iMod == 3 && farIndirectRegisterFormRaisesUd()) {
            raiseException(6)
            return
        }
        // Sample the branch target before any stack write. CALL SP must
        // jump to the pre-decrement SP; reading after push would use SP-2.
        // Memory targets also need 16-bit offset wrap (e.g. word at FFFFh).
        //
        // Real/VM86 far pointer layout depends on operand size:
        //   16-bit opsize → 16:16 at [mem+0]/[mem+2]
        //   32-bit opsize → 16:32 at [mem+0]/[mem+4] (Intel SDM CALL)
        val far32 = (iReg and 1) != 0 && operandSizeOverrideEn != 0
        val newIp: Int
        val newCs: Int?
        if ((iReg and 1) != 0) {
            if (far32) {
                if (rmIsMemory && dwordOffsetFaults(rmOffset and 0xFFFF)) {
                    raiseException(exceptionForWordAccess(rmSegment))
                    return
                }
                if (rmIsMemory && wordOffsetFaults((rmOffset + 4) and 0xFFFF)) {
                    raiseException(exceptionForWordAccess(rmSegment))
                    return
                }
                newIp = guestRead8(rmAddress(0)) or (guestRead8(rmAddress(1)) shl 8) or
                    (guestRead8(rmAddress(2)) shl 16) or (guestRead8(rmAddress(3)) shl 24)
                newCs = guestRead8(rmAddress(4)) or (guestRead8(rmAddress(5)) shl 8)
            } else {
                newIp = readOperand(opFromAddr)
                if (pendingException >= 0) return
                if (rmIsMemory && wordOffsetFaults((rmOffset + 2) and 0xFFFF)) {
                    raiseException(exceptionForWordAccess(rmSegment))
                    return
                }
                newCs = guestRead8(rmAddress(2)) or (guestRead8(rmAddress(3)) shl 8)
            }
        } else {
            newIp = readOperand(opFromAddr)
            if (pendingException >= 0) return
            newCs = null
        }
        if (iReg == 3) { // CALL (far)
            push(regs16[REG_CS].toInt() and 0xFFFF)
            if (pendingException >= 0) return
        }
        if ((iReg and 2) != 0) { // CALL (near or far)
            // Instruction length from FF opcode (66h prefix already consumed).
            val callOffset = 2 + iMod * (if (iMod != 3) 1 else 0) + 2 * (if (iMod == 0 && iRm == 6) 1 else 0)
            push(regIp + callOffset)
            if (pendingException >= 0) return
        }
        if (newCs != null) {
            regs16[REG_CS] = newCs.toShort()
        }
        // Real-mode fetch uses the low 16 bits of EIP.
        regIp = newIp and 0xFFFF
        flushInstructionPrefetch()
        setOpcode(0x9A) // Decode like CALL
    }
}

internal fun Emulator8086.executeXlat6() {
    if (operandSize32()) {
        executeDwordGroup3()
        return
    }
    // TEST r/m,imm16 | NOT|NEG|MUL|IMUL|DIV|IDIV reg - OPCODE 6
    // Nested switch(i_reg) inside OPCODE 6 (ALU /m,imm forms).
    // opToAddr already equals opFromAddr (== rmAddr) here: the top-level
    // decodeRmReg() call swapped them because i_d is true for 0xF6/0xF7,
    // so GET_REG_ADDR(i_reg)'s "fake register" address ended up in
    // opToAddr, about to be discarded, while the real r/m operand address
    // landed in opFromAddr.
    opToAddr = opFromAddr
    when (iReg) {
        0, 1 -> { // TEST r/m, imm8/imm16 (reg=1 is an 8086 alias of reg=0)
            val immediate = if (addressSizeOverrideEn != 0) {
                val immOff = 2 + if (iMod < 3) modRm32ExtraBytes() else 0
                val lo = instructionRead8((regIp + immOff) and 0xFFFF)
                if (iW) lo or (instructionRead8((regIp + immOff + 1) and 0xFFFF) shl 8)
                else lo
            } else iData2
            setOpcode(0x20) // Decode like AND
            regIp += if (iW) 2 else 1
            regs16[REG_SCRATCH] = immediate.toShort()
            memOp(opToAddr, ALU_AND, REGS_BASE + 2 * REG_SCRATCH, writeBack = false)
        }
        2 -> { // NOT
            val value = readOperand(opToAddr)
            if (pendingException >= 0) return
            opDest = value
            opSource = value
            opResult = value.inv() and (if (iW) 0xFFFF else 0xFF)
            writeOperand(opToAddr, opResult)
        }
        3 -> { // NEG
            val value = readOperand(opToAddr)
            if (pendingException >= 0) return
            opSource = value
            opResult = (-value) and (if (iW) 0xFFFF else 0xFF)
            writeOperand(opToAddr, opResult)
            opDest = 0
            setOpcode(0x28) // Decode like SUB
            setCF(opResult > opDest)
        }
        4, 5 -> { // MUL (4) / IMUL (5)
            setOpcode(0x10) // Decode like ADC (SZP from opResult)
            val signed = iReg == 5
            val rmVal = readOperand(rmAddr)
            if (pendingException >= 0) return
            val accum = if (iW) regs16[REG_AX].toInt() and 0xFFFF else regs8[REG_AL].toUByte().toInt()
            var product: Long = if (signed) {
                val signedRmVal = if (iW) rmVal.toShort().toLong() else rmVal.toByte().toLong()
                val signedAccum = if (iW) accum.toShort().toLong() else accum.toByte().toLong()
                signedRmVal * signedAccum
            } else {
                rmVal.toLong() * accum.toLong()
            }
            // 8086: REP/REPNE leaves internal F1 set, which negates the product.
            if (repOverrideEn != 0) {
                product = -product
            }
            if (iW) {
                regs16[REG_DX] = ((product shr 16) and 0xFFFF).toShort()
            }
            regs16[REG_AX] = (product and 0xFFFF).toShort()
            // Overflow iff the product doesn't round-trip through the
            // accumulator's own width (8 or 16 bits) - i.e. AH/DX != 0
            // for unsigned, or != sign-extension of the low half for signed.
            val overflows = if (signed) {
                (if (iW) product.toShort().toLong() else product.toByte().toLong()) != product
            } else {
                (if (iW) (product and 0xFFFF) else (product and 0xFF)) != product
            }
            setCF(overflows)
            setOF(overflows)
            // 8088 silicon sets SF/ZF/PF from the HIGH half (AH/DX), not AL/AX.
            // Using the low half makes ZF=1 for 40h*40h=1000h (AL=0), so CheckIt's
            // `xor al,al / mov al,40h / mul al / je` mis-detects a V20.
            opResult = if (iW) {
                (product shr 16).toInt() and 0xFFFF
            } else {
                (product shr 8).toInt() and 0xFF
            }
        }
        6, 7 -> { // DIV (6) / IDIV (7)
            val signed = iReg == 7
            val divisorRaw = readOperand(rmAddr)
            if (pendingException >= 0) return
            val divisor = if (signed) {
                if (iW) divisorRaw.toShort().toInt() else divisorRaw.toByte().toInt()
            } else {
                divisorRaw
            }
            if (divisor == 0) {
                raiseDivideError()
            } else {
                val dividend: Long = if (iW) {
                    ((regs16[REG_DX].toInt() and 0xFFFF).toLong() shl 16) or
                        (regs16[REG_AX].toInt() and 0xFFFF).toLong()
                } else {
                    (regs16[REG_AX].toInt() and 0xFFFF).toLong()
                }
                val signedDividend = if (signed) {
                    if (iW) dividend.toInt().toLong() else dividend.toShort().toLong()
                } else {
                    dividend
                }
                var quotient = signedDividend / divisor
                val remainder = signedDividend % divisor
                // 8086: REP/REPNE negates the IDIV quotient (via F1), not DIV.
                if (signed && repOverrideEn != 0) {
                    quotient = -quotient
                }
                // 8086: quotient -128 / -32768 raises #DE. 286 allows those.
                val fits = if (signed) {
                    signedDivideQuotientFits(quotient, word = iW)
                } else {
                    if (iW) quotient in 0..0xFFFF else quotient in 0..0xFF
                }
                if (!fits) {
                    raiseDivideError()
                } else {
                    if (iW) {
                        regs16[REG_AX] = quotient.toShort()
                        regs16[REG_DX] = remainder.toShort()
                    } else {
                        regs8[REG_AL] = quotient.toByte()
                        regs8[REG_AH] = remainder.toByte()
                    }
                    opResult = quotient.toInt()
                }
            }
        }
    }
}

private fun Emulator8086.executeDwordGroup3() {
    val value = readOperand(rmAddr)
    if (pendingException >= 0) return
    when (iReg) {
        0, 1 -> {
            val disp = if (iMod == 3) 0 else if (addressSizeOverrideEn != 0) {
                modRm32ExtraBytes()
            } else {
                if (iMod == 0 && iRm == 6 || iMod == 2) 2 else if (iMod == 1) 1 else 0
            }
            val immOff = 2 + disp
            var immediate = 0
            repeat(4) { i ->
                immediate = immediate or (instructionRead8((regIp + immOff + i) and 0xFFFF) shl (8 * i))
            }
            opDest = value
            opSource = immediate
            opResult = value and immediate
            regIp += 4
            setOpcode(0x20)
        }
        2 -> {
            opResult = value.inv()
            writeOperand(rmAddr, opResult)
        }
        3 -> {
            opDest = 0
            opSource = value
            opResult = -value
            writeOperand(rmAddr, opResult)
            setOpcode(0x28)
            setCF(value != 0)
        }
        4, 5 -> {
            val signed = iReg == 5
            val ax = getReg32(REG_AX)
            val product = if (signed) ax.toLong() * value.toLong() else
                (ax.toLong() and 0xFFFFFFFFL) * (value.toLong() and 0xFFFFFFFFL)
            setReg32(REG_AX, product.toInt())
            setReg32(REG_DX, (product ushr 32).toInt())
            val overflow = if (signed) product != product.toInt().toLong() else (product ushr 32) != 0L
            setCF(overflow)
            setOF(overflow)
        }
        6, 7 -> {
            if (value == 0) {
                raiseDivideError()
                return
            }
            val dividend = (getReg32(REG_DX).toLong() shl 32) or (getReg32(REG_AX).toLong() and 0xFFFFFFFFL)
            if (iReg == 7) {
                // Long.MIN_VALUE / -1 overflows even the intermediate quotient.
                if (dividend == Long.MIN_VALUE && value == -1) {
                    raiseDivideError()
                    return
                }
                val quotient = dividend / value.toLong()
                if (quotient !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    raiseDivideError()
                    return
                }
                setReg32(REG_AX, quotient.toInt())
                setReg32(REG_DX, (dividend % value.toLong()).toInt())
            } else {
                val divisor = value.toUInt().toULong()
                val quotient = dividend.toULong() / divisor
                if (quotient > UInt.MAX_VALUE.toULong()) {
                    raiseDivideError()
                    return
                }
                setReg32(REG_AX, quotient.toInt())
                setReg32(REG_DX, (dividend.toULong() % divisor).toInt())
            }
        }
    }
}

internal fun Emulator8086.executeXlat7() {
    // ADD|OR|ADC|SBB|AND|SUB|XOR|CMP AL/AX, immed - OPCODE 7 (falls through to 8, 9)
    rmAddr = REGS_BASE
    iData2 = iData0
    iMod = 3
    iReg = extra
    regIp--
    executeOpcode8Body()
    executeOpcode9Body()
}

internal fun Emulator8086.executeXlat8() {
    // ADD|OR|ADC|SBB|AND|SUB|XOR|CMP reg, immed - OPCODE 8 (falls through to 9)
    executeOpcode8Body()
    executeOpcode9Body()
}

internal fun Emulator8086.executeXlat9() {
    // ADD|OR|ADC|SBB|AND|SUB|XOR|CMP|MOV reg, r/m - OPCODE 9
    executeOpcode9Body()
}

internal fun Emulator8086.executeXlat12() {
    // ROL|ROR|RCL|RCR|SHL|SHR|SAR reg/mem, 1/CL/imm - OPCODE 12
    // Read mem[rm_addr] at full operand width here, and
    // for rotates (i_reg<4) re-reads the full original value into scratch2_uint
    // just before shifting, for later wraparound-bit
    // extraction. The actual shift is what sets
    // op_dest to the pre-shift value, for both rotates and shifts alike.
    val originalValue = readOperand(rmAddr)
    val scratchUint = if (extra != 0) { // xxx reg/mem, imm8 (C0/C1)
        val immOff = 2 + if (iMod == 3) 0 else if (addressSizeOverrideEn != 0) {
            modRm32ExtraBytes()
        } else {
            iMod * (if (iMod != 3) 1 else 0) + 2 * (if (iMod == 0 && iRm == 6) 1 else 0)
        }
        val imm = instructionRead8((regIp + immOff) and 0xFFFF)
        regIp++ // imm8 not included in baseInstSize
        maskShiftCount(imm.toByte().toInt())
    } else if (iD) { // xxx reg/mem, CL
        // The 8086 uses the full CL value. 286+ masks to five bits.
        maskShiftCount(regs8[REG_CL].toUByte().toInt())
    } else { // xxx reg/mem, 1
        1
    }

    if (scratchUint != 0) {
        val width = topBit()
        val widthMask = cfWidthMask()
        val signMask = 1 shl (width - 1)
        opDest = originalValue
        var result = originalValue and widthMask
        var carry = regs8[FLAG_CF].toInt() != 0

        repeat(scratchUint) {
            when (iReg) {
                0 -> { // ROL
                    carry = result and signMask != 0
                    result = ((result shl 1) and widthMask) or if (carry) 1 else 0
                }
                1 -> { // ROR
                    carry = result and 1 != 0
                    result = (result ushr 1) or if (carry) signMask else 0
                }
                2 -> { // RCL
                    val nextCarry = result and signMask != 0
                    result = ((result shl 1) and widthMask) or if (carry) 1 else 0
                    carry = nextCarry
                }
                3 -> { // RCR
                    val nextCarry = result and 1 != 0
                    result = (result ushr 1) or if (carry) signMask else 0
                    carry = nextCarry
                }
                4, 6 -> { // SHL / SAL (286: /6 aliases SHL; 8086 /6 is SETMO)
                    if (iReg == 6 && shiftReg6IsSetmo()) {
                        result = widthMask // SETMO
                    } else {
                        carry = result and signMask != 0
                        result = (result shl 1) and widthMask
                    }
                }
                5 -> { // SHR
                    carry = result and 1 != 0
                    result = result ushr 1
                }
                7 -> { // SAR
                    carry = result and 1 != 0
                    result = (result ushr 1) or (result and signMask)
                }
            }
        }

        if (!(iReg == 6 && shiftReg6IsSetmo())) setCF(carry)
        if (iReg > 3) { // Shift operations
            setOpcode(0x10) // Decode like ADC
        }

        val shiftOf = overflowAfterShiftRotate(
            operation = if (iReg == 6 && !shiftReg6IsSetmo()) 4 else iReg,
            count = scratchUint,
            originalValue = opDest,
            result = result,
            carry = carry,
            signMask = signMask,
        )
        if (shiftOf != OF_UNCHANGED) setOF(shiftOf != 0)

        opResult = result and widthMask
        writeOperand(rmAddr, opResult)
    }
}

internal fun Emulator8086.executeXlat15() {
    // TEST reg, r/m - OPCODE 15
    memOp(opFromAddr, ALU_AND, opToAddr, writeBack = false)
}

internal fun Emulator8086.executeXlat47() {
    // TEST AL/AX, immed - OPCODE 47
    val al = if (iW) regs16[REG_AX].toInt() and 0xFFFF else regs8[REG_AL].toUByte().toInt()
    opResult = al and iData0
}
