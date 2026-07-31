package com.trugath.k8086.cpu

/**
 * ALU / arithmetic / shift / TEST xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

internal fun Emulator8086.executeOpcode8Body() {
    opToAddr = rmAddr
    iD = iD or (!iW)
    val scratchValue = if (iD) iData2.toByte().toInt() else iData2
    regs16[REG_SCRATCH] = scratchValue.toShort()
    opFromAddr = REGS_BASE + 2 * REG_SCRATCH
    regIp += (if (!iD) 1 else 0) + 1
    extra = iReg
    setOpcode(0x08 * extra) // Decode like the reg,r/m variant of the same operation
}

// Shared body for OPCODE 9 (ADD|OR|ADC|SBB|AND|SUB|XOR|CMP|MOV reg, r/m).
internal fun Emulator8086.executeOpcode9Body() {
    when (extra) {
        0 -> { // ADD
            memOp(opToAddr, ALU_ADD, opFromAddr)
            // Carry mirrors the C reference's unsigned `op_result < op_dest`, where
            // op_result is truncated to the operand width. Kotlin's opResult is the
            // untruncated sum, so mask it before the (now signed-safe) comparison.
            setCF((opResult and cfWidthMask()) < opDest)
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
            val masked = opResult and cfWidthMask()
            setCF(masked < opDest || (cf != 0 && masked == opDest))
            setAFOfArith()
        }
        3 -> { // SBB
            val cf = regs8[FLAG_CF].toInt()
            memOp(opToAddr, ALU_SUB, opFromAddr)
            opResult -= cf
            writeOperand(opToAddr, opResult)
            // Borrow (unsigned) when the truncated difference exceeds the minuend; the
            // carry-in edge (subtrahend + borrow wraps to 0) is caught by the equality.
            val masked = opResult and cfWidthMask()
            setCF(masked > opDest || (cf != 0 && masked == opDest))
            setAFOfArith()
        }
        4 -> { // AND
            memOp(opToAddr, ALU_AND, opFromAddr)
        }
        5 -> { // SUB
            memOp(opToAddr, ALU_SUB, opFromAddr)
            setCF((opResult and cfWidthMask()) > opDest)
        }
        6 -> { // XOR
            memOp(opToAddr, ALU_XOR, opFromAddr)
        }
        7 -> { // CMP
            memOp(opToAddr, ALU_SUB, opFromAddr, writeBack = false)
            // Borrow (unsigned): the subtrahend exceeds the minuend. Masking the raw
            // difference to the operand width makes 0 - 1 read back as 0xFF/0xFFFF,
            // matching the reference's unsigned comparison against op_dest.
            setCF((opResult and cfWidthMask()) > opDest)
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
        val newIp = readOperand(opFromAddr)
        if (pendingException >= 0) return
        val newCs = if ((iReg and 1) != 0) {
            if (rmIsMemory && wordOffsetFaults((rmOffset + 2) and 0xFFFF)) {
                raiseException(exceptionForWordAccess(rmSegment))
                return
            }
            guestRead8(rmAddress(2)) or (guestRead8(rmAddress(3)) shl 8)
        } else {
            null
        }
        if (iReg == 3) { // CALL (far)
            push(regs16[REG_CS].toInt() and 0xFFFF)
            if (pendingException >= 0) return
        }
        if ((iReg and 2) != 0) { // CALL (near or far)
            val callOffset = 2 + iMod * (if (iMod != 3) 1 else 0) + 2 * (if (iMod == 0 && iRm == 6) 1 else 0)
            push(regIp + callOffset)
            if (pendingException >= 0) return
        }
        if (newCs != null) {
            regs16[REG_CS] = newCs.toShort()
        }
        regIp = newIp
        setOpcode(0x9A) // Decode like CALL
    }
}

internal fun Emulator8086.executeXlat6() {
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
            setOpcode(0x20) // Decode like AND
            regIp += if (iW) 2 else 1
            regs16[REG_SCRATCH] = iData2.toShort()
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
        val immOff = 2 + iMod * (if (iMod != 3) 1 else 0) +
            2 * (if (iMod == 0 && iRm == 6) 1 else 0)
        val imm = guestRead8(
            segreg(REG_CS, REG_ZERO, (regIp + immOff) and 0xFFFF),
        )
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
        val widthMask = if (iW) 0xFFFF else 0xFF
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
