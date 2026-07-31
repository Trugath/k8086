package com.trugath.k8086.cpu

/**
 * Allocation-free instruction fetch/decode/length orchestrator.
 *
 * One instance is owned by [Emulator8086]; it mutates the CPU's persistent
 * [CpuState] fields and returns primitive status values only.
 */
internal class InstructionDecoder(private val cpu: Emulator8086) {
    fun isPrefixOpcode(opcode: Int): Boolean = when (opcode) {
        0x26, 0x2E, 0x36, 0x3E, // segment overrides
        0xF0, 0xF1, // LOCK (and ICEBP alias on later CPUs; treated as LOCK here)
        0xF2, 0xF3, // REP/REPE/REPNE
        -> true
        else -> false
    }

    private fun baseInstSize(opcode: Int): Int =
        cpu.tblBaseInstSize[opcode and 0xFF].toInt() and 0xFF

    private fun iModSize(opcode: Int): Int =
        cpu.tblIModSize[opcode and 0xFF].toInt() and 0xFF

    private fun iWSize(opcode: Int): Int =
        cpu.tblIWSize[opcode and 0xFF].toInt() and 0xFF

    /**
     * Byte length of the current opcode encoding (excluding prefixes already counted
     * in [CpuState.instructionPrefixBytes]). Used for the 286 10-byte limit.
     */
    fun encodingLengthBytes(opcode: Int): Int =
        encodingLengthBytes(opcode, cpu.iMod, cpu.iRm, cpu.iReg, cpu.iW)

    fun encodingLengthBytes(opcode: Int, mod: Int, rm: Int, reg: Int, word: Boolean): Int {
        val modDisp =
            (mod * (if (mod != 3) 1 else 0) + 2 * (if (mod == 0 && rm == 6) 1 else 0)) *
                iModSize(opcode)
        val base = baseInstSize(opcode)
        // Immediate bytes not already folded into baseInstSize. 80/82/83 are imm8 even
        // when the word bit is set; 81 is imm16. 68/69/6A/6B/C0/C1/C8 bake imm into base.
        val immBytes = when (opcode) {
            0x80, 0x82, 0x83 -> 1
            0x81 -> 2
            0x68, 0x69, 0x6A, 0x6B, 0xC8 -> 0
            0xC0, 0xC1 -> 1 // imm8; baseInstSize is opcode+modrm only
            // GRP3 TEST (reg 0 and alias reg 1) takes an immediate; I_W_SIZE is 0
            // because other GRP3 forms have no imm.
            0xF6 -> if (reg <= 1) 1 else 0
            0xF7 -> if (reg <= 1) 2 else 0
            // Control transfers with baseInstSize 0: length is the full encoding.
            0x9A, 0xEA -> 5 // CALL/JMP FAR
            0xE8, 0xE9 -> 3 // CALL/JMP rel16
            0xEB -> 2 // JMP rel8
            0xC2, 0xCA -> 3 // RET/RETF imm16
            0xC3, 0xCB -> 1 // RET/RETF
            else -> iWSize(opcode) * (if (word) 2 else 1)
        }
        return modDisp + base + immBytes
    }

    /**
     * Estimated length of the instruction at CS:IP (including leading prefixes).
     * Does not mutate CPU decode state. Caps at 15 bytes (8086 max with prefixes).
     */
    fun peekInstructionLengthAtCsIp(): Int {
        val cs = cpu.reg16u(REG_CS)
        val ip = cpu.regIp
        val mask = cpu.physicalAddressMask
        val mem = cpu.mem
        val convEnd = cpu.conventionalMemoryEnd
        fun codeByte(relative: Int): Int {
            val phys = ((cs shl 4) + ((ip + relative) and 0xFFFF)) and mask
            return fetchCodeByte(mem, convEnd, phys)
        }
        var prefixBytes = 0
        while (prefixBytes < 14 && isPrefixOpcode(codeByte(prefixBytes))) {
            prefixBytes++
        }
        val opcode = codeByte(prefixBytes)
        var mod = 0
        var rm = 0
        var reg = 0
        if (iModSize(opcode) > 0) {
            val modrm = codeByte(prefixBytes + 1)
            mod = modrm shr 6
            reg = (modrm shr 3) and 7
            rm = modrm and 7
        }
        val word = (opcode and 1) != 0
        val body = encodingLengthBytes(opcode, mod, rm, reg, word)
        val total = prefixBytes + (if (body < 1) 1 else body)
        return if (total > 15) 15 else if (total < 1) 1 else total
    }

    /**
     * Instruction-stream byte at a physical address. Conventional RAM and system ROM
     * are direct [mem] loads — the common fetch path — without [guestRead8] dispatch.
     */
    private fun fetchCodeByte(mem: ByteArray, convEnd: Int, phys: Int): Int {
        if (phys >= 0 && phys < 0xA0000) {
            if (phys >= convEnd) return 0xFF
            return mem[phys].toInt() and 0xFF
        }
        if (phys >= ROM_REGION_START && phys < ROM_REGION_END) {
            return mem[phys].toInt() and 0xFF
        }
        return cpu.guestRead8(phys)
    }

