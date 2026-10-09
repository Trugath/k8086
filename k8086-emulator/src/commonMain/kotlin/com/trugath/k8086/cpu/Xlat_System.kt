package com.trugath.k8086.cpu

/**
 * Prefixes, BCD, 186/286 extras, LOCK/HLT/ESC xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

// --- xlat opcode group: system ---

internal fun Emulator8086.executeXlat23() {
    // REPxx - OPCODE 23
    repOverrideEn = 2
    repMode = if (iW) 1 else 0
    if (segOverrideEn != 0) segOverrideEn++
    if (lockOverrideEn != 0) lockOverrideEn++
    if (operandSizeOverrideEn != 0) operandSizeOverrideEn++
    if (addressSizeOverrideEn != 0) addressSizeOverrideEn++
}

internal fun Emulator8086.executeXlat27() {
    // xS: segment overrides - OPCODE 27 (also FS/GS on 386)
    segOverrideEn = 2
    segOverride = extra
    if (repOverrideEn != 0) repOverrideEn++
    if (lockOverrideEn != 0) lockOverrideEn++
    if (operandSizeOverrideEn != 0) operandSizeOverrideEn++
    if (addressSizeOverrideEn != 0) addressSizeOverrideEn++
}

internal fun Emulator8086.executeXlat28() {
    // DAA/DAS - OPCODE 28
    executeDaaDas(das = extra != 0)
}

internal fun Emulator8086.executeXlat29() {
    // AAA/AAS - OPCODE 29
    opResult = aaaAas(extra - 1)
}

internal fun Emulator8086.executeXlat30() {
    // CBW / CWDE (66h) - OPCODE 30
    if (operandSizeOverrideEn != 0) {
        // CWDE: sign-extend AX into EAX
        val ax = regs16[REG_AX].toInt().toShort().toInt()
        setReg32(REG_AX, ax)
    } else {
        iW = false
        regs8[REG_AH] = (-signOf(regs8[REG_AL].toUByte().toInt())).toByte()
    }
}

internal fun Emulator8086.executeXlat31() {
    // CWD / CDQ (66h) - OPCODE 31
    if (operandSizeOverrideEn != 0) {
        val eax = reg32u(REG_AX)
        val sign = if ((eax and 0x80000000.toInt()) != 0) -1 else 0
        setReg32(REG_DX, sign)
    } else {
        iW = true
        regs16[REG_DX] = (-signOf(regs16[REG_AX].toInt() and 0xFFFF)).toShort()
    }
}

internal fun Emulator8086.executeXlat41() {
    // AAM - OPCODE 41
    val divisor = iData0 and 0xFF
    // 8086 clears AF on AAM (success or #DE).
    setAF(false)
    if (divisor != 0) {
        val al = regs8[REG_AL].toUByte().toInt()
        regs8[REG_AH] = (al / divisor).toByte()
        opResult = al % divisor
        regs8[REG_AL] = (opResult and 0xFF).toByte()
    } else {
        // Flag update is skipped after #DE is pending, so apply here.
        applyAamDivideErrorFlags()
        raiseDivideError()
    }
}

internal fun Emulator8086.executeXlat42() {
    // AAD - OPCODE 42
    iW = false
    val al = regs8[REG_AL].toUByte().toInt()
    val ah = regs8[REG_AH].toUByte().toInt()
    opResult = (al + (iData0 and 0xFF) * ah) and 0xFF
    regs16[REG_AX] = (opResult and 0xFF).toShort()
    setAF(false)
}

internal fun Emulator8086.executeXlat43() {
    // SALC - OPCODE 43
    regs8[REG_AL] = (-regs8[FLAG_CF].toInt()).toByte()
}

internal fun Emulator8086.executeXlat44() {
    // XLAT - OPCODE 44
    val al = regs8[REG_AL].toUByte().toInt()
    val seg = if (segOverrideEn != 0) segOverride else REG_DS
    val addr = segreg(seg, REG_BX, al)
    regs8[REG_AL] = guestRead8(addr).toByte()
}

internal fun Emulator8086.executeXlat45() {
    // CMC - OPCODE 45
    regs8[FLAG_CF] = ((regs8[FLAG_CF].toInt() xor 1) and 1).toByte()
}

internal fun Emulator8086.executeXlat48() {

    // Formerly used for raw 0x0F when it was stubbed as a no-op. Real 8086/8088
    // 0x0F is POP CS (xlat 26); this case remains only as a decode-table safety
    // net so an accidental remapping does not hit the unhandled-opcode path.
}

internal fun Emulator8086.executeXlat49() {
    // PUSHA / PUSHAD (66h) (0x60)
    iW = true
    val dword = operandSizeOverrideEn != 0
    val step = if (dword) 4 else 2
    val sp = regs16[REG_SP].toInt() and 0xFFFF
    val startSpHi = gprHi[REG_SP]
    if (!dword) {
        for (i in 1..8) {
            val off = (sp - step * i) and 0xFFFF
            if (wordOffsetFaults(off)) {
                raiseException(exceptionForWordAccess(REG_SS))
                return
            }
        }
    }
    // 386EX PUSHAD: dword-aligned SP may wrap (SP=8 → FFE8). Misaligned SP
    // below 0x20 still writes the full 32-byte image (including through
    // SS:FFFF), then #SS with SP restored (6660.MOO #302/#956).
    val pushadWrapFault = dword && sp < 0x20 && (sp and 3) != 0
    if (dword) {
        fun pushAdDword(value: Int) {
            val oldSp = regs16[REG_SP].toInt() and 0xFFFF
            val newSp = (oldSp - 4) and 0xFFFF
            regs16[REG_SP] = newSp.toShort()
            writeSegmentDwordWrapped(REG_SS, newSp, value)
        }
        pushAdDword(reg32u(REG_AX))
        pushAdDword(reg32u(REG_CX))
        pushAdDword(reg32u(REG_DX))
        pushAdDword(reg32u(REG_BX))
        pushAdDword(sp) // original SP, zero-extended
        pushAdDword(reg32u(REG_BP))
        pushAdDword(reg32u(REG_SI))
        pushAdDword(reg32u(REG_DI))
    } else {
        push(regs16[REG_AX].toInt() and 0xFFFF)
        push(regs16[REG_CX].toInt() and 0xFFFF)
        push(regs16[REG_DX].toInt() and 0xFFFF)
        push(regs16[REG_BX].toInt() and 0xFFFF)
        push(sp)
        push(regs16[REG_BP].toInt() and 0xFFFF)
        push(regs16[REG_SI].toInt() and 0xFFFF)
        push(regs16[REG_DI].toInt() and 0xFFFF)
    }
    if (pushadWrapFault) {
        regs16[REG_SP] = sp.toShort()
        gprHi[REG_SP] = startSpHi
        raiseException(exceptionForWordAccess(REG_SS))
    }
}

internal fun Emulator8086.executeXlat50() {
    // POPA / POPAD (66h) (0x61)
    // On mid-instruction #SS/#GP, 386EX keeps already-written GPRs but restores
    // SP to the value at instruction start before the fault frame is pushed.
    iW = true
    val dword = operandSizeOverrideEn != 0
    val startSp = reg16u(REG_SP)
    val startSpHi = gprHi[REG_SP]
    // No blanket wrap reject: POPAD at SP=FFF4 succeeds (6661.MOO); SP=FFFE
    // still #SS via dword pop. Mid-fault restores SP below.
    fun commit(reg: Int, value: Int) {
        if (dword) setReg32(reg, value) else regs16[reg] = (value and 0xFFFF).toShort()
    }
    fun abort(): Boolean {
        if (pendingException < 0) return false
        regs16[REG_SP] = startSp.toShort()
        gprHi[REG_SP] = startSpHi
        return true
    }
    // The 286 commits GPRs only after all reads succeed; the 386EX commits
    // each completed pop even if a later pop faults.
    val incremental = has386Prefixes
    val di = pop(); if (abort()) return else if (incremental) commit(REG_DI, di)
    val si = pop(); if (abort()) return else if (incremental) commit(REG_SI, si)
    val bp = pop(); if (abort()) return else if (incremental) commit(REG_BP, bp)
    val savedSp = pop(); if (abort()) return
    if (dword) {
        setReg32(REG_SP, (savedSp and 0xFFFF0000.toInt()) or reg16u(REG_SP))
    }
    val bx = pop(); if (abort()) return else if (incremental) commit(REG_BX, bx)
    val dx = pop(); if (abort()) return else if (incremental) commit(REG_DX, dx)
    val cx = pop(); if (abort()) return else if (incremental) commit(REG_CX, cx)
    val ax = pop(); if (abort()) return else if (incremental) commit(REG_AX, ax)
    if (!incremental) {
        commit(REG_DI, di)
        commit(REG_SI, si)
        commit(REG_BP, bp)
        commit(REG_BX, bx)
        commit(REG_DX, dx)
        commit(REG_CX, cx)
        commit(REG_AX, ax)
    }
}

internal fun Emulator8086.executeXlat51() {
    // BOUND r16,m32 / r32,m64 (66h)
    if (addressSizeOverrideEn != 0 && rmIsMemory && rmOffset > 0x10000 - (if (operandSizeOverrideEn != 0) 8 else 4)) {
        raiseException(exceptionForWordAccess(rmSegment))
        return
    }
    iW = true
    iD = false
    decodeRmReg()
    if (iMod == 3) {
        raiseException(6) // #UD — register form is invalid
        return
    }
    val dword = operandSizeOverrideEn != 0
    if (dword) {
        val index = reg32u(iReg)
        if (dwordOffsetFaults(rmOffset and 0xFFFF) ||
            dwordOffsetFaults((rmOffset + 4) and 0xFFFF)
        ) {
            raiseException(exceptionForWordAccess(rmSegment))
            return
        }
        val low = readSegmentDword(rmSegment, rmOffset and 0xFFFF)
        if (pendingException >= 0) return
        val high = readSegmentDword(rmSegment, (rmOffset + 4) and 0xFFFF)
        if (pendingException >= 0) return
        // Signed 32-bit compare
        if (index < low || index > high) {
            raiseException(5) // #BR
        }
    } else {
        val index = regs16[iReg].toInt().toShort().toInt()
        val low = readOperand(rmAddr).toShort().toInt()
        if (pendingException >= 0) return
        if (wordOffsetFaults((rmOffset + 2) and 0xFFFF)) {
            raiseException(exceptionForWordAccess(rmSegment))
            return
        }
        val high = (
            guestRead8(rmAddress(2)) or (guestRead8(rmAddress(3)) shl 8)
            ).toShort().toInt()
        if (index < low || index > high) {
            raiseException(5) // #BR
        }
    }
}

internal fun Emulator8086.executeXlat52() {
    // PUSH imm16 (0x68) | PUSH imm8 (0x6A) | IMUL r,r/m,imm (0x69/0x6B)
    when (rawOpcodeId) {
        0x68 -> { // PUSH imm16 / imm32 (66h)
            iW = true
            if (operandSizeOverrideEn != 0) {
                val lo = iData0 and 0xFFFF
                val hi = instructionRead8((regIp + 3) and 0xFFFF) or
                    (instructionRead8((regIp + 4) and 0xFFFF) shl 8)
                push(lo or (hi shl 16))
            } else {
                push(iData0 and 0xFFFF)
            }
        }
        0x6A -> { // PUSH imm8 (sign-extended to 16 or 32)
            iW = true
            val imm = iData0.toByte().toInt()
            push(if (operandSizeOverrideEn != 0) imm else imm and 0xFFFF)
        }
        0x69, 0x6B -> { // IMUL r16/32, r/m, imm16/imm32/imm8
            iW = true
            iD = false
            decodeRmReg()
            val opsize32 = operandSizeOverrideEn != 0
            val src = if (opsize32) {
                readOperand(rmAddr)
            } else {
                readOperand(rmAddr).toShort().toInt()
            }
            if (pendingException >= 0) return
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
            val immOffset = 2 + dispBytes
            val imm = if (rawOpcodeId == 0x6B) {
                instructionRead8((regIp + immOffset) and 0xFFFF)
                    .toByte().toInt()
            } else if (opsize32) {
                val b0 = instructionRead8((regIp + immOffset) and 0xFFFF)
                val b1 = instructionRead8((regIp + immOffset + 1) and 0xFFFF)
                val b2 = instructionRead8((regIp + immOffset + 2) and 0xFFFF)
                val b3 = instructionRead8((regIp + immOffset + 3) and 0xFFFF)
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                val lo = instructionRead8((regIp + immOffset) and 0xFFFF)
                val hi = instructionRead8((regIp + immOffset + 1) and 0xFFFF)
                (lo or (hi shl 8)).toShort().toInt()
            }
            val product = src.toLong() * imm.toLong()
            if (opsize32) {
                val truncated = product.toInt()
                setReg32(iReg, truncated)
                opResult = truncated
                val overflows = product != truncated.toLong()
                setCF(overflows)
                setOF(overflows)
            } else {
                val truncated = product.toInt() and 0xFFFF
                regs16[iReg] = truncated.toShort()
                opResult = truncated
                val overflows = product != truncated.toShort().toLong()
                setCF(overflows)
                setOF(overflows)
            }
            setFlagsType = FLAGS_UPDATE_SZP
        }
    }
}

internal fun Emulator8086.executeXlat54() {
    // ENTER (0xC8)
    iW = true
    val frameSize = iData0 and 0xFFFF
    val nesting = (iData1 shr 8) and 0x1F
    // 286: on a wrap #GP, SP/BP roll back to the pre-instruction values
    // but successful stack writes remain (residue below the exception frame).
    val savedSp = regs16[REG_SP].toInt() and 0xFFFF
    val savedBp = regs16[REG_BP].toInt() and 0xFFFF
    val savedEbp = getReg32(REG_BP)
    val dword = operandSize32()
    val width = operandBytes()
    fun rollbackEnter(): Boolean {
        if (pendingException < 0) return false
        regs16[REG_SP] = savedSp.toShort()
        regs16[REG_BP] = savedBp.toShort()
        if (dword) setReg32(REG_BP, savedEbp)
        return true
    }
    fun pushEnter(value: Int) {
        val nextSp = ((regs16[REG_SP].toInt() and 0xFFFF) - width) and 0xFFFF
        // ENTER's frame writes must fit within SS even though ordinary PUSH
        // uses the wrapped stack-write path on the 386EX.
        if (dword && dwordOffsetFaults(nextSp)) {
            raiseException(exceptionForWordAccess(REG_SS))
        } else {
            push(value)
        }
    }
    pushEnter(if (dword) savedEbp else savedBp)
    if (rollbackEnter()) return
    val frameTemp = regs16[REG_SP].toInt() and 0xFFFF
    if (nesting > 0) {
        var level = 1
        while (level < nesting) {
            regs16[REG_BP] = ((regs16[REG_BP].toInt() and 0xFFFF) - width).toShort()
            val offset = regs16[REG_BP].toInt() and 0xFFFF
            val link = if (dword) readSegmentDword(REG_SS, offset) else readSegmentWord(REG_SS, offset)
            if (rollbackEnter()) return
            pushEnter(link)
            if (rollbackEnter()) return
            level++
        }
        pushEnter(frameTemp)
        if (rollbackEnter()) return
    }
    if (dword) setReg32(REG_BP, frameTemp) else regs16[REG_BP] = frameTemp.toShort()
    regs16[REG_SP] = (
        (regs16[REG_SP].toInt() and 0xFFFF) - frameSize
        ).toShort()
}

internal fun Emulator8086.executeXlat55() {
    // LEAVE (0xC9)
    iW = true
    val bp = regs16[REG_BP].toInt() and 0xFFFF
    // Fault before SP←BP so a wrap at BP=FFFFh keeps the original SP.
    if (if (operandSize32()) dwordOffsetFaults(bp) else wordOffsetFaults(bp)) {
        raiseException(exceptionForWordAccess(REG_SS))
        return
    }
    regs16[REG_SP] = bp.toShort()
    val newBp = pop()
    if (pendingException >= 0) return
    if (operandSize32()) setReg32(REG_BP, newBp) else regs16[REG_BP] = newBp.toShort()
}

internal fun Emulator8086.executeXlat56() {
    // 286/386 0x0F escape
    execute0FEscape()
}

/** 80386 66h/67h operand-size / address-size override prefixes. */
internal fun Emulator8086.executeXlat58() {
    if (extra == 0) operandSizeOverrideEn = 2 else addressSizeOverrideEn = 2
    if (segOverrideEn != 0) segOverrideEn++
    if (repOverrideEn != 0) repOverrideEn++
    if (lockOverrideEn != 0) lockOverrideEn++
    if (extra == 0) {
        if (addressSizeOverrideEn != 0 && addressSizeOverrideEn != 2) addressSizeOverrideEn++
    } else {
        if (operandSizeOverrideEn != 0 && operandSizeOverrideEn != 2) operandSizeOverrideEn++
    }
}

