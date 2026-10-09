package com.trugath.k8086.cpu

/**
 * MOV / PUSH / POP / XCHG / flags / LES-LDS xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

internal fun Emulator8086.executeXchgBody() {
    if (opToAddr != opFromAddr) {
        // XCHG using XOR trick: a^=b, b^=a, a^=b
        memOp(opToAddr, ALU_XOR, opFromAddr)
        memOp(opFromAddr, ALU_XOR, opToAddr)
        memOp(opToAddr, ALU_XOR, opFromAddr)
    }
}

// Execute translated opcode - matches C code's switch(xlat_opcode_id)
// This function executes based on xlat_opcode_id (0-48), not raw opcodes
// --- xlat opcode group: move ---

internal fun Emulator8086.executeXlat1(iReg4bit: Int) {
    // MOV reg, imm - OPCODE 1 (B0–BF). With 66h: MOV r32, imm32.
    iW = (rawOpcodeId and 8) != 0
    val imm = if (iW && operandSizeOverrideEn != 0) {
        val lo = iData0 and 0xFFFF
        val hi = instructionRead8((regIp + 3) and 0xFFFF) or
            (instructionRead8((regIp + 4) and 0xFFFF) shl 8)
        lo or (hi shl 16)
    } else {
        iData0
    }
    val regAddr = getRegAddr(iReg4bit)
    writeOperand(regAddr, imm)
    opResult = imm and cfWidthMask()
}

internal fun Emulator8086.executeXlat3(iReg4bit: Int) {
    // PUSH r16/r32 - OPCODE 3
    if (operandSizeOverrideEn != 0) {
        val value = reg32u(iReg4bit)
        // 386 PUSH ESP stores the pre-decrement value (same rule as 286 PUSH SP).
        push(if (iReg4bit == REG_SP) value else value)
    } else {
        val value = regs16[iReg4bit].toInt() and 0xFFFF
        push(if (iReg4bit == REG_SP) valuePushedForSp(value) else value)
    }
}

internal fun Emulator8086.executeXlat4(iReg4bit: Int) {
    // POP r16/r32 - OPCODE 4
    val value = pop()
    if (pendingException >= 0) return
    if (operandSizeOverrideEn != 0) setReg32(iReg4bit, value)
    else regs16[iReg4bit] = value.toShort()
}

internal fun Emulator8086.executeXlat10() {
    // MOV sreg, r/m | POP r/m | LEA reg, r/m - OPCODE 10
    if (!iW) { // MOV
        iW = true
        if (invalidMovSregField(iReg, toSreg = iD)) {
            pendingException = 6 // #UD precedes data-address faults.
            return
        }
        // 8086 aliases ModR/M sreg 4..7 to ES..DS; 386 maps 4/5 to FS/GS.
        iReg = sregFromModrmField(iReg)
        decodeRmReg()
        when {
            !iD && operandSizeOverrideEn != 0 && iMod == 3 -> {
                // 66h MOV r32, sreg: zero-extend selector into the GPR (668C.MOO).
                setReg32(iRm, regs16[iReg].toInt() and 0xFFFF)
            }
            iD -> {
                // MOV sreg, r/m: always a 16-bit selector load. A 66h dword
                // memOp would write past the sreg slot into the next register
                // (SS→DS, etc. — 668E.MOO).
                val savedOpSize = operandSizeOverrideEn
                operandSizeOverrideEn = 0
                iW = true
                memOp(opToAddr, ALU_MOV, opFromAddr)
                operandSizeOverrideEn = savedOpSize
                if (iReg == REG_SS) armSsInterruptShadow()
            }
            else -> {
                // MOV r/m, sreg — memory keeps a 16-bit store even under 66h.
                val savedOpSize = operandSizeOverrideEn
                operandSizeOverrideEn = 0
                iW = true
                memOp(opToAddr, ALU_MOV, opFromAddr)
                operandSizeOverrideEn = savedOpSize
            }
        }
    } else if (!iD) { // LEA
        if (iMod == 3 && leaRegisterFormRaisesUd()) {
            raiseException(6) // #UD — LEA with register form (286+)
            return
        }
        segOverrideEn = 1
        segOverride = REG_ZERO
        decodeRmReg()
        val regAddr = getRegAddr(iReg)
        writeOperand(regAddr, if (addressSizeOverrideEn != 0) rmOffset else rmAddr)
        opResult = rmAddr and (if (iW) 0xFFFF else 0xFF)
    } else { // POP r/m — only /0 is valid; other reg fields are #UD (668F.MOO).
        if (iReg != 0) {
            pendingException = 6
            return
        }
        var destinationException = pendingException
        // Probe the destination before committing SP (fault must leave SP unchanged).
        if (rmIsMemory && (
                if (operandSize32()) dwordOffsetFaults(rmOffset and 0xFFFF)
                else wordOffsetFaults(rmOffset and 0xFFFF)
            )
        ) {
            if (destinationException < 0) destinationException = exceptionForWordAccess(rmSegment)
        }
        pendingException = -1
        val savedSp = regs16[REG_SP]
        val value = pop()
        if (pendingException >= 0) return
        if (destinationException >= 0) {
            regs16[REG_SP] = savedSp
            pendingException = destinationException
            return
        }
        if (rmIsMemory && addressSizeOverrideEn != 0) {
            // POP's ESP-based destination uses the incremented stack pointer.
            decodeRmReg()
            if (pendingException >= 0) {
                regs16[REG_SP] = savedSp
                return
            }
        }
        writeOperand(rmAddr, value)
    }
}

internal fun Emulator8086.executeXlat11() {
    // MOV AL/AX, [loc] - OPCODE 11
    if (addressSizeOverrideEn != 0) {
        val offset = (iData0 and 0xFFFF) or
            (instructionRead8((regIp + 3) and 0xFFFF) shl 16) or
            (instructionRead8((regIp + 4) and 0xFFFF) shl 24)
        val segment = if (segOverrideEn != 0) segOverride else REG_DS
        if ((offset ushr 16) != 0) {
            raiseException(exceptionForWordAccess(segment))
            return
        }
        rmIsMemory = true
        rmOffset = offset
        rmSegment = segment
        rmAddr = segreg(segment, REG_ZERO, offset)
        opFromAddr = if (iD) rmAddr else REGS_BASE
        opToAddr = if (iD) REGS_BASE else rmAddr
        regIp += 2 // moffs32 instead of moffs16
        memOp(opFromAddr, ALU_MOV, opToAddr)
        return
    }
    iMod = 0
    iReg = 0
    iRm = 6
    iData1 = iData0
    decodeRmReg()
    memOp(opFromAddr, ALU_MOV, opToAddr)
}

internal fun Emulator8086.executeXlat16(iReg4bit: Int) {
    // XCHG AX, regs16 - OPCODE 16 (falls through to 24 in C)
    iW = true
    opToAddr = REGS_BASE
    opFromAddr = getRegAddr(iReg4bit)
    executeXchgBody()
}

internal fun Emulator8086.executeXlat24() {
    // NOP|XCHG reg, r/m - OPCODE 24 (chains from 16)
    executeXchgBody()
}

internal fun Emulator8086.executeXlat20() {
    // MOV r/m, immed - OPCODE 20
    if (this is Emulator80286 && iReg != 0) {
        pendingException = 6 // C6/C7 only define the /0 encoding on 286+.
        return
    }
    // Destination is the r/m operand. Raw opcodes C6/C7 have i_d = 1, so
    // DECODE_RM_REG has already swapped op_from_addr to hold rm_addr (memory
    // for mod<3, or the r/m register for mod==3). The C reference writes to
    // mem[op_from_addr]; using getRegAddr(iReg) here wrongly targeted AL/AX.
    // With 66h, C7 takes imm32; iData2 only holds the low word.
    val imm = if (operandSize32()) {
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
        val immOff = 2 + dispBytes
        val lo = instructionRead8((regIp + immOff) and 0xFFFF) or
            (instructionRead8((regIp + immOff + 1) and 0xFFFF) shl 8)
        val hi = instructionRead8((regIp + immOff + 2) and 0xFFFF) or
            (instructionRead8((regIp + immOff + 3) and 0xFFFF) shl 8)
        lo or (hi shl 16)
    } else if (addressSizeOverrideEn != 0) {
        val immOff = 2 + if (iMod < 3) modRm32ExtraBytes() else 0
        val lo = instructionRead8((regIp + immOff) and 0xFFFF)
        if (iW) lo or (instructionRead8((regIp + immOff + 1) and 0xFFFF) shl 8)
        else lo
    } else {
        iData2
    }
    writeOperand(opFromAddr, imm)
    opResult = imm and cfWidthMask()
}

internal fun Emulator8086.executeXlat25() {
    // PUSH reg/sreg - OPCODE 25 (66h PUSH r32 or zero-extended sreg)
    val value = if (operandSizeOverrideEn != 0 && extra <= REG_DI) {
        reg32u(extra)
    } else {
        regs16[extra].toInt() and 0xFFFF
    }
    push(value)
}

internal fun Emulator8086.executeXlat26() {
    // POP reg/sreg - OPCODE 26 (66h POP r32; sreg: word selector + SP+=4)
    val isSreg = extra > REG_DI
    val value = if (isSreg) popSelector() else pop()
    if (pendingException >= 0) return
    if (operandSizeOverrideEn != 0 && !isSreg) {
        setReg32(extra, value)
    } else {
        regs16[extra] = (value and 0xFFFF).toShort()
    }
    if (extra == REG_SS) armSsInterruptShadow()
}

internal fun Emulator8086.executeXlat33() {
    // PUSHF / PUSHFD - OPCODE 33
    // 386EX real-mode PUSHFD stores only the low 16 bits of EFLAGS
    // zero-extended (669C.MOO); the SST FFFCxxxx reserved pattern is not written.
    val flags = if (operandSizeOverrideEn != 0) {
        makeFlags() and 0xFFFF
    } else {
        makeFlags()
    }
    push(flags)
}

internal fun Emulator8086.executeXlat34() {
    // POPF / POPFD - OPCODE 34
    // If the stack read #SS/#GPs (e.g. POPFD at SP>FFFC), do not clobber EFLAGS
    // with the zero return — the interrupt frame must push the pre-fault flags
    // (669D.MOO #SS vectors).
    val value = pop()
    if (pendingException >= 0) return
    setFlags(value and popfValueMask())
}

internal fun Emulator8086.executeXlat35() {
    // SAHF - OPCODE 35
    val flags = makeFlags()
    setFlags((flags and 0xFF00) or (regs8[REG_AH].toUByte().toInt()))
}

internal fun Emulator8086.executeXlat36() {
    // LAHF - OPCODE 36
    val flags = makeFlags()
    regs8[REG_AH] = (flags and 0xFF).toByte()
}

internal fun Emulator8086.executeXlat37() {
    // LES|LDS reg, r/m - OPCODE 37
    // 16-bit opsize → m16:16; 66h → m16:32 (offset via memOp width, segment at +4).
    iW = true
    iD = true
    if (iMod == 3 && lesLdsRegisterFormRaisesUd()) {
        raiseException(6) // #UD
        return
    }
    decodeRmReg()
    val far32 = operandSizeOverrideEn != 0
    val segDisp = if (far32) 4 else 2
    if (rmIsMemory) {
        if (addressSizeOverrideEn != 0 && rmOffset > 0x10000 - segDisp - 2) {
            raiseException(exceptionForWordAccess(rmSegment))
            return
        }
        if (far32) {
            if (dwordOffsetFaults(rmOffset and 0xFFFF) ||
                wordOffsetFaults((rmOffset + 4) and 0xFFFF)
            ) {
                raiseException(exceptionForWordAccess(rmSegment))
                return
            }
        } else {
            if (wordOffsetFaults(rmOffset and 0xFFFF)) {
                raiseException(exceptionForWordAccess(rmSegment))
                return
            }
            if (wordOffsetFaults((rmOffset + 2) and 0xFFFF)) {
                raiseException(exceptionForWordAccess(rmSegment))
                return
            }
        }
    }
    memOp(opToAddr, ALU_MOV, opFromAddr)
    if (pendingException >= 0) return
    val segValue = guestRead8(rmAddress(segDisp)) or (guestRead8(rmAddress(segDisp + 1)) shl 8)
    // MEM_OP(REGS_BASE + extra, =, rm_addr + 2):
    // for this opcode, the BIOS table's `extra` is already a byte offset into
    // the register file (e.g. REG_ES*2), not a plain register index - unlike
    // extra's usage elsewhere (regs16[extra]), so it must NOT be multiplied by 2.
    val segReg = REGS_BASE + extra
    writeWord(segReg, segValue)
}
