package com.trugath.k8086.cpu

/**
 * Unhandled xlat opcode trap.
 *
 * Extension handlers on [Emulator8086]; compile to static calls (no runtime cost).
 */

// --- xlat opcode group: misc ---

internal fun Emulator8086.executeXlatUnhandled() {

    // Should not happen with a well-formed BIOS table: every xlat opcode
    // 0-48 has a case above. Fail loudly rather than silently no-op'ing,
    // since a print-and-continue here previously masked opcode 6 being
    // entirely unimplemented.
    throw IllegalStateException(
        "Unhandled xlat_opcode_id: $xlatOpcodeId (raw opcode 0x${rawOpcodeId.toString(16).padStart(2, '0')}) " +
            "at CS:IP ${(regs16[REG_CS].toInt() and 0xFFFF).toString(16).padStart(4, '0')}:${regIp.toString(16).padStart(4, '0')}"
    )
}