    // Instruction decoding and execution - matches C code structure
    // Returns: false = stop, true = continue
    fun decodeAndExecuteInstruction(): Boolean {
        val mask = cpu.physicalAddressMask
        val mem = cpu.mem
        val convEnd = cpu.conventionalMemoryEnd
        val csBase = cpu.reg16u(REG_CS) shl 4
        val ip = cpu.regIp
        val ipAddr = (csBase + (ip and 0xFFFF)) and mask
        if (ipAddr >= RAM_SIZE || ipAddr < 0) return false
        cpu.pendingDivideError = false
        cpu.pendingException = -1
        // Remember the first byte of a fresh instruction (prefixes keep this IP).
        if (cpu.segOverrideEn == 0 && cpu.repOverrideEn == 0 && cpu.lockOverrideEn == 0) {
            cpu.instructionStartIp = ip
            cpu.instructionPrefixBytes = 0
        }

        // Instruction bytes wrap at the 16-bit IP boundary before segment translation.
        // When the whole CS window sits in conventional RAM or system ROM, fetch is a
        // single masked mem[] load (no per-byte range ladder).
        val windowEnd = csBase + 0xFFFF
        val direct = (csBase >= 0 && windowEnd < convEnd && windowEnd < 0xA0000) ||
            (csBase >= ROM_REGION_START && windowEnd < ROM_REGION_END)
        fun codeByte(relative: Int): Int {
            val phys = (csBase + ((ip + relative) and 0xFFFF)) and mask
            if (direct) return mem[phys].toInt() and 0xFF
            if (phys >= 0 && phys < 0xA0000) {
                return if (phys >= convEnd) 0xFF else mem[phys].toInt() and 0xFF
            }
            if (phys >= ROM_REGION_START && phys < ROM_REGION_END) {
                return mem[phys].toInt() and 0xFF
            }
            return cpu.guestRead8(phys)
        }

        fun codeWord(relative: Int): Int {
            if (direct) {
                val p0 = (csBase + ((ip + relative) and 0xFFFF)) and mask
                val p1 = (csBase + ((ip + relative + 1) and 0xFFFF)) and mask
                return (mem[p0].toInt() and 0xFF) or ((mem[p1].toInt() and 0xFF) shl 8)
            }
            return codeByte(relative) or (codeByte(relative + 1) shl 8)
        }

        // Inline setOpcode — avoids a call + repeated `and 0xFF` on the opcode.
        val tblXlat = cpu.tblXlatOpcode
        val tblSub = cpu.tblXlatSubfunction
        val tblMod = cpu.tblIModSize
        val tblFlags = cpu.tblStdFlags
        val tblBase = cpu.tblBaseInstSize
        val tblIW = cpu.tblIWSize
        val opcode0 = codeByte(0)
        val op = opcode0 and 0xFF
        cpu.rawOpcodeId = opcode0
        cpu.xlatOpcodeId = tblXlat[op].toInt() and 0xFF
        cpu.extra = tblSub[op].toInt() and 0xFF
        val modSize = tblMod[op].toInt() and 0xFF
        cpu.iModSize = modSize
        cpu.setFlagsType = tblFlags[op].toInt() and 0xFF

        // Extract i_w and i_d fields from instruction (matches C code)
        val iReg4bit = opcode0 and 7
        cpu.iW = (iReg4bit and 1) != 0
        cpu.iD = ((iReg4bit shr 1) and 1) != 0

        // Extract instruction data fields (matches C: CAST(short)opcode_stream[1/2/3]).
        // Always read — some control transfers have baseInstSize 0 but still consume imm.
        cpu.iData0 = codeWord(1).toShort().toInt()
        cpu.iData1 = codeWord(2).toShort().toInt()
        cpu.iData2 = codeWord(3).toShort().toInt()

        // Prefix holds: number of following decode steps that inherit the prefix.
        if (cpu.segOverrideEn > 0) cpu.segOverrideEn--
        if (cpu.repOverrideEn > 0) cpu.repOverrideEn--
        if (cpu.lockOverrideEn > 0) cpu.lockOverrideEn--

        // i_mod_size > 0 indicates that opcode uses i_mod/i_rm/i_reg, so decode them
        cpu.rmIsMemory = false
        if (modSize > 0) {
            // Decode the ModR/M byte from the UNSIGNED low byte of iData0. iData0 is a
            // sign-extended 16-bit read, so it can be negative when the byte after the
            // ModR/M has its high bit set; computing i_reg via signed division (as the
            // naive `/ 8 & 7`) then corrupts the reg field.
            // Masking to the byte first keeps the decode correct for the real BIOS.
            val modrm = cpu.iData0 and 0xFF
            cpu.iMod = modrm shr 6
            cpu.iRm = modrm and 7
            cpu.iReg = (modrm shr 3) and 7

            if ((cpu.iMod == 0 && cpu.iRm == 6) || (cpu.iMod == 2)) {
                cpu.iData2 = codeWord(4).toShort().toInt()
            } else if (cpu.iMod != 1) {
                cpu.iData2 = cpu.iData1
            } else {
                // If i_mod is 1, operand is (usually) 8 bits rather than 16 bits
                cpu.iData1 = (cpu.iData1.toByte().toInt())
            }

            cpu.decodeRmReg()
        }

        // GRP3 timings need the pre-execution decode fields; some handlers call setOpcode().
        val opcodeByte = cpu.rawOpcodeId
        val grp3Reg = cpu.iReg
        val grp3Word = cpu.iW
        val grp3Mem = modSize > 0 && cpu.iMod != 3

        // True encoding length for the 286 10-byte limit only — 8086/8088 skip this.
        if (cpu.maxInsnBytes != Int.MAX_VALUE) {
            val encodingLength = encodingLengthBytes(opcodeByte)
            if (cpu.instructionPrefixBytes + encodingLength > cpu.maxInsnBytes) {
                cpu.raiseException(13)
            } else {
                cpu.executeXlatOpcode(iReg4bit)
            }
        } else {
            cpu.executeXlatOpcode(iReg4bit)
        }

        cpu.lastOpcodeByte = opcodeByte and 0xFF
        cpu.lastInstructionCycles = if (cpu.cycleOverride != CYCLE_OVERRIDE_NONE) {
            cpu.cycleOverride
        } else if (opcodeByte == 0xF6 || opcodeByte == 0xF7) {
            CycleTables.grp3Cycles(grp3Reg, word = grp3Word, memory = grp3Mem)
        } else {
            var cycles = CycleTables.BASE[opcodeByte and 0xFF]
            if (grp3Mem) cycles += CycleTables.MEMORY_OPERAND_PENALTY
            cycles
        }
        cpu.cycleOverride = CYCLE_OVERRIDE_NONE

        // Post-execution length matches prior behaviour (handlers may remap via setOpcode).
        val rawAfter = cpu.rawOpcodeId and 0xFF
        val ipIncrement = (cpu.iMod * (if (cpu.iMod != 3) 1 else 0) + 2 * (if (cpu.iMod == 0 && cpu.iRm == 6) 1 else 0)) * cpu.iModSize +
            (tblBase[rawAfter].toInt() and 0xFF) +
            (tblIW[rawAfter].toInt() and 0xFF) * (if (cpu.iW) 2 else 1)
        val faultPending = cpu.pendingDivideError || cpu.pendingException >= 0
        if (!(faultPending && !cpu.advanceIpBeforeDivideError())) {
            cpu.regIp += ipIncrement
        }
        if (!faultPending && isPrefixOpcode(opcodeByte)) {
            cpu.instructionPrefixBytes += ipIncrement
        } else if (!faultPending) {
            // Prefix holds are sized so the following opcode still sees them after the
            // start-of-decode decrement (set to 2 → 1). Clear the leftover after that
            // opcode finishes; otherwise consecutive prefixed instructions keep the
            // previous hold alive, instructionPrefixBytes never resets, and the 286
            // 10-byte limit spuriously #GPs (PSP init ES: MOV chain).
            cpu.segOverrideEn = 0
            cpu.repOverrideEn = 0
            cpu.lockOverrideEn = 0
        }

        // If instruction needs to update SF, ZF and PF, set them as appropriate
        if (!faultPending && cpu.setFlagsType and FLAGS_UPDATE_SZP != 0) {
            // ZF must reflect the result truncated to the operand width. The reference
            // C masks op_result via CAST(unsigned char/short) at the operation site, so
            // e.g. INC AL of 0xFF (raw result 0x100) wraps to 0x00 and sets ZF. signOf
            // and parity already mask; ZF needs the same width mask here.
            val widthMask = if (cpu.iW) 0xFFFF else 0xFF
            val m = mem
            m[REGS_BASE + FLAG_SF] = cpu.signOf(cpu.opResult).toByte()
            m[REGS_BASE + FLAG_ZF] = if ((cpu.opResult and widthMask) == 0) 1 else 0
            m[REGS_BASE + FLAG_PF] = cpu.parityFlag(cpu.opResult).toByte()

            // If instruction is an arithmetic or logic operation, also set AF/OF/CF as appropriate.
            if (cpu.setFlagsType and FLAGS_UPDATE_AO_ARITH != 0) {
                cpu.setAFOfArith()
            }
            if (cpu.setFlagsType and FLAGS_UPDATE_OC_LOGIC != 0) {
                cpu.setCF(false)
                cpu.setOF(false)
            }
        }

        // Deliver #DE / #GP / #SS after IP/flag updates to match silicon.
        if (cpu.pendingDivideError) {
            cpu.pendingDivideError = false
            cpu.pcInterrupt(0, cpu.faultReturnIp())
        } else if (cpu.pendingException >= 0) {
            val vector = cpu.pendingException
            cpu.pendingException = -1
            cpu.pcInterrupt(vector, cpu.faultReturnIp())
        }

        return true
    }
}
