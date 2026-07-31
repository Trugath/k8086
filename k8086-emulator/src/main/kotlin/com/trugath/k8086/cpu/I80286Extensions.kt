package com.trugath.k8086.cpu

/**
 * Real-mode 80286 system extensions (0x0F escape: SMSW/LMSW/CLTS).
 *
 * Owned by [Emulator80286]; created once at construction — never allocated
 * on the instruction hot path.
 */
internal class I80286Extensions(private val cpu: Emulator80286) {
    /** Machine status word (CR0 low bits). Reset value matches 286 power-on. */
    var machineStatusWord: Int = Emulator80286.MSW_RESET

    fun execute0FEscape() {
        // Second opcode byte at CS:IP+1; baseInstSize is 0 so we own IP updates.
        val ip = cpu.getIp()
        val op2 = cpu.readPhysByte(csIp(ip + 1))
        when (op2) {
            0x06 -> { // CLTS
                machineStatusWord = machineStatusWord and Emulator80286.MSW_TS.inv()
                cpu.setIp((ip + 2) and 0xFFFF)
            }
            0x01 -> { // LGDT/SGDT/LIDT/SIDT/SMSW/LMSW group
                val modrm = cpu.readPhysByte(csIp(ip + 2))
                val reg = (modrm shr 3) and 7
                val mod = modrm shr 6
                val rm = modrm and 7
                var consumed = 3 // 0F 01 modrm
                var disp = 0
                if (mod != 3) {
                    when {
                        mod == 0 && rm == 6 -> {
                            disp = cpu.readPhysByte(csIp(ip + 3)) or
                                (cpu.readPhysByte(csIp(ip + 4)) shl 8)
                            consumed += 2
                        }
                        mod == 1 -> {
                            disp = cpu.readPhysByte(csIp(ip + 3)).toByte().toInt()
                            consumed += 1
                        }
                        mod == 2 -> {
                            disp = cpu.readPhysByte(csIp(ip + 3)) or
                                (cpu.readPhysByte(csIp(ip + 4)) shl 8)
                            consumed += 2
                        }
                    }
                }
                when (reg) {
                    4 -> { // SMSW r/m16
                        val value = machineStatusWord and 0xFFFF
                        if (mod == 3) {
                            cpu.setReg16(rm, value)
                        } else {
                            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
                            cpu.writePhysByte(addr, value and 0xFF)
                            cpu.writePhysByte(
                                (addr and 0xF0000) or (((addr and 0xFFFF) + 1) and 0xFFFF),
                                (value shr 8) and 0xFF,
                            )
                        }
                    }
                    6 -> { // LMSW r/m16 — only low 4 bits; PE may be set but addressing stays real
                        val value = if (mod == 3) {
                            cpu.getReg16(rm)
                        } else {
                            val addr = cpu.effectiveAddressLinear(mod, rm, disp)
                            cpu.readPhysByte(addr) or (cpu.readPhysByte(
                                (addr and 0xF0000) or (((addr and 0xFFFF) + 1) and 0xFFFF),
                            ) shl 8)
                        }
                        machineStatusWord = (machineStatusWord and Emulator80286.MSW_PE.inv() and
                            Emulator80286.MSW_MP.inv() and Emulator80286.MSW_EM.inv() and
                            Emulator80286.MSW_TS.inv()) or
                            (value and 0x000F)
                    }
                    else -> {
                        // Other 0F 01 forms (descriptor table ops) not implemented in real-mode pass.
                    }
                }
                cpu.setIp((ip + consumed) and 0xFFFF)
            }
            else -> {
                // Unimplemented escape: skip the two-byte opcode.
                cpu.setIp((ip + 2) and 0xFFFF)
            }
        }
    }

    private fun csIp(ip: Int): Int =
        cpu.physicalAddress((cpu.getReg16(REG_CS) shl 4) + (ip and 0xFFFF))
}
