package com.trugath.k8086.cpu.fpu

/**
 * Decoded 8087 operation, analogous to Emulator8086 xlat ids.
 * Operand details (memory address, ST index, pop) live alongside in [EscDecoded].
 */
internal enum class FpuOp {
    // Arithmetic
    FADD, FMUL, FCOM, FCOMP, FSUB, FSUBR, FDIV, FDIVR,
    FADDP, FMULP, FCOMPP, FSUBP, FSUBRP, FDIVP, FDIVRP,
    FUCOM, FUCOMP, FUCOMPP,

    // Load / store
    FLD_M32, FST_M32, FSTP_M32,
    FLD_M64, FST_M64, FSTP_M64,
    FLD_M80, FSTP_M80,
    FILD_M16, FIST_M16, FISTP_M16,
    FILD_M32, FIST_M32, FISTP_M32,
    FILD_M64, FISTP_M64,
    FBLD, FBSTP,
    FLD_ST, FST_ST, FSTP_ST, FXCH, FFREE,

    // Integer memory arithmetic (same ops as real, different load)
    FIADD, FIMUL, FICOM, FICOMP, FISUB, FISUBR, FIDIV, FIDIVR,

    // Control / env
    FLDENV, FLDCW, FSTENV, FSTCW,
    FRSTOR, FSAVE, FSTSW, FSTSW_AX,
    FENI, FDISI, FCLEX, FINIT,
    FNOP, FDECSTP, FINCSTP,

    // Unary / constants / transcendental
    FCHS, FABS, FTST, FXAM,
    FLD1, FLDL2T, FLDL2E, FLDPI, FLDLG2, FLDLN2, FLDZ,
    F2XM1, FYL2X, FPTAN, FPATAN, FXTRACT, FPREM1,
    FPREM, FYL2XP1, FSQRT, FSINCOS, FRNDINT, FSCALE, FSIN, FCOS,

    UNIMPLEMENTED,
}

internal data class EscDecoded(
    val op: FpuOp,
    /** ST(i) or r/m field when relevant. */
    val index: Int = 0,
    /** True when destination is ST(0); false for DC/DE ST(i) destination forms. */
    val destIsSt0: Boolean = true,
    /** Memory operand size hint for integer/real loads. */
    val memBytes: Int = 0,
)
