package com.trugath.k8086.cpu

/**
 * String ops and IN/OUT / INS/OUTS xlat handlers.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 *
 * REP loops yield every [REP_ITER_QUANTUM] iterations so Machine can advance PIT
 * and deliver IRQ0 between quanta. Continuation uses [CpuState.repContinue] —
 * never rewinds to a bare REP prefix (that would leave [Emulator8086.prefixActive]
 * set and drop the IRQ latch).
 */

// --- xlat opcode group: stringIo ---

/** Count completed iterations, including the final partial REP quantum. */
private fun Emulator8086.account386RepCycles(repeated: Boolean, iterations: Int) {
    if (!has386Prefixes || !repeated) return
    val per = when (rawOpcodeId and 0xFF) {
        0xA4, 0xA5 -> 4
        0xA6, 0xA7 -> 9
        0xAA, 0xAB -> 5
        0xAC, 0xAD -> 5
        0xAE, 0xAF -> 8
        0x6C, 0x6D -> 6
        0x6E, 0x6F -> 12
        else -> return
    }
    // The separately executed REP prefix already costs two clocks. Charge
    // the remaining setup once, rather than on every interruptible quantum.
    val setup = if (repTimingContinuation) 0 else if (rawOpcodeId in 0x6C..0x6D) 11 else 3
    cycleOverride = (setup + per * iterations).coerceAtLeast(1)
}

/** Arm mid-REP resume; decoder suppresses IP advance and clears prefix holds. */
internal fun Emulator8086.armRepContinue(itersDone: Int) {
    repContinue = true
    repContinueRawOpcode = rawOpcodeId
    repContinueXlat = xlatOpcodeId
    repContinueExtra = extra
    repContinueIW = iW
    repContinueOperandSize = operandSizeOverrideEn != 0
    repContinueRepMode = repMode
    repContinueSegActive = segOverrideEn != 0
    repContinueSeg = segOverride
    repContinueCs = regs16[REG_CS].toInt() and 0xFFFF
    repContinueIp = regIp and 0xFFFF
    suppressIpAdvance = true
    val per = CycleTables.BASE[rawOpcodeId and 0xFF].coerceAtLeast(1)
    cycleOverride = per * itersDone.coerceAtLeast(1)
}

/** True when a word/dword string access at [offset] should #GP/#SS. */
private fun Emulator8086.stringOffsetFaults(offset: Int): Boolean =
    if (operandSize32()) dwordOffsetFaults(offset) else iW && wordOffsetFaults(offset)

/** Yield only when IF=1 so CLI regions (e.g. HD INT 13h) stay atomic. */
internal fun Emulator8086.shouldYieldRep(itersDone: Int): Boolean {
    if (itersDone < REP_ITER_QUANTUM) return false
    if (repCount() == 0) return false
    return interruptsEnabled()
}