internal fun Emulator8086.executeXlat53() {
    // WAIT (0x9B) | LOCK (0xF0/0xF1) | HLT (0xF4) | ESC/FPU (0xD8-0xDF)
    when (rawOpcodeId) {
        0xF4 -> halted = true // HLT: pause until the next hardware interrupt
        0xF0, 0xF1 -> {
            // LOCK is a prefix: keep instructionStartIp and hold for the next op.
            lockOverrideEn = 2
            if (segOverrideEn != 0) segOverrideEn++
            if (repOverrideEn != 0) repOverrideEn++
            if (operandSizeOverrideEn != 0) operandSizeOverrideEn++
            if (addressSizeOverrideEn != 0) addressSizeOverrideEn++
        }
        in 0xD8..0xDF -> {
            val fpu = mathCoprocessor
            if (fpu != null) {
                fpuAccess.instructionPointer = regIp and 0xFFFF
                fpuAccess.instructionCs = regs16[REG_CS].toInt() and 0xFFFF
                fpuAccess.dataPointer = rmAddr and 0xFFFF
                fpuAccess.dataCs = regs16[REG_DS].toInt() and 0xFFFF
                fpu.executeEsc(
                    opcode = rawOpcodeId,
                    iMod = iMod,
                    iReg = iReg,
                    iRm = iRm,
                    rmAddr = rmAddr,
                    access = fpuAccess,
                )
            }
            // Without an 8087, ESC is a no-op on the 5155.
        }
        // The software FPU executes synchronously, so WAIT has no BUSY#
        // interval to stall on.
    }
}
