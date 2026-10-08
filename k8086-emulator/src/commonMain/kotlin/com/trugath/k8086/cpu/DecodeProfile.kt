package com.trugath.k8086.cpu

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.compat.JvmField
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
 * 80286/80386 are copies with 80186+ remaps applied once.
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

    /** Real-mode 80386: 286 map plus FS/GS and 66h/67h size prefixes. */
    val I80386: DecodeProfile = build80386()

    fun forModel(model: CpuModel): DecodeProfile = when (model) {
        CpuModel.I8086 -> I8086
        CpuModel.I8088 -> I8088
        CpuModel.I80286 -> I80286
        CpuModel.I80386 -> I80386
    }

    private fun remapper(): Array<ByteArray> {
        val xlat = DecodeTables.XLAT_OPCODE.copyOf()
        val sub = DecodeTables.XLAT_SUBFUNCTION.copyOf()
        val flags = DecodeTables.STD_FLAGS.copyOf()
        val base = DecodeTables.BASE_INST_SIZE.copyOf()
        val iw = DecodeTables.I_W_SIZE.copyOf()
        val mod = DecodeTables.I_MOD_SIZE.copyOf()
        return arrayOf(xlat, sub, flags, base, iw, mod)
    }

    private fun set(
        tables: Array<ByteArray>,
        opcode: Int,
        xlatId: Int,
        subfunction: Int = 0,
        modSize: Int = 0,
        stdFlags: Int = 0,
        baseSize: Int,
        iW: Int = 0,
    ) {
        tables[0][opcode] = xlatId.toByte()
        tables[1][opcode] = subfunction.toByte()
        tables[5][opcode] = modSize.toByte()
        tables[2][opcode] = stdFlags.toByte()
        tables[3][opcode] = baseSize.toByte()
        tables[4][opcode] = iW.toByte()
    }

    private fun apply186And286(tables: Array<ByteArray>) {
        set(tables, 0x60, xlatId = 49, baseSize = 1) // PUSHA
        set(tables, 0x61, xlatId = 50, baseSize = 1) // POPA
        set(tables, 0x62, xlatId = 51, modSize = 1, baseSize = 2) // BOUND
        set(tables, 0x68, xlatId = 52, baseSize = 3) // PUSH imm16
        set(tables, 0x69, xlatId = 52, modSize = 1, stdFlags = FLAGS_UPDATE_SZP, baseSize = 4) // IMUL imm16
        set(tables, 0x6A, xlatId = 52, baseSize = 2) // PUSH imm8
        set(tables, 0x6B, xlatId = 52, modSize = 1, stdFlags = FLAGS_UPDATE_SZP, baseSize = 3) // IMUL imm8
        set(tables, 0x6C, xlatId = 57, baseSize = 1) // INS
        set(tables, 0x6D, xlatId = 57, baseSize = 1)
        set(tables, 0x6E, xlatId = 57, baseSize = 1)
        set(tables, 0x6F, xlatId = 57, baseSize = 1)
        set(tables, 0xC0, xlatId = 12, subfunction = 1, modSize = 1, baseSize = 2) // shift imm8
        set(tables, 0xC1, xlatId = 12, subfunction = 1, modSize = 1, baseSize = 2)
        set(tables, 0xC8, xlatId = 54, baseSize = 4) // ENTER
        set(tables, 0xC9, xlatId = 55, baseSize = 1) // LEAVE
        set(tables, 0x0F, xlatId = 56, baseSize = 0) // multi-byte escape
    }

    private fun build80286(): DecodeProfile {
        val tables = remapper()
        apply186And286(tables)
        return DecodeProfile(tables[0], tables[1], tables[2], tables[3], tables[4], tables[5])
    }

    private fun build80386(): DecodeProfile {
        val tables = remapper()
        apply186And286(tables)
        // FS/GS overrides (extra = register-file word index).
        set(tables, 0x64, xlatId = 27, subfunction = REG_FS, baseSize = 1)
        set(tables, 0x65, xlatId = 27, subfunction = REG_GS, baseSize = 1)
        // Operand-size (66h) / address-size (67h) overrides.
        set(tables, 0x66, xlatId = 58, subfunction = 0, baseSize = 1)
        set(tables, 0x67, xlatId = 58, subfunction = 1, baseSize = 1)
        return DecodeProfile(tables[0], tables[1], tables[2], tables[3], tables[4], tables[5])
    }
}