internal fun Emulator8086.executeXlat17() {
    // MOVSx (extra=0)|STOSx (extra=1)|LODSx (extra=2) - OPCODE 17
    // dest is ES:DI unless this is LODS (extra=2, dest=accumulator);
    // src is the accumulator for STOS (extra=1), else DS(or override):SI.
    // 66h → dword width; 67h → ESI/EDI/ECX and #GP if index[31:16] ≠ 0.
    val scratch2Uint = if (segOverrideEn != 0) segOverride else REG_DS
    val rep = repOverrideEn != 0
    val sourceIsAccumulator = extra and 1 != 0
    val destinationIsAccumulator = extra >= 2
    val width = operandBytes()
    var iters = 0

    while (true) {
        if (rep && repCount() == 0) break
        if (rep) decRepCount()

        val si = stringIndex(REG_SI)
        val di = stringIndex(REG_DI)
        val siOff = si and 0xFFFF
        val diOff = di and 0xFFFF

        // 67h: index bits 31:16 set → #GP/#SS before any transfer (6766A5.MOO).
        if (!sourceIsAccumulator && stringIndexHighFaults(si)) {
            raiseException(exceptionForWordAccess(scratch2Uint))
            if (rep && !stringWrapFaultCommitsSideEffects()) incRepCount()
            break
        }

        // Word/dword MOVS/LODS wrapping past FFFFh → #GP/#SS (SS: override).
        // 286: advance SI (and keep the REP CX decrement). 386EX: leave SI/CX.
        if (!sourceIsAccumulator && width >= 2 && stringOffsetFaults(siOff)) {
            raiseException(exceptionForWordAccess(scratch2Uint))
            if (stringWrapFaultCommitsSideEffects()) {
                indexInc(REG_SI)
            } else if (rep) {
                incRepCount()
            }
            break
        }

        val value = when (width) {
            4 -> if (sourceIsAccumulator) getReg32(REG_AX) else readSegmentDword(scratch2Uint, siOff)
            2 -> if (sourceIsAccumulator) guestRead16(REGS_BASE) else readSegmentWord(scratch2Uint, siOff)
            else ->
                if (sourceIsAccumulator) guestRead8(REGS_BASE)
                else guestRead8(segreg(scratch2Uint, REG_ZERO, siOff))
        }
        if (pendingException >= 0) break

        if (!destinationIsAccumulator && stringIndexHighFaults(di)) {
            raiseException(exceptionForWordAccess(REG_ES))
            if (rep && !stringWrapFaultCommitsSideEffects()) incRepCount()
            break
        }

        // Word/dword MOVS/STOS wrapping past FFFFh → #GP (ES:DI).
        // 286: advance SI+DI and an extra CX tick; 386EX: leave pointers/CX.
        if (!destinationIsAccumulator && width >= 2 && stringOffsetFaults(diOff)) {
            raiseException(exceptionForWordAccess(REG_ES))
            if (stringWrapFaultCommitsSideEffects()) {
                if (!sourceIsAccumulator) indexInc(REG_SI)
                indexInc(REG_DI)
                if (rep) decRepCount()
            } else if (rep) {
                incRepCount()
            }
            break
        }

        val dstAddr =
            if (destinationIsAccumulator) REGS_BASE
            else segreg(REG_ES, REG_ZERO, diOff)
        if (!isRom(dstAddr)) {
            when (width) {
                4 ->
                    if (destinationIsAccumulator) setReg32(REG_AX, value)
                    else writeSegmentDword(REG_ES, diOff, value)
                2 ->
                    if (destinationIsAccumulator) guestWrite16(dstAddr, value)
                    else writeSegmentWord(REG_ES, diOff, value)
                else -> guestWrite8(dstAddr, value)
            }
        }
        if (pendingException >= 0) break

        if (!sourceIsAccumulator) indexInc(REG_SI)
        if (!destinationIsAccumulator) indexInc(REG_DI)
        iters++
        if (!rep) break
        if (shouldYieldRep(iters)) {
            armRepContinue(iters)
            break
        }
    }
    account386RepCycles(rep, iters)
}

internal fun Emulator8086.executeXlat18() {
    // CMPSx (extra=0)|SCASx (extra=1) - OPCODE 18
    val scratch2Uint = if (segOverrideEn != 0) segOverride else REG_DS
    val rep = repOverrideEn != 0
    var iters = 0

    while (true) {
        if (rep && repCount() == 0) break

        val si = stringIndex(REG_SI)
        val di = stringIndex(REG_DI)
        val siOff = si and 0xFFFF
        val diOff = di and 0xFFFF

        // 67h: high half of EDI/ESI set → fault before compare.
        if (stringIndexHighFaults(di)) {
            raiseException(exceptionForWordAccess(REG_ES))
            break
        }
        if (extra == 0 && stringIndexHighFaults(si)) {
            raiseException(exceptionForWordAccess(scratch2Uint))
            break
        }

        // Check DI before SI. 286 advances pointers (and SCAS/CX quirks);
        // 386EX leaves DI/SI/CX unchanged on the wrap fault.
        if (iW && stringOffsetFaults(diOff)) {
            raiseException(exceptionForWordAccess(REG_ES))
            if (stringWrapFaultCommitsSideEffects()) {
                indexInc(REG_DI)
                if (rep && extra != 0) decRepCount()
            }
            break
        }
        // Word/dword at SI wrapping (CMPS): 286 advances both + CX; 386EX stays put.
        // SS: override on the source → #SS (36 A7 / 66A7).
        if (extra == 0 && iW && stringOffsetFaults(siOff)) {
            raiseException(exceptionForWordAccess(scratch2Uint))
            if (stringWrapFaultCommitsSideEffects()) {
                if (rep) decRepCount()
                indexInc(REG_SI)
                indexInc(REG_DI)
            }
            break
        }

        if (rep) decRepCount()

        val srcAddr =
            if (extra != 0) REGS_BASE else segreg(scratch2Uint, REG_ZERO, siOff)
        val dstAddr = segreg(REG_ES, REG_ZERO, diOff)
        memOp(srcAddr, ALU_SUB, dstAddr, writeBack = false)
        if (pendingException >= 0) break
        // Update flags after each successful compare so a later #GP keeps them.
        // Unsigned borrow — signed `>` is wrong for 32-bit results (66A7.MOO).
        val widthMask = cfWidthMask()
        setCF(unsignedGreater(opResult and widthMask, opDest and widthMask))
        regs8[FLAG_SF] = signOf(opResult).toByte()
        regs8[FLAG_ZF] = if ((opResult and widthMask) == 0) 1 else 0
        regs8[FLAG_PF] = parityFlag(opResult).toByte()
        setAFOfArith()
        setFlagsType = 0

        if (extra == 0) indexInc(REG_SI)
        indexInc(REG_DI)
        iters++

        if (rep) {
            val keepGoing = (regs16[REG_CX].toInt() and 0xFFFF) != 0 &&
                (opResult == 0) == (repMode != 0)
            if (!keepGoing) break
            if (shouldYieldRep(iters)) {
                armRepContinue(iters)
                break
            }
        } else {
            break
        }
    }
    account386RepCycles(rep, iters)
}

