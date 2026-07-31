package com.trugath.k8086.cpu

/**
 * String ops and IN/OUT / INS/OUTS xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

// --- xlat opcode group: stringIo ---

internal fun Emulator8086.executeXlat17() {
    // MOVSx (extra=0)|STOSx (extra=1)|LODSx (extra=2) - OPCODE 17
    // dest is ES:DI unless this is LODS (extra=2, dest=accumulator);
    // src is the accumulator for STOS (extra=1), else DS(or override):SI.
    val scratch2Uint = if (segOverrideEn != 0) segOverride else REG_DS
    val rep = repOverrideEn != 0
    val sourceIsAccumulator = extra and 1 != 0
    val destinationIsAccumulator = extra >= 2

    while (true) {
        if (rep && (regs16[REG_CX].toInt() and 0xFFFF) == 0) break
        if (rep) {
            regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
        }

        val si = regs16[REG_SI].toInt() and 0xFFFF
        val di = regs16[REG_DI].toInt() and 0xFFFF

        // 286: word MOVS/LODS at SI=FFFFh — #GP, advance SI only.
        if (!sourceIsAccumulator && iW && wordOffsetFaults(si)) {
            raiseException(13)
            indexInc(REG_SI)
            break
        }

        val value = if (iW) {
            if (sourceIsAccumulator) guestRead16(REGS_BASE)
            else readSegmentWord(scratch2Uint, si)
        } else {
            if (sourceIsAccumulator) guestRead8(REGS_BASE)
            else guestRead8(segreg(scratch2Uint, REG_SI, 0))
        }
        if (pendingException >= 0) break

        // 286: word MOVS/STOS at DI=FFFFh — #GP after advancing SI and DI.
        if (!destinationIsAccumulator && iW && wordOffsetFaults(di)) {
            raiseException(13)
            if (!sourceIsAccumulator) indexInc(REG_SI)
            indexInc(REG_DI)
            if (rep) {
                regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
            }
            break
        }

        val dstAddr = if (destinationIsAccumulator) REGS_BASE else segreg(REG_ES, REG_DI, 0)
        if (!isRom(dstAddr)) {
            if (iW) {
                if (destinationIsAccumulator) guestWrite16(dstAddr, value)
                else writeSegmentWord(REG_ES, di, value)
            } else {
                guestWrite8(dstAddr, value)
            }
        }
        if (pendingException >= 0) break

        if (!sourceIsAccumulator) indexInc(REG_SI)
        if (!destinationIsAccumulator) indexInc(REG_DI)
        if (!rep) break
    }
}

internal fun Emulator8086.executeXlat18() {
    // CMPSx (extra=0)|SCASx (extra=1) - OPCODE 18
    val scratch2Uint = if (segOverrideEn != 0) segOverride else REG_DS
    val rep = repOverrideEn != 0
    var compared = false
    var faultedAfterCompare = false

    while (true) {
        if (rep && (regs16[REG_CX].toInt() and 0xFFFF) == 0) break

        val si = regs16[REG_SI].toInt() and 0xFFFF
        val di = regs16[REG_DI].toInt() and 0xFFFF

        // 286: check DI before SI. Word at DI=FFFFh advances DI only;
        // CMPS leaves CX unchanged, SCAS decrements once.
        if (iW && wordOffsetFaults(di)) {
            raiseException(13)
            indexInc(REG_DI)
            if (rep && extra != 0) {
                regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
            }
            if (compared) faultedAfterCompare = true
            break
        }
        // Word at SI=FFFFh (CMPS) advances both pointers; CX decrements once.
        if (extra == 0 && iW && wordOffsetFaults(si)) {
            if (rep) {
                regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
            }
            raiseException(13)
            indexInc(REG_SI)
            indexInc(REG_DI)
            if (compared) faultedAfterCompare = true
            break
        }

        if (rep) {
            regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
        }

        val srcAddr = if (extra != 0) REGS_BASE else segreg(scratch2Uint, REG_SI, 0)
        val dstAddr = segreg(REG_ES, REG_DI, 0)
        memOp(srcAddr, ALU_SUB, dstAddr, writeBack = false)
        if (pendingException >= 0) break
        compared = true
        // Update flags after each successful compare so a later #GP keeps them.
        setCF((opResult and cfWidthMask()) > opDest)
        val widthMask = if (iW) 0xFFFF else 0xFF
        regs8[FLAG_SF] = signOf(opResult).toByte()
        regs8[FLAG_ZF] = if ((opResult and widthMask) == 0) 1 else 0
        regs8[FLAG_PF] = parityFlag(opResult).toByte()
        setAFOfArith()
        setFlagsType = 0

        if (extra == 0) indexInc(REG_SI)
        indexInc(REG_DI)

        if (rep) {
            if (!(regs16[REG_CX].toInt() and 0xFFFF != 0 &&
                    (opResult == 0) == (repMode != 0))
            ) {
                break
            }
        } else {
            break
        }
    }

    // 286: trailing word-wrap #GP after compares clears CF/AF (setters no-op while pending).
    if (faultedAfterCompare) {
        regs8[FLAG_CF] = 0
        regs8[FLAG_AF] = 0
    }
}

internal fun Emulator8086.executeXlat21() {
    // IN AL/AX, DX/imm8 - OPCODE 21
    val port = if (extra != 0) regs16[REG_DX].toInt() and 0xFFFF else iData0 and 0xFF
    val value = if (iW) ioReadPort16(port) else ioReadPort8(port)
    if (iW) {
        regs16[REG_AX] = value.toShort()
        opResult = value
    } else {
        regs8[REG_AL] = (value and 0xFF).toByte()
        opResult = value and 0xFF
    }
}

internal fun Emulator8086.executeXlat22() {
    // OUT DX/imm8, AL/AX - OPCODE 22
    val port = if (extra != 0) regs16[REG_DX].toInt() and 0xFFFF else iData0 and 0xFF
    if (iW) ioWritePort16(port, regs16[REG_AX].toInt() and 0xFFFF)
    else ioWritePort8(port, regs8[REG_AL].toInt() and 0xFF)
}

internal fun Emulator8086.executeXlat57() {
    // INS / OUTS (0x6C-0x6F)
    executeInsOuts()
}

internal fun Emulator8086.executeInsOuts() {
    // 6C INSB, 6D INSW, 6E OUTSB, 6F OUTSW
    iW = (rawOpcodeId and 1) != 0
    val isIns = rawOpcodeId < 0x6E
    val port = regs16[REG_DX].toInt() and 0xFFFF
    val srcSeg = if (segOverrideEn != 0) segOverride else REG_DS
    val rep = repOverrideEn != 0
    while (true) {
        if (rep && (regs16[REG_CX].toInt() and 0xFFFF) == 0) break
        if (rep) {
            regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
        }
        val indexReg = if (isIns) REG_DI else REG_SI
        val offset = regs16[indexReg].toInt() and 0xFFFF
        if (iW && wordOffsetFaults(offset)) {
            // 286: word at FFFFh raises #GP; string pointer still advances.
            // REP INS decrements CX an extra time; REP OUTS does not.
            if (rep && isIns) {
                regs16[REG_CX] = ((regs16[REG_CX].toInt() and 0xFFFF) - 1).toShort()
            }
            raiseException(13)
            indexInc(indexReg)
            break
        }
        if (isIns) {
            val value = if (iW) ioReadPort16(port) else ioReadPort8(port)
            if (iW) {
                writeSegmentWord(REG_ES, offset, value)
            } else {
                guestWrite8(segreg(REG_ES, REG_DI, 0), value)
            }
        } else {
            val value = if (iW) {
                readSegmentWord(srcSeg, offset)
            } else {
                guestRead8(segreg(srcSeg, REG_SI, 0))
            }
            if (pendingException >= 0) break
            if (iW) ioWritePort16(port, value) else ioWritePort8(port, value)
        }
        if (pendingException >= 0) break
        indexInc(indexReg)
        if (!rep) break
    }
}
