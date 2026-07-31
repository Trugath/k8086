package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice

// Intel 8253 Programmable Interval Timer at ports 0x40-0x43. On the IBM PC/XT the
// 1.193182 MHz input clock drives three counters:
//   - Counter 0: system timer, output wired to IRQ0 (the 18.2 Hz tick).
//   - Counter 1: DRAM refresh timing (functionally cosmetic here).
//   - Counter 2: tone generator, gated by the 8255 PPI, output to the speaker.
//
// This models counter reloads, the low/high byte read-write sequencing, latching,
// and countdown, and raises IRQ0 on the PIC each time counter 0 completes a period.
class Pit8253(private val pic: Pic8259) : IoDevice {

    private class Counter {
        @JvmField var reload = 0          // full reload value (0 means 65536)
        @JvmField var count = 0           // current counting value
        @JvmField var mode = 0            // counting mode 0-5
        @JvmField var rwMode = 3          // 1 = lo only, 2 = hi only, 3 = lo then hi
        @JvmField var writeState = 0      // for rwMode 3: 0 = expecting lo, 1 = expecting hi
        @JvmField var readState = 0
        @JvmField var latched = false
        @JvmField var latchValue = 0
        @JvmField var loadLow = 0
        @JvmField var active = false
        @JvmField var bcd = false
        @JvmField var gate = true       // counting enable (counter 2's gate is the PPI PB0)
        @JvmField var output = false     // counter output level (square wave for mode 3)
    }

    private val counter0 = Counter()
    private val counter1 = Counter()
    private val counter2 = Counter()
    private val counters = arrayOf(counter0, counter1, counter2)

    // Counter 2's gate is driven by 8255 PPI port B bit 0 (speaker timer enable).
    fun setGate2(enabled: Boolean) { counter2.gate = enabled }

    // Current logic level of counter 2's output, exposed on PPI port C bit 5.
    fun timer2Output(): Boolean = counter2.output

    // Counter 1's output pulses DRAM refresh on the XT, which is wired to trigger a
    // DMA channel-0 cycle. Prefer a direct reference over a nullable lambda invoke.
    internal var refreshDma: Dma8237? = null
    var refreshRequest: (() -> Unit)? = null

    // Fractional PIT-clock accumulator: the CPU runs at exactly 4x the PIT input clock.
    private var clockAccumCpuCycles = 0
    private var refreshPending = false

    override fun ioReadByte(port: Int): Int {
        val idx = port and 3
        if (idx == 3) return 0 // control port is write-only
        val c = counters[idx]
        val value = if (c.latched) c.latchValue else c.count
        return when (c.rwMode) {
            1 -> value and 0xFF
            2 -> (value shr 8) and 0xFF
            else -> {
                if (c.readState == 0) {
                    c.readState = 1
                    value and 0xFF
                } else {
                    c.readState = 0
                    c.latched = false
                    (value shr 8) and 0xFF
                }
            }
        }
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        val idx = port and 3
        if (idx == 3) {
            writeControl(v)
            return
        }
        val c = counters[idx]
        when (c.rwMode) {
            1 -> { c.reload = v; c.count = if (v == 0) 0x10000 else v; c.active = true }
            2 -> { c.reload = v shl 8; c.count = if (c.reload == 0) 0x10000 else c.reload; c.active = true }
            else -> {
                if (c.writeState == 0) {
                    c.loadLow = v
                    c.writeState = 1
                } else {
                    c.writeState = 0
                    c.reload = (v shl 8) or c.loadLow
                    c.count = if (c.reload == 0) 0x10000 else c.reload
                    c.active = true
                }
            }
        }
    }

    private fun writeControl(v: Int) {
        val idx = (v shr 6) and 3
        if (idx == 3) return // read-back command (8254 only) - ignore on 8253
        val c = counters[idx]
        val rw = (v shr 4) and 3
        if (rw == 0) {
            // Counter latch command: freeze the current count for reading.
            c.latchValue = c.count
            c.latched = true
            c.readState = 0
            return
        }
        c.rwMode = rw
        c.mode = (v shr 1) and 7
        c.bcd = (v and 1) != 0
        c.writeState = 0
        c.readState = 0
        c.latched = false
        c.active = false
    }

    // Advance all counters by the given number of CPU cycles. IRQ0 is raised each
    // time counter 0 elapses a full period.
    fun tickCpuCycles(cpuCycles: Int) {
        // Exact integer divide: 4.77 MHz / 1.19318 MHz = 4.
        clockAccumCpuCycles += cpuCycles
        val ticks = clockAccumCpuCycles shr 2
        if (ticks <= 0) return
        clockAccumCpuCycles -= ticks shl 2
        advance(ticks)
    }

    private fun advance(ticks: Int) {
        // Inline the common "no terminal count" path for each counter — avoids three
        // method calls when PIT ticks are small (typical per-instruction quantum).
        val c0 = counter0
        if (c0.active && c0.gate) {
            if (c0.count > ticks) c0.count -= ticks
            else advanceOne(c0, ticks, raiseIrq0 = true)
        }
        val c1 = counter1
        if (c1.active && c1.gate) {
            if (c1.count > ticks) c1.count -= ticks
            else advanceOne(c1, ticks, refresh = true)
        }
        val c2 = counter2
        if (c2.active && c2.gate) {
            if (c2.count > ticks) c2.count -= ticks
            else advanceOne(c2, ticks)
        }
        if (refreshPending) {
            refreshPending = false
            val dma = refreshDma
            if (dma != null) dma.refreshCycle()
            else refreshRequest?.invoke()
        }
    }

    private fun advanceOne(
        c: Counter,
        ticks: Int,
        raiseIrq0: Boolean = false,
        refresh: Boolean = false,
    ) {
        var remaining = ticks
        val period = if (c.reload == 0) 0x10000 else c.reload
        while (remaining > 0) {
            if (c.count > remaining) {
                c.count -= remaining
                return
            }
            remaining -= c.count
            var completions = 1
            if (c.mode != 0 && remaining >= period) {
                val extra = remaining / period
                remaining -= extra * period
                completions += extra
            }
            c.count = period
            if ((completions and 1) != 0) c.output = !c.output
            if (raiseIrq0) pic.raiseIrq(0)
            if (refresh) refreshPending = true
            if (c.mode == 0) {
                c.active = false
                return
            }
        }
    }

    // Test/inspection helpers.
    fun currentCount(counter: Int): Int = counters[counter].count
    fun reloadValue(counter: Int): Int = counters[counter].reload

    companion object {
        // 8088 CPU clock (4.77 MHz) / PIT input clock (1.19318 MHz) = 4.
        const val CPU_CLOCKS_PER_PIT_CLOCK = 4.0
    }
}
