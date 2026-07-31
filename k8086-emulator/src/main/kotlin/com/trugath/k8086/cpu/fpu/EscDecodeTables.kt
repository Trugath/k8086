package com.trugath.k8086.cpu.fpu

/**
 * Table-driven ESC decode, mirroring [com.trugath.k8086.cpu.DecodeTables] for the 8086.
 *
 * Maps `(opcode, isReg, reg, rm)` → [EscDecoded]. Unknown combinations yield [FpuOp.UNIMPLEMENTED].
 * The lookup table is built once at class init; [decode] is a primitive index (no allocation).
 */
internal object EscDecodeTables {
    private val UNIMPLEMENTED = EscDecoded(FpuOp.UNIMPLEMENTED)

    /**
     * Index: `((opcode - 0xD8) shl 7) or (isReg shl 6) or (reg shl 3) or rm`
     * — 8 opcodes × 2 mod-forms × 8 reg × 8 rm = 1024 entries.
     */
    private val TABLE: Array<EscDecoded> = Array(1024) { UNIMPLEMENTED }.also { table ->
        for (opOff in 0..7) {
            val op = 0xD8 + opOff
            for (reg in 0..7) {
                for (rm in 0..7) {
                    table[index(opOff, isReg = false, reg, rm)] = decodeMemory(op, reg)
                    table[index(opOff, isReg = true, reg, rm)] = decodeRegister(op, reg, rm)
                }
            }
        }
    }

    fun decode(opcode: Int, iMod: Int, iReg: Int, iRm: Int): EscDecoded {
        val op = opcode and 0xFF
        if (op !in 0xD8..0xDF) return UNIMPLEMENTED
        return TABLE[index(op - 0xD8, isReg = iMod == 3, iReg and 7, iRm and 7)]
    }

    private fun index(opOff: Int, isReg: Boolean, reg: Int, rm: Int): Int =
        (opOff shl 7) or ((if (isReg) 1 else 0) shl 6) or ((reg and 7) shl 3) or (rm and 7)

    private fun decodeMemory(op: Int, reg: Int): EscDecoded = when (op) {
        0xD8 -> arithMem(reg, real = true, bytes = 4)
        0xD9 -> when (reg) {
            0 -> EscDecoded(FpuOp.FLD_M32, memBytes = 4)
            2 -> EscDecoded(FpuOp.FST_M32, memBytes = 4)
            3 -> EscDecoded(FpuOp.FSTP_M32, memBytes = 4)
            4 -> EscDecoded(FpuOp.FLDENV)
            5 -> EscDecoded(FpuOp.FLDCW)
            6 -> EscDecoded(FpuOp.FSTENV)
            7 -> EscDecoded(FpuOp.FSTCW)
            else -> UNIMPLEMENTED
        }
        0xDA -> arithMem(reg, real = false, bytes = 4)
        0xDB -> when (reg) {
            0 -> EscDecoded(FpuOp.FILD_M32, memBytes = 4)
            2 -> EscDecoded(FpuOp.FIST_M32, memBytes = 4)
            3 -> EscDecoded(FpuOp.FISTP_M32, memBytes = 4)
            5 -> EscDecoded(FpuOp.FLD_M80, memBytes = 10)
            7 -> EscDecoded(FpuOp.FSTP_M80, memBytes = 10)
            else -> UNIMPLEMENTED
        }
        0xDC -> arithMem(reg, real = true, bytes = 8)
        0xDD -> when (reg) {
            0 -> EscDecoded(FpuOp.FLD_M64, memBytes = 8)
            2 -> EscDecoded(FpuOp.FST_M64, memBytes = 8)
            3 -> EscDecoded(FpuOp.FSTP_M64, memBytes = 8)
            4 -> EscDecoded(FpuOp.FRSTOR)
            6 -> EscDecoded(FpuOp.FSAVE)
            7 -> EscDecoded(FpuOp.FSTSW)
            else -> UNIMPLEMENTED
        }
        0xDE -> arithMem(reg, real = false, bytes = 2)
        0xDF -> when (reg) {
            0 -> EscDecoded(FpuOp.FILD_M16, memBytes = 2)
            2 -> EscDecoded(FpuOp.FIST_M16, memBytes = 2)
            3 -> EscDecoded(FpuOp.FISTP_M16, memBytes = 2)
            4 -> EscDecoded(FpuOp.FBLD, memBytes = 10)
            5 -> EscDecoded(FpuOp.FILD_M64, memBytes = 8)
            6 -> EscDecoded(FpuOp.FBSTP, memBytes = 10)
            7 -> EscDecoded(FpuOp.FISTP_M64, memBytes = 8)
            else -> UNIMPLEMENTED
        }
        else -> UNIMPLEMENTED
    }

    private fun arithMem(reg: Int, real: Boolean, bytes: Int): EscDecoded {
        val op = if (real) {
            when (reg) {
                0 -> FpuOp.FADD
                1 -> FpuOp.FMUL
                2 -> FpuOp.FCOM
                3 -> FpuOp.FCOMP
                4 -> FpuOp.FSUB
                5 -> FpuOp.FSUBR
                6 -> FpuOp.FDIV
                7 -> FpuOp.FDIVR
                else -> FpuOp.UNIMPLEMENTED
            }
        } else {
            when (reg) {
                0 -> FpuOp.FIADD
                1 -> FpuOp.FIMUL
                2 -> FpuOp.FICOM
                3 -> FpuOp.FICOMP
                4 -> FpuOp.FISUB
                5 -> FpuOp.FISUBR
                6 -> FpuOp.FIDIV
                7 -> FpuOp.FIDIVR
                else -> FpuOp.UNIMPLEMENTED
            }
        }
        return EscDecoded(op, memBytes = bytes, destIsSt0 = true)
    }

