package com.trugath.k8086.chipset

// The IBM XT keyboard interface. Scan codes (set 1) are presented one at a time on
// PPI port A (0x60) with IRQ1 asserted; the BIOS INT 9 handler reads the code and
// pulses PPI port B bit 7 to acknowledge, at which point the next queued code (if any)
// is delivered. Host key events are translated to XT scan codes and enqueued here.
class Keyboard(
    private val pic: Pic8259,
    private val ppi: Ppi8255,
) {
    private val queue = ArrayDeque<Int>()
    private var awaitingAck = false

    /**
     * Set while the guest is in warm POST after CAD. Host CAD injection stops
     * enqueueing further codes (86Box `keyboard_get_in_reset()` style).
     */
    @Volatile
    var inReset: Boolean = false

    init {
        ppi.onKeyboardClear = { onAcknowledge() }
    }

    // Enqueue a raw scan code (make codes as-is, break codes = make or 0x80).
    @Synchronized
    fun enqueueScanCode(code: Int) {
        if (inReset) return
        queue.addLast(code and 0xFF)
        deliverNext()
    }

    // Convenience: press-and-release a key given its make scan code.
    fun typeKey(makeCode: Int) {
        enqueueScanCode(makeCode)
        enqueueScanCode(makeCode or 0x80)
    }

    /**
     * Inject Ctrl+Alt+Delete (XT set-1), makes then breaks — same shape as 86Box
     * `pc_send_cad`. Breaks are dropped once [inReset] is set by the run loop.
     */
    fun sendCtrlAltDelete() {
        enqueueScanCode(0x1D) // Ctrl make
        enqueueScanCode(0x38) // Alt make
        enqueueScanCode(0x53) // Del make
        enqueueScanCode(0xD3) // Del break
        enqueueScanCode(0xB8) // Alt break
        enqueueScanCode(0x9D) // Ctrl break
    }

    /** Drop queued scan codes and deassert IRQ1. */
    @Synchronized
    fun reset() {
        queue.clear()
        awaitingAck = false
        ppi.keyboardData = 0
        pic.lowerIrq(1)
    }

    @Synchronized
    private fun deliverNext() {
        if (awaitingAck || queue.isEmpty()) return
        ppi.keyboardData = queue.first()
        awaitingAck = true
        pic.raiseIrq(1)
    }

    @Synchronized
    private fun onAcknowledge() {
        if (awaitingAck && queue.isNotEmpty()) queue.removeFirst()
        awaitingAck = false
        deliverNext()
    }
}
