package com.trugath.k8086.cpu

/**
 * Coarse 8088 cycle estimates keyed by raw opcode (not prefetch-accurate).
 * Average across the table is near real 8088 short-op cost. Machine advances
 * peripherals by [Emulator8086.lastInstructionCycles] with no artificial floor —
 * a flat floor that is a multiple of 4 phase-locks the XT BIOS DRAM-refresh POST
 * loop. Jcc uses 4 here as the not-taken default; taken branches override to 16.
 *
 * GRP3 (0xF6/0xF7) is special: TEST/NOT/NEG are cheap, but MUL/IMUL/DIV/IDIV
 * are 70–180+ cycles. CheckIt-style CPU MHz probes time a burst of MUL against
 * PIT channel 2, so a flat F7=70 for MUL r16 (~118–133 real) reports MHz high.
 */
internal object CycleTables {
    /** Base cycles for each primary opcode byte. */
    val BASE: IntArray = IntArray(256) { 8 }.also { t ->
        // Arithmetic / logic reg,r/m family 00–3F (even opcodes often shorter)
        for (op in 0x00..0x3F) t[op] = if ((op and 1) == 0) 3 else 9
        // INC/DEC reg16
        for (op in 0x40..0x4F) t[op] = 3
        // PUSH/POP reg
        for (op in 0x50..0x5F) t[op] = 11
        // Conditional jumps: not-taken default; taken overrides to 16 in the CPU.
        for (op in 0x70..0x7F) t[op] = 4
        // Imm ALU / TEST / XCHG / MOV
        for (op in 0x80..0x8F) t[op] = 10
        // NOP / XCHG AX,reg
        for (op in 0x90..0x97) t[op] = 3
        t[0x98] = 2; t[0x99] = 5 // CBW/CWD
        t[0x9A] = 28; t[0x9B] = 4 // CALL far / WAIT
        t[0x9C] = 10; t[0x9D] = 8 // PUSHF/POPF
        t[0x9E] = 4; t[0x9F] = 4 // SAHF/LAHF
        // MOV mem/imm, string, etc.
        for (op in 0xA0..0xA3) t[op] = 10
        for (op in 0xA4..0xA7) t[op] = 18 // MOVS/CMPS base
        for (op in 0xA8..0xA9) t[op] = 4
        for (op in 0xAA..0xAF) t[op] = 15 // STOS/LODS/SCAS
        for (op in 0xB0..0xBF) t[op] = 4 // MOV imm
        for (op in 0xC0..0xC1) t[op] = 2 // 186+ no-ops
        t[0xC2] = 16; t[0xC3] = 16 // RET
        t[0xC4] = 16; t[0xC5] = 16 // LES/LDS
        t[0xC6] = 10; t[0xC7] = 10
        t[0xC8] = 2; t[0xC9] = 2
        t[0xCA] = 18; t[0xCB] = 18 // RETF
        t[0xCC] = 52; t[0xCD] = 51; t[0xCE] = 4; t[0xCF] = 32
        for (op in 0xD0..0xD3) t[op] = 15 // shifts
        t[0xD4] = 83; t[0xD5] = 60 // AAM/AAD
        t[0xD6] = 3; t[0xD7] = 11 // SALC/XLAT
        for (op in 0xD8..0xDF) t[op] = 2 // ESC
        for (op in 0xE0..0xE3) t[op] = 18 // LOOP*
        t[0xE4] = 10; t[0xE5] = 10; t[0xE6] = 10; t[0xE7] = 10 // IN/OUT imm
        t[0xE8] = 19; t[0xE9] = 15; t[0xEA] = 15; t[0xEB] = 15
        t[0xEC] = 8; t[0xED] = 8; t[0xEE] = 8; t[0xEF] = 8
        t[0xF0] = 2; t[0xF1] = 2; t[0xF2] = 2; t[0xF3] = 2
        t[0xF4] = 2; t[0xF5] = 2
        // GRP3 fallback; prefer [grp3Cycles] once ModR/M.reg is known.
        t[0xF6] = 15; t[0xF7] = 70
        t[0xF8] = 2; t[0xF9] = 2; t[0xFA] = 2; t[0xFB] = 2
        t[0xFC] = 2; t[0xFD] = 2
        t[0xFE] = 3; t[0xFF] = 16
        // Segment overrides / prefixes
        for (op in listOf(0x26, 0x2E, 0x36, 0x3E)) t[op] = 2
    }

    /** Extra cycles when ModR/M addresses memory (iMod != 3). */
    const val MEMORY_OPERAND_PENALTY = 6

    /**
     * 8088-typical cycles for GRP3 (F6/F7) by ModR/M.reg.
     * Includes the memory operand cost when [memory] is true — callers should not
     * also add [MEMORY_OPERAND_PENALTY].
     */
    fun grp3Cycles(reg: Int, word: Boolean, memory: Boolean): Int {
        val mem = if (memory) MEMORY_OPERAND_PENALTY else 0
        return when (reg) {
            0, 1 -> (if (word) 11 else 9) + mem // TEST r/m, imm
            2 -> (if (memory) 16 else 3) // NOT
            3 -> (if (memory) 16 else 3) // NEG
            // MUL/IMUL/DIV/IDIV: published 8088 ranges; use a mid/typical value.
            4 -> (if (word) 124 else 74) + mem // MUL
            5 -> (if (word) 140 else 89) + mem // IMUL
            6 -> (if (word) 153 else 85) + mem // DIV
            7 -> (if (word) 174 else 106) + mem // IDIV
            else -> 15 + mem
        }
    }
}
