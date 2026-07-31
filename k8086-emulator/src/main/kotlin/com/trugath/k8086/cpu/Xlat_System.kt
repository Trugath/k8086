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
}

internal fun Emulator8086.executeXlat27() {
    // xS: segment overrides - OPCODE 27
    segOverrideEn = 2
    segOverride = extra
    if (repOverrideEn != 0) repOverrideEn++
    if (lockOverrideEn != 0) lockOverrideEn++
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
    // CBW - OPCODE 30
    regs8[REG_AH] = (-signOf(regs8[REG_AL].toUByte().toInt())).toByte()
}

internal fun Emulator8086.executeXlat31() {
    // CWD - OPCODE 31
    regs16[REG_DX] = (-signOf(regs16[REG_AX].toInt() and 0xFFFF)).toShort()
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
    // PUSHA (0x60)
    iW = true
    val sp = regs16[REG_SP].toInt() and 0xFFFF
    // 286: fault before any write if a word push would land at offset FFFFh.
    for (i in 1..8) {
        if (wordOffsetFaults((sp - 2 * i) and 0xFFFF)) {
            raiseException(13)
            return
        }
    }
    push(regs16[REG_AX].toInt() and 0xFFFF)
    push(regs16[REG_CX].toInt() and 0xFFFF)
    push(regs16[REG_DX].toInt() and 0xFFFF)
    push(regs16[REG_BX].toInt() and 0xFFFF)
    push(sp)
    push(regs16[REG_BP].toInt() and 0xFFFF)
    push(regs16[REG_SI].toInt() and 0xFFFF)
    push(regs16[REG_DI].toInt() and 0xFFFF)
}

internal fun Emulator8086.executeXlat50() {
    // POPA (0x61)
    iW = true
    val sp = regs16[REG_SP].toInt() and 0xFFFF
    for (i in 0 until 8) {
        if (wordOffsetFaults((sp + 2 * i) and 0xFFFF)) {
            raiseException(13)
            return
        }
    }
    val di = pop(); if (pendingException >= 0) return
    val si = pop(); if (pendingException >= 0) return
    val bp = pop(); if (pendingException >= 0) return
    pop(); if (pendingException >= 0) return // discard saved SP
    val bx = pop(); if (pendingException >= 0) return
    val dx = pop(); if (pendingException >= 0) return
    val cx = pop(); if (pendingException >= 0) return
    val ax = pop(); if (pendingException >= 0) return
    regs16[REG_DI] = di.toShort()
    regs16[REG_SI] = si.toShort()
    regs16[REG_BP] = bp.toShort()
    regs16[REG_BX] = bx.toShort()
    regs16[REG_DX] = dx.toShort()
    regs16[REG_CX] = cx.toShort()
    regs16[REG_AX] = ax.toShort()
}

internal fun Emulator8086.executeXlat51() {
    // BOUND r16, m32 (0x62)
    iW = true
    iD = false
    decodeRmReg()
    if (iMod == 3) {
        raiseException(6) // #UD — register form is invalid
        return
    }
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

internal fun Emulator8086.executeXlat52() {
    // PUSH imm16 (0x68) | PUSH imm8 (0x6A) | IMUL r,r/m,imm (0x69/0x6B)
    when (rawOpcodeId) {
        0x68 -> { // PUSH imm16
            iW = true
            push(iData0 and 0xFFFF)
        }
        0x6A -> { // PUSH imm8 (sign-extended)
            iW = true
            push(iData0.toByte().toInt() and 0xFFFF)
        }
        0x69, 0x6B -> { // IMUL r16, r/m16, imm16/imm8
            iW = true
            iD = false
            decodeRmReg()
            val src = readOperand(rmAddr).toShort().toInt()
            if (pendingException >= 0) return
            val immOffset = 2 + iMod * (if (iMod != 3) 1 else 0) +
                2 * (if (iMod == 0 && iRm == 6) 1 else 0)
            val imm = if (rawOpcodeId == 0x6B) {
                guestRead8(segreg(REG_CS, REG_ZERO, (regIp + immOffset) and 0xFFFF))
                    .toByte().toInt()
            } else {
                val lo = guestRead8(segreg(REG_CS, REG_ZERO, (regIp + immOffset) and 0xFFFF))
                val hi = guestRead8(segreg(REG_CS, REG_ZERO, (regIp + immOffset + 1) and 0xFFFF))
                (lo or (hi shl 8)).toShort().toInt()
            }
            val product = src.toLong() * imm.toLong()
            val truncated = product.toInt() and 0xFFFF
            regs16[iReg] = truncated.toShort()
            opResult = truncated
            val overflows = product != truncated.toShort().toLong()
            setCF(overflows)
            setOF(overflows)
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
    fun rollbackEnter(): Boolean {
        if (pendingException < 0) return false
        regs16[REG_SP] = savedSp.toShort()
        regs16[REG_BP] = savedBp.toShort()
        return true
    }
    push(regs16[REG_BP].toInt() and 0xFFFF)
    if (rollbackEnter()) return
    val frameTemp = regs16[REG_SP].toInt() and 0xFFFF
    if (nesting > 0) {
        var level = 1
        while (level < nesting) {
            regs16[REG_BP] = ((regs16[REG_BP].toInt() and 0xFFFF) - 2).toShort()
            val link = readSegmentWord(REG_SS, regs16[REG_BP].toInt() and 0xFFFF)
            if (rollbackEnter()) return
            push(link)
            if (rollbackEnter()) return
            level++
        }
        push(frameTemp)
        if (rollbackEnter()) return
    }
    regs16[REG_BP] = frameTemp.toShort()
    regs16[REG_SP] = (
        (regs16[REG_SP].toInt() and 0xFFFF) - frameSize
        ).toShort()
}

internal fun Emulator8086.executeXlat55() {
    // LEAVE (0xC9)
    iW = true
    val bp = regs16[REG_BP].toInt() and 0xFFFF
    // Fault before SP←BP so a wrap at BP=FFFFh keeps the original SP.
    if (wordOffsetFaults(bp)) {
        raiseException(13)
        return
    }
    regs16[REG_SP] = bp.toShort()
    val newBp = pop()
    if (pendingException >= 0) return
    regs16[REG_BP] = newBp.toShort()
}

internal fun Emulator8086.executeXlat56() {
    // 286 0x0F escape
    execute0FEscape()
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
