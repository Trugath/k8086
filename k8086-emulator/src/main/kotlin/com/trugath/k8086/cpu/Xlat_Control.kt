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
        regIp += iData0.toByte().toInt()
    }
    // 8088: ~4 not taken / ~16 taken (flat table used 16 for both).
    cycleOverride = if (taken) 16 else 4
}

internal fun Emulator8086.executeXlat13(iReg4bit: Int) {
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
        regIp += iData0.toByte().toInt()
    }
}

internal fun Emulator8086.executeXlat14() {
    // JMP | CALL short/near - OPCODE 14
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
}

internal fun Emulator8086.executeXlat19() {
    // RET|RETF|IRET - OPCODE 19
    iD = iW
    regIp = pop()
    if (pendingException >= 0) return
    if (extra != 0) { // IRET|RETF|RETF imm16
        regs16[REG_CS] = pop().toShort()
        if (pendingException >= 0) return
    }
    if ((extra and 2) != 0) { // IRET
        val flags = pop()
        if (pendingException >= 0) return
        setFlags(flags and popfValueMask())
    } else if (!iD) { // RET|RETF imm16
        regs16[REG_SP] = ((regs16[REG_SP].toInt() and 0xFFFF) + iData0).toShort()
    }
}

internal fun Emulator8086.executeXlat32() {
    // CALL FAR imm16:imm16 - OPCODE 32
    push(regs16[REG_CS].toInt() and 0xFFFF)
    push(regIp + 5)
    regs16[REG_CS] = iData2.toShort()
    regIp = iData0
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
