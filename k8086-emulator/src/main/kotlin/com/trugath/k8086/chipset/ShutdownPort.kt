package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice

/**
 * Bochs-compatible shutdown port at 0x8900.
 *
 * When the guest writes the ASCII sequence "Shutdown" (byte by byte),
 * [onShutdown] is invoked so the host can stop the run loop cleanly.
 */
class ShutdownPort(
    private val onShutdown: () -> Unit,
) : IoDevice {

    private var index = 0
    private var triggered = false

    fun wasTriggered(): Boolean = triggered

    override fun ioReadByte(port: Int): Int = 0xFF

    override fun ioWriteByte(port: Int, value: Int) {
        if (triggered) return
        val b = value and 0xFF
        if (b == EXPECTED[index].code) {
            index++
            if (index == EXPECTED.length) {
                triggered = true
                onShutdown()
            }
        } else {
            index = if (b == EXPECTED[0].code) 1 else 0
        }
    }

    companion object {
        const val PORT = 0x8900
        private const val EXPECTED = "Shutdown"
    }
}
