package com.trugath.k8086.cpu

/**
 * Test-only accessors kept as extensions so [Emulator8086] stays the instruction engine.
 * Register/memory APIs used by Machine and device models remain members on the CPU.
 */

/** Write instruction bytes to memory at current CS:IP. */
internal fun Emulator8086.writeInstruction(bytes: ByteArray) {
    val addr = segreg(REG_CS, REG_ZERO, regIp)
    for (i in bytes.indices) {
        if (addr + i < RAM_SIZE) guestWrite8(addr + i, bytes[i].toInt())
    }
}

/** Public wrapper around [Emulator8086.segreg] for tests. */
internal fun Emulator8086.segregPublic(regSeg: Int, regOfs: Int, offset: Int = 0): Int =
    segreg(regSeg, regOfs, offset)

internal fun Emulator8086.getMemArray(): ByteArray = mem

/** Debug string for tracing operand decode. */
internal fun Emulator8086.dbgState(): String =
    "iW=$iW iD=$iD iMod=$iMod iReg=$iReg iRm=$iRm opTo=${opToAddr.toString(16)} " +
        "opFrom=${opFromAddr.toString(16)} rmAddr=${rmAddr.toString(16)} " +
        "opResult=${opResult.toString(16)} extra=$extra xlat=$xlatOpcodeId " +
        "raw=${rawOpcodeId.toString(16)}"

internal fun Emulator8086.getFlag(flagIndex: Int): Int = regs8[flagIndex].toInt()

internal fun Emulator8086.getIoPort(port: Int): Int =
    if (port in 0 until IO_PORT_COUNT) ioPorts[port].toUByte().toInt() else 0

internal fun Emulator8086.setIoPort(port: Int, value: Int) {
    if (port in 0 until IO_PORT_COUNT) {
        ioPorts[port] = (value and 0xFF).toByte()
    }
}