    private fun decodeRegister(op: Int, reg: Int, rm: Int): EscDecoded = when (op) {
        0xD8 -> EscDecoded(
            when (reg) {
                0 -> FpuOp.FADD
                1 -> FpuOp.FMUL
                2 -> FpuOp.FCOM
                3 -> FpuOp.FCOMP
                4 -> FpuOp.FSUB
                5 -> FpuOp.FSUBR
                6 -> FpuOp.FDIV
                7 -> FpuOp.FDIVR
                else -> FpuOp.UNIMPLEMENTED
            },
            index = rm,
            destIsSt0 = true,
        )
        0xD9 -> when (reg) {
            0 -> EscDecoded(FpuOp.FLD_ST, index = rm)
            1 -> EscDecoded(FpuOp.FXCH, index = rm)
            2 -> if (rm == 0) EscDecoded(FpuOp.FNOP) else UNIMPLEMENTED
            4 -> EscDecoded(
                when (rm) {
                    0 -> FpuOp.FCHS
                    1 -> FpuOp.FABS
                    4 -> FpuOp.FTST
                    5 -> FpuOp.FXAM
                    else -> FpuOp.UNIMPLEMENTED
                },
            )
            5 -> EscDecoded(
                when (rm) {
                    0 -> FpuOp.FLD1
                    1 -> FpuOp.FLDL2T
                    2 -> FpuOp.FLDL2E
                    3 -> FpuOp.FLDPI
                    4 -> FpuOp.FLDLG2
                    5 -> FpuOp.FLDLN2
                    6 -> FpuOp.FLDZ
                    else -> FpuOp.UNIMPLEMENTED
                },
            )
            6 -> EscDecoded(
                when (rm) {
                    0 -> FpuOp.F2XM1
                    1 -> FpuOp.FYL2X
                    2 -> FpuOp.FPTAN
                    3 -> FpuOp.FPATAN
                    4 -> FpuOp.FXTRACT
                    5 -> FpuOp.FPREM1
                    6 -> FpuOp.FDECSTP
                    7 -> FpuOp.FINCSTP
                    else -> FpuOp.UNIMPLEMENTED
                },
            )
            7 -> EscDecoded(
                when (rm) {
                    0 -> FpuOp.FPREM
                    1 -> FpuOp.FYL2XP1
                    2 -> FpuOp.FSQRT
                    3 -> FpuOp.FSINCOS
                    4 -> FpuOp.FRNDINT
                    5 -> FpuOp.FSCALE
                    6 -> FpuOp.FSIN
                    7 -> FpuOp.FCOS
                    else -> FpuOp.UNIMPLEMENTED
                },
            )
            else -> UNIMPLEMENTED
        }
        0xDA -> if (reg == 5 && rm == 1) EscDecoded(FpuOp.FUCOMPP) else UNIMPLEMENTED
        0xDB -> when {
            reg == 4 && rm == 0 -> EscDecoded(FpuOp.FENI)
            reg == 4 && rm == 1 -> EscDecoded(FpuOp.FDISI)
            reg == 4 && rm == 2 -> EscDecoded(FpuOp.FCLEX)
            reg == 4 && rm == 3 -> EscDecoded(FpuOp.FINIT)
            else -> UNIMPLEMENTED
        }
        0xDC -> EscDecoded(
            when (reg) {
                0 -> FpuOp.FADD
                1 -> FpuOp.FMUL
                2 -> FpuOp.FCOM
                3 -> FpuOp.FCOMP
                4 -> FpuOp.FSUB
                5 -> FpuOp.FSUBR
                6 -> FpuOp.FDIV
                7 -> FpuOp.FDIVR
                else -> FpuOp.UNIMPLEMENTED
            },
            index = rm,
            destIsSt0 = false,
        )
        0xDD -> when (reg) {
            0 -> EscDecoded(FpuOp.FFREE, index = rm)
            2 -> EscDecoded(FpuOp.FST_ST, index = rm)
            3 -> EscDecoded(FpuOp.FSTP_ST, index = rm)
            4 -> EscDecoded(FpuOp.FUCOM, index = rm)
            5 -> EscDecoded(FpuOp.FUCOMP, index = rm)
            else -> UNIMPLEMENTED
        }
        0xDE -> when {
            reg == 3 && rm == 1 -> EscDecoded(FpuOp.FCOMPP)
            else -> EscDecoded(
                when (reg) {
                    0 -> FpuOp.FADDP
                    1 -> FpuOp.FMULP
                    4 -> FpuOp.FSUBP
                    5 -> FpuOp.FSUBRP
                    6 -> FpuOp.FDIVP
                    7 -> FpuOp.FDIVRP
                    else -> FpuOp.UNIMPLEMENTED
                },
                index = rm,
                destIsSt0 = false,
            )
        }
        0xDF -> if (reg == 4 && rm == 0) EscDecoded(FpuOp.FSTSW_AX) else UNIMPLEMENTED
        else -> UNIMPLEMENTED
    }
}
