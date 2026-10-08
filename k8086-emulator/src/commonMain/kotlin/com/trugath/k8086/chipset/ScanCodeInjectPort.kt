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

/**
 * Guest-visible mouse-event inject port at [PORT] (debug / e2e).
 *
 * Three consecutive OUT AL writes: buttons (bit0=left, bit1=right), signed dx,
 * signed dy — same path as [com.trugath.k8086.Machine.enqueueMouseEvent].
 */
class MouseInjectPort(
    private val enqueue: (dx: Int, dy: Int, buttons: Int) -> Unit,
) : IoDevice {
    private var phase = 0
    private var buttons = 0
    private var dx = 0

    override fun ioReadByte(port: Int): Int = 0xFF

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (phase) {
            0 -> {
                buttons = v
                phase = 1
            }
            1 -> {
                dx = (v shl 24) shr 24
                phase = 2
            }
            else -> {
                val dy = (v shl 24) shr 24
                phase = 0
                enqueue(dx, dy, buttons)
            }
        }
    }

    companion object {
        const val PORT = 0x8903
    }
}
