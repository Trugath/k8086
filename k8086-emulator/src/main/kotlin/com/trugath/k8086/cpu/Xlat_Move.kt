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
    // MOV reg, imm - OPCODE 1
    iW = (rawOpcodeId and 8) != 0
    val regAddr = getRegAddr(iReg4bit)
    writeOperand(regAddr, iData0)
    opResult = iData0 and (if (iW) 0xFFFF else 0xFF)
}

internal fun Emulator8086.executeXlat3(iReg4bit: Int) {
    // PUSH regs16 - OPCODE 3
    val value = regs16[iReg4bit].toInt() and 0xFFFF
    push(if (iReg4bit == REG_SP) valuePushedForSp(value) else value)
}

internal fun Emulator8086.executeXlat4(iReg4bit: Int) {
    // POP regs16 - OPCODE 4
    val value = pop()
    if (pendingException >= 0) return
    regs16[iReg4bit] = value.toShort()
}

internal fun Emulator8086.executeXlat10() {
    // MOV sreg, r/m | POP r/m | LEA reg, r/m - OPCODE 10
    if (!iW) { // MOV
        iW = true
        if (invalidMovSregField(iReg, toSreg = iD)) {
            raiseException(6) // #UD
            return
        }
        // The 8086 decodes only two bits of the segment-register
        // field, so ModR/M reg values 4..7 alias ES..DS.
        iReg = (iReg and 3) + REG_ES
        decodeRmReg()
        memOp(opToAddr, ALU_MOV, opFromAddr)
        // iD: MOV sreg,r/m (write to sreg). 0x8C MOV r/m,sreg must not shadow.
        if (iD && iReg == REG_SS) armSsInterruptShadow()
    } else if (!iD) { // LEA
        if (iMod == 3 && leaRegisterFormRaisesUd()) {
            raiseException(6) // #UD — LEA with register form (286+)
            return
        }
        segOverrideEn = 1
        segOverride = REG_ZERO
        decodeRmReg()
        val regAddr = getRegAddr(iReg)
        writeOperand(regAddr, rmAddr)
        opResult = rmAddr and (if (iW) 0xFFFF else 0xFF)
    } else { // POP
        val value = pop()
        if (pendingException >= 0) return
        writeOperand(rmAddr, value)
    }
}

internal fun Emulator8086.executeXlat11() {
    // MOV AL/AX, [loc] - OPCODE 11
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
    // Destination is the r/m operand. Raw opcodes C6/C7 have i_d = 1, so
    // DECODE_RM_REG has already swapped op_from_addr to hold rm_addr (memory
    // for mod<3, or the r/m register for mod==3). The C reference writes to
    // mem[op_from_addr]; using getRegAddr(iReg) here wrongly targeted AL/AX.
    writeOperand(opFromAddr, iData2)
    opResult = iData2 and (if (iW) 0xFFFF else 0xFF)
}

internal fun Emulator8086.executeXlat25() {
    // PUSH reg - OPCODE 25
    push(regs16[extra].toInt() and 0xFFFF)
}

internal fun Emulator8086.executeXlat26() {
    // POP reg - OPCODE 26
    val value = pop()
    if (pendingException >= 0) return
    regs16[extra] = value.toShort()
    if (extra == REG_SS) armSsInterruptShadow()
}

internal fun Emulator8086.executeXlat33() {
    // PUSHF - OPCODE 33
    push(makeFlags())
}

internal fun Emulator8086.executeXlat34() {
    // POPF - OPCODE 34
    setFlags(pop() and popfValueMask())
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
    iW = true
    iD = true
    if (iMod == 3 && lesLdsRegisterFormRaisesUd()) {
        raiseException(6) // #UD
        return
    }
    decodeRmReg()
    if (rmIsMemory) {
        if (wordOffsetFaults(rmOffset and 0xFFFF)) {
            raiseException(exceptionForWordAccess(rmSegment))
            return
        }
        if (wordOffsetFaults((rmOffset + 2) and 0xFFFF)) {
            raiseException(exceptionForWordAccess(rmSegment))
            return
        }
    }
    memOp(opToAddr, ALU_MOV, opFromAddr)
    if (pendingException >= 0) return
    val segValue = guestRead8(rmAddress(2)) or (guestRead8(rmAddress(3)) shl 8)
    // MEM_OP(REGS_BASE + extra, =, rm_addr + 2):
    // for this opcode, the BIOS table's `extra` is already a byte offset into
    // the register file (e.g. REG_ES*2), not a plain register index - unlike
    // extra's usage elsewhere (regs16[extra]), so it must NOT be multiplied by 2.
    val segReg = REGS_BASE + extra
    writeWord(segReg, segValue)
}
