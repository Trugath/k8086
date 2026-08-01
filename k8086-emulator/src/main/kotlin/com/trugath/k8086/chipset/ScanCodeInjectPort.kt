package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice

/**
 * Guest-visible XT scancode inject port at [PORT] (debug / BIOS unit tests).
 *
 * Each OUT AL writes one set-1 scancode into the keyboard queue (make or break),
 * which raises IRQ1 the same way host key events do.
 */
class ScanCodeInjectPort(
    private val enqueue: (Int) -> Unit,
) : IoDevice {

    override fun ioReadByte(port: Int): Int = 0xFF

    override fun ioWriteByte(port: Int, value: Int) {
        enqueue(value and 0xFF)
    }

    companion object {
        const val PORT = 0x8901
    }
}

/**
 * OUT to [PORT] marks sticky FDC disk-change (DIR bit 7) for INT 13h AH=16 tests.
 */
class DiskChangeInjectPort(
    private val signal: () -> Unit,
) : IoDevice {

    override fun ioReadByte(port: Int): Int = 0xFF

    override fun ioWriteByte(port: Int, value: Int) {
        signal()
    }

    companion object {
        const val PORT = 0x8902
    }
}