internal fun Emulator8086.executeXlat21() {
    // IN AL/AX, DX/imm8 - OPCODE 21
    val port = if (extra != 0) regs16[REG_DX].toInt() and 0xFFFF else iData0 and 0xFF
    val value = when (operandBytes()) {
        4 -> ioReadPort16(port) or (ioReadPort16((port + 2) and 0xFFFF) shl 16)
        2 -> ioReadPort16(port)
        else -> ioReadPort8(port)
    }
    if (operandSize32()) {
        setReg32(REG_AX, value)
        opResult = value
    } else if (iW) {
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
    when (operandBytes()) {
        4 -> {
            val value = getReg32(REG_AX)
            ioWritePort16(port, value and 0xFFFF)
            ioWritePort16((port + 2) and 0xFFFF, value ushr 16)
        }
        2 -> ioWritePort16(port, regs16[REG_AX].toInt() and 0xFFFF)
        else -> ioWritePort8(port, regs8[REG_AL].toInt() and 0xFF)
    }
}

internal fun Emulator8086.executeXlat57() {
    // INS / OUTS (0x6C-0x6F)
    executeInsOuts()
}

internal fun Emulator8086.executeInsOuts() {
    // 6C INSB, 6D INSW/INSD, 6E OUTSB, 6F OUTSW/OUTSD
    iW = (rawOpcodeId and 1) != 0
    val isIns = rawOpcodeId < 0x6E
    val port = regs16[REG_DX].toInt() and 0xFFFF
    val srcSeg = if (segOverrideEn != 0) segOverride else REG_DS
    val rep = repOverrideEn != 0
    val width = operandBytes()
    var iters = 0
    while (true) {
        if (rep && repCount() == 0) break
        val indexReg = if (isIns) REG_DI else REG_SI
        val index = stringIndex(indexReg)
        val offset = index and 0xFFFF
        val faultSeg = if (isIns) REG_ES else srcSeg
        if (stringIndexHighFaults(index)) {
            raiseException(exceptionForWordAccess(faultSeg))
            break
        }
        if (width >= 2 && stringOffsetFaults(offset)) {
            // 286/386: word/dword wrapping past FFFFh. SS: override → #SS
            // (666F.MOO); ES/DS → #GP. Pointer and CX stay put on 386EX.
            raiseException(exceptionForWordAccess(faultSeg))
            if (stringWrapFaultCommitsSideEffects()) {
                indexInc(indexReg)
                if (rep) repeat(if (isIns) 2 else 1) { decRepCount() }
            }
            break
        }
        if (rep) decRepCount()
        if (isIns) {
            when (width) {
                4 -> writeSegmentDword(
                    REG_ES,
                    offset,
                    ioReadPort16(port) or (ioReadPort16(port + 2) shl 16),
                )
                2 -> writeSegmentWord(REG_ES, offset, ioReadPort16(port))
                else -> guestWrite8(segreg(REG_ES, REG_ZERO, offset), ioReadPort8(port))
            }
        } else {
            val value = when (width) {
                4 -> readSegmentDword(srcSeg, offset)
                2 -> readSegmentWord(srcSeg, offset)
                else -> guestRead8(segreg(srcSeg, REG_ZERO, offset))
            }
            if (pendingException >= 0) break
            when (width) {
                4 -> {
                    ioWritePort16(port, value and 0xFFFF)
                    ioWritePort16(port + 2, value ushr 16)
                }
                2 -> ioWritePort16(port, value)
                else -> ioWritePort8(port, value)
            }
        }
        if (pendingException >= 0) break
        indexInc(indexReg)
        iters++
        if (!rep) break
        // Yield REP INS/OUTS only with IF=1; CLI disk paths must not split.
        if (shouldYieldRep(iters)) {
            armRepContinue(iters)
            break
        }
    }
    account386RepCycles(rep, iters)
}
