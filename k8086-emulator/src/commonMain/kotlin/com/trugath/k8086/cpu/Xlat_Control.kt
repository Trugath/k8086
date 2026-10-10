package com.trugath.k8086.cpu

/**
 * Jcc / LOOP / JMP / CALL / RET / INT / flag-set xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

// --- xlat opcode group: control ---

internal fun Emulator8086.executeXlat0() {
    // Conditional jump (JO/JNO … JL/JGE …)
    // i_w is the invert flag (odd opcodes are the "not" form).
    val cond = (rawOpcodeId shr 1) and 7
    val taken = iW xor jccTaken(cond)
    if (taken) {
        flushInstructionPrefetch()
        regIp += iData0.toByte().toInt()
    }
    // 8088: ~4 not taken / ~16 taken (flat table used 16 for both).
    cycleOverride = if (has386Prefixes) {
        // 7+m taken / 3 not taken; use a coarse three-clock refill allowance.
        if (taken) 10 else 3
    } else if (taken) 16 else 4
}

internal fun Emulator8086.executeXlat13(iReg4bit: Int) {
    if (addressSizeOverrideEn != 0) {
        val count = if (iReg4bit == 3) reg32u(REG_CX) else reg32u(REG_CX) - 1
        if (iReg4bit != 3) setReg32(REG_CX, count)
        val taken = when (iReg4bit) {
            0 -> count != 0 && regs8[FLAG_ZF].toInt() == 0
            1 -> count != 0 && regs8[FLAG_ZF].toInt() != 0
            3 -> count == 0
            else -> count != 0
        }
        if (taken) {
            flushInstructionPrefetch()
            regIp += iData0.toByte().toInt()
        }
        return
    }
    // LOOPxx|JCZX - OPCODE 13
    // Matches `scratch_uint = !!--regs16[REG_CX]`:
    // CX is decremented FIRST, and the loop-continues check reads the
    // POST-decrement value, not the pre-decrement value.
    regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
    var scratchUint = if (regs16[REG_CX].toInt() and 0xFFFF != 0) 1 else 0

    when (iReg4bit) {
        0 -> { // LOOPNZ
            scratchUint = scratchUint and (if (regs8[FLAG_ZF].toInt() == 0) 1 else 0)
        }
        1 -> { // LOOPZ
            scratchUint = scratchUint and regs8[FLAG_ZF].toInt()
        }
        3 -> { // JCXXZ
            regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) + 1).toShort()
            scratchUint = if (regs16[REG_CX].toInt() and 0xFFFF == 0) 1 else 0
        }
    }
    if (scratchUint != 0) {
        flushInstructionPrefetch()
        regIp += iData0.toByte().toInt()
    }
}

internal fun Emulator8086.executeXlat14() {
    // JMP | CALL short/near/far - OPCODE 14
    // 66h near CALL/JMP use rel32 (and CALL pushes a 32-bit return EIP).
    val shortJmp = iD && iW
    if (!shortJmp && !iD && operandSizeOverrideEn != 0) {
        val lo = iData0 and 0xFFFF
        val hi = instructionRead8((regIp + 3) and 0xFFFF) or
            (instructionRead8((regIp + 4) and 0xFFFF) shl 8)
        val rel = lo or (hi shl 16)
        regIp = (regIp + 5) and 0xFFFF
        if (!iW) { // CALL near
            push(regIp)
            if (pendingException >= 0) return
        }
        regIp = (regIp + rel) and 0xFFFF
        flushInstructionPrefetch()
        return
    }
    // 66h JMP FAR ptr16:32 (EA)
    if (!iW && iD && operandSizeOverrideEn != 0) {
        val b0 = instructionRead8((regIp + 1) and 0xFFFF)
        val b1 = instructionRead8((regIp + 2) and 0xFFFF)
        val b2 = instructionRead8((regIp + 3) and 0xFFFF)
        val b3 = instructionRead8((regIp + 4) and 0xFFFF)
        val offset = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        val newCs = instructionRead8((regIp + 5) and 0xFFFF) or
            (instructionRead8((regIp + 6) and 0xFFFF) shl 8)
        regs16[REG_CS] = newCs.toShort()
        regIp = offset and 0xFFFF
        flushInstructionPrefetch()
        return
    }
    regIp += 3 - (if (iD) 1 else 0)
    if (!iW) {
        if (iD) { // JMP far
            regIp = 0
            regs16[REG_CS] = iData2.toShort()
        } else { // CALL
            push(regIp)
        }
    }
    regIp += if (iD && iW) iData0.toByte().toInt() else iData0
    // A 16-bit near branch truncates its target before checking the CS limit.
    regIp = regIp and 0xFFFF
    flushInstructionPrefetch()
}

internal fun Emulator8086.executeXlat19() {
    // RET|RETF|IRET - OPCODE 19
    //
    // With 66h (32-bit operand size) in real/VM86 mode:
    //   RETF/IRET pop a 32-bit EIP, then a dword slot containing a 16-bit CS.
    //   IRET then pops a 32-bit EFLAGS. Near RET pops only EIP.
    iD = iW
    val opsize32 = operandSizeOverrideEn != 0
    val oldSp = regs16[REG_SP]
    val targetIp = pop()
    if (pendingException >= 0) return
    if (opsize32 && (targetIp ushr 16) != 0) {
        // A return must validate the target against the real-mode CS limit
        // before committing the stack pop or the immediate stack adjustment.
        regs16[REG_SP] = oldSp
        raiseException(13)
        return
    }
    var targetCs = regs16[REG_CS].toInt() and 0xFFFF
    if (extra != 0) { // IRET|RETF|RETF imm16
        // RETF/IRET read a full CS slot, unlike POP sreg's selector-only read.
        targetCs = (if (opsize32) pop() else popSelector()) and 0xFFFF
        if (pendingException >= 0) {
            regs16[REG_SP] = oldSp
            return
        }
    }
    if ((extra and 2) != 0) { // IRET
        val flags = pop()
        if (pendingException >= 0) {
            regs16[REG_SP] = oldSp
            return
        }
        setFlags(flags and popfValueMask())
    } else if (!iD) { // RET|RETF imm16
        regs16[REG_SP] = ((regs16[REG_SP].toInt() and 0xFFFF) + iData0).toShort()
    }
    regIp = targetIp and 0xFFFF
    regs16[REG_CS] = targetCs.toShort()
    flushInstructionPrefetch()
}

internal fun Emulator8086.executeXlat32() {
    // CALL FAR ptr16:16 / ptr16:32 (66h) - OPCODE 32
    val opsize32 = operandSizeOverrideEn != 0
    val offset: Int
    val newCs: Int
    val insnLen: Int
    if (opsize32) {
        // 9A offset32 seg16
        val b0 = instructionRead8((regIp + 1) and 0xFFFF)
        val b1 = instructionRead8((regIp + 2) and 0xFFFF)
        val b2 = instructionRead8((regIp + 3) and 0xFFFF)
        val b3 = instructionRead8((regIp + 4) and 0xFFFF)
        offset = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        newCs = instructionRead8((regIp + 5) and 0xFFFF) or
            (instructionRead8((regIp + 6) and 0xFFFF) shl 8)
        insnLen = 7
    } else {
        offset = iData0 and 0xFFFF
        newCs = iData2 and 0xFFFF
        insnLen = 5
    }
    push(regs16[REG_CS].toInt() and 0xFFFF)
    if (pendingException >= 0) return
    push(regIp + insnLen)
    if (pendingException >= 0) return
    regs16[REG_CS] = newCs.toShort()
    regIp = offset and 0xFFFF
    flushInstructionPrefetch()
}

internal fun Emulator8086.executeXlat38() {
    // INT 3 - OPCODE 38
    regIp++
    pcInterrupt(3)
}

internal fun Emulator8086.executeXlat39() {
    // INT imm8 - OPCODE 39
    regIp += 2
    val intNum = iData0 and 0xFF
    // DOS terminate: INT 20h, or INT 21h with AH=00h (terminate) / AH=4Ch (exit).
    if (intNum == 0x20 ||
        (intNum == 0x21 && (regs8[REG_AH].toInt() and 0xFF).let { it == 0x00 || it == 0x4C })
    ) {
        hostServices.onDosTerminate?.invoke()
    }
    // INT 13h host shims: HD (DL bit 7) or floppy (DL < 0x80).
    if (intNum == 0x13) {
        val dl = regs8[REG_DL].toInt() and 0xFF
        if ((dl and 0x80) != 0) {
            if (hostServices.onInt13HardDisk?.invoke() == true) {
                return
            }
        } else if (hostServices.onInt13Floppy?.invoke() == true) {
            return
        }
    }
    pcInterrupt(intNum)
}

internal fun Emulator8086.executeXlat40() {
    // INTO - OPCODE 40
    regIp++
    if (regs8[FLAG_OF].toInt() != 0) {
        pcInterrupt(4)
    }
}

internal fun Emulator8086.executeXlat46() {
    // CLC|STC|CLI|STI|CLD|STD - OPCODE 46
    val flagIndex = extra / 2
    val flagValue = (extra and 1).toByte()
    regs8[flagIndex] = flagValue
}
