package com.trugath.k8086.cpu

import com.trugath.k8086.api.CpuModel
/**
 * Immutable per-model opcode decode metadata.
 *
 * Built once at class init; hot-path lookup is a primitive array index.
 * No per-instruction allocation.
 */
internal class DecodeProfile(
    @JvmField val xlatOpcode: ByteArray,
    @JvmField val xlatSubfunction: ByteArray,
    @JvmField val stdFlags: ByteArray,
    @JvmField val baseInstSize: ByteArray,
    @JvmField val iWSize: ByteArray,
    @JvmField val iModSize: ByteArray,
) {
    init {
        require(xlatOpcode.size == 256)
        require(xlatSubfunction.size == 256)
        require(stdFlags.size == 256)
        require(baseInstSize.size == 256)
        require(iWSize.size == 256)
        require(iModSize.size == 256)
    }

    fun xlatOpcodeFor(opcode: Int): Int = xlatOpcode[opcode and 0xFF].toInt() and 0xFF
    fun xlatSubfunctionFor(opcode: Int): Int = xlatSubfunction[opcode and 0xFF].toInt() and 0xFF
    fun stdFlagsFor(opcode: Int): Int = stdFlags[opcode and 0xFF].toInt() and 0xFF
    fun baseInstSizeFor(opcode: Int): Int = baseInstSize[opcode and 0xFF].toInt() and 0xFF
    fun iWSizeFor(opcode: Int): Int = iWSize[opcode and 0xFF].toInt() and 0xFF
    fun iModSizeFor(opcode: Int): Int = iModSize[opcode and 0xFF].toInt() and 0xFF
}

/**
 * Static decode profiles for each [CpuModel]. 8086/8088 share the BIOS tables;
 * 80286 is a copy with 80186/286 remaps applied once.
 */
internal object DecodeProfiles {
    val I8086: DecodeProfile = DecodeProfile(
        xlatOpcode = DecodeTables.XLAT_OPCODE,
        xlatSubfunction = DecodeTables.XLAT_SUBFUNCTION,
        stdFlags = DecodeTables.STD_FLAGS,
        baseInstSize = DecodeTables.BASE_INST_SIZE,
        iWSize = DecodeTables.I_W_SIZE,
        iModSize = DecodeTables.I_MOD_SIZE,
    )

    /** 8088 uses the same opcode map as 8086. */
    val I8088: DecodeProfile = I8086

    val I80286: DecodeProfile = build80286()

    fun forModel(model: CpuModel): DecodeProfile = when (model) {
        CpuModel.I8086 -> I8086
        CpuModel.I8088 -> I8088
        CpuModel.I80286 -> I80286
    }

    private fun build80286(): DecodeProfile {
        val xlat = DecodeTables.XLAT_OPCODE.copyOf()
        val sub = DecodeTables.XLAT_SUBFUNCTION.copyOf()
        val flags = DecodeTables.STD_FLAGS.copyOf()
        val base = DecodeTables.BASE_INST_SIZE.copyOf()
        val iw = DecodeTables.I_W_SIZE.copyOf()
        val mod = DecodeTables.I_MOD_SIZE.copyOf()

        fun set(
            opcode: Int,
            xlatId: Int,
            subfunction: Int = 0,
            modSize: Int = 0,
            stdFlags: Int = 0,
            baseSize: Int,
            iW: Int = 0,
        ) {
            xlat[opcode] = xlatId.toByte()
            sub[opcode] = subfunction.toByte()
            mod[opcode] = modSize.toByte()
            flags[opcode] = stdFlags.toByte()
            base[opcode] = baseSize.toByte()
            iw[opcode] = iW.toByte()
        }

        set(0x60, xlatId = 49, baseSize = 1) // PUSHA
        set(0x61, xlatId = 50, baseSize = 1) // POPA
        set(0x62, xlatId = 51, modSize = 1, baseSize = 2) // BOUND
        set(0x68, xlatId = 52, baseSize = 3) // PUSH imm16
        set(0x69, xlatId = 52, modSize = 1, stdFlags = FLAGS_UPDATE_SZP, baseSize = 4) // IMUL imm16
        set(0x6A, xlatId = 52, baseSize = 2) // PUSH imm8
        set(0x6B, xlatId = 52, modSize = 1, stdFlags = FLAGS_UPDATE_SZP, baseSize = 3) // IMUL imm8
        set(0x6C, xlatId = 57, baseSize = 1) // INS
        set(0x6D, xlatId = 57, baseSize = 1)
        set(0x6E, xlatId = 57, baseSize = 1)
        set(0x6F, xlatId = 57, baseSize = 1)
        set(0xC0, xlatId = 12, subfunction = 1, modSize = 1, baseSize = 2) // shift imm8
        set(0xC1, xlatId = 12, subfunction = 1, modSize = 1, baseSize = 2)
        set(0xC8, xlatId = 54, baseSize = 4) // ENTER
        set(0xC9, xlatId = 55, baseSize = 1) // LEAVE
        set(0x0F, xlatId = 56, baseSize = 0) // multi-byte escape

        return DecodeProfile(xlat, sub, flags, base, iw, mod)
    }
}
