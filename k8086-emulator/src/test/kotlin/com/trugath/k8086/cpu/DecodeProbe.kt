package com.trugath.k8086.cpu

/**
 * Thin wrappers around CPU decode lookups for characterization tests.
 * Kept free of subclassing so final model classes stay final.
 */
internal class DecodeProbe(private val cpu: Emulator8086) {
    fun xlat(opcode: Int): Int = cpu.xlatOpcodeFor(opcode)
    fun sub(opcode: Int): Int = cpu.xlatSubfunctionFor(opcode)
    fun modSize(opcode: Int): Int = cpu.iModSizeFor(opcode)
    fun flags(opcode: Int): Int = cpu.stdFlagsFor(opcode)
    fun baseSize(opcode: Int): Int = cpu.baseInstSizeFor(opcode)
    fun immWSize(opcode: Int): Int = cpu.iWSizeFor(opcode)

    companion object {
        fun i8086(): DecodeProbe = DecodeProbe(Emulator8086())
        fun i8088(): DecodeProbe = DecodeProbe(Emulator8088())
        fun i80286(): DecodeProbe = DecodeProbe(Emulator80286())
    }
}
