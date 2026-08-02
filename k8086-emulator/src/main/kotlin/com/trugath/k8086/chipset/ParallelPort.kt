package com.trugath.k8086.chipset

import com.trugath.k8086.api.IoDevice
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * IBM PC Centronics parallel port (LPT1 defaults: data 0x378, status 0x379, control 0x37A).
 *
 * Captures a byte on each rising STROBE edge (control bit 0). Completes a print job on
 * form-feed (0x0C), idle timeout after the last byte, or an /INIT pulse (control bit 2 low).
 */
class ParallelPort(
    private val basePort: Int = 0x378,
    /** Wall-clock idle flush after the last captured byte (milliseconds). */
    private val idleTimeoutMs: Long = 400L,
) : IoDevice {

    private var dataLatch = 0
    private var control = CTRL_INIT_HIGH // /INIT high (inactive), SELECT_IN off
    private var status = STATUS_READY

    private val currentJob = ArrayDeque<Int>()
    private val completed = ConcurrentLinkedQueue<CapturedPrintJob>()

    @Volatile
    private var lastByteAtMs: Long = 0L

    /** Invoked (on the I/O thread) whenever a job is queued. */
    @Volatile
    var onJobCompleted: ((CapturedPrintJob) -> Unit)? = null

    /** Invoked for each captured byte (after strobe). */
    @Volatile
    var onByte: ((Int) -> Unit)? = null

    override fun ioReadByte(port: Int): Int {
        return when (port - basePort) {
            0 -> dataLatch and 0xFF
            1 -> status and 0xFF
            2 -> control and 0x0F
            else -> 0xFF
        }
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (port - basePort) {
            0 -> dataLatch = v
            1 -> { /* status is read-only */ }
            2 -> writeControl(v)
        }
    }

    private fun writeControl(v: Int) {
        val next = v and 0x0F
        val prev = control
        control = next

        val strobeWas = (prev and CTRL_STROBE) != 0
        val strobeNow = (next and CTRL_STROBE) != 0
        if (!strobeWas && strobeNow) {
            captureByte(dataLatch)
        }

        val initWas = (prev and CTRL_INIT) != 0
        val initNow = (next and CTRL_INIT) != 0
        // /INIT active-low: falling edge abandons the current buffer (fresh job).
        if (initWas && !initNow) {
            synchronized(currentJob) {
                currentJob.clear()
                lastByteAtMs = 0L
            }
        }
    }

    private fun captureByte(byte: Int) {
        val b = byte and 0xFF
        onByte?.invoke(b)
        val completeOnFf: CapturedPrintJob?
        synchronized(currentJob) {
            currentJob.addLast(b)
            lastByteAtMs = System.currentTimeMillis()
            if (b == FORM_FEED) {
                completeOnFf = finishJobLocked()
            } else {
                completeOnFf = null
            }
        }
        completeOnFf?.let { publish(it) }
    }

    /**
     * Flush a pending job if idle longer than [idleTimeoutMs]. Call from the machine tick path.
     */
    fun pollIdle(nowMs: Long = System.currentTimeMillis()) {
        val job: CapturedPrintJob? = synchronized(currentJob) {
            if (currentJob.isEmpty() || lastByteAtMs == 0L) return
            if (nowMs - lastByteAtMs < idleTimeoutMs) return
            finishJobLocked()
        }
        job?.let { publish(it) }
    }

    /** Force-complete any in-progress buffer (e.g. machine shutdown). */
    fun flushPending() {
        val job: CapturedPrintJob? = synchronized(currentJob) {
            if (currentJob.isEmpty()) null else finishJobLocked()
        }
        job?.let { publish(it) }
    }

    fun drainCompletedJobs(): List<CapturedPrintJob> {
        pollIdle()
        val out = mutableListOf<CapturedPrintJob>()
        while (true) {
            val j = completed.poll() ?: break
            out += j
        }
        return out
    }

    fun pendingByteCount(): Int = synchronized(currentJob) { currentJob.size }

    fun completedJobCount(): Int = completed.size

    private fun finishJobLocked(): CapturedPrintJob? {
        if (currentJob.isEmpty()) {
            lastByteAtMs = 0L
            return null
        }
        val bytes = ByteArray(currentJob.size)
        var i = 0
        while (currentJob.isNotEmpty()) {
            bytes[i++] = currentJob.removeFirst().toByte()
        }
        lastByteAtMs = 0L
        return CapturedPrintJob(bytes = bytes, capturedAtMs = System.currentTimeMillis())
    }

    private fun publish(job: CapturedPrintJob) {
        completed.offer(job)
        onJobCompleted?.invoke(job)
    }

    companion object {
        const val FORM_FEED = 0x0C

        /** Control bit 0: STROBE (BIOS pulses high). */
        const val CTRL_STROBE = 0x01
        /** Control bit 2: /INIT (active low; 1 = inactive). */
        const val CTRL_INIT = 0x04
        const val CTRL_INIT_HIGH = CTRL_INIT

        /**
         * Status: /BUSY=1, /ACK=1, SELECT=1, /ERROR=1, PE=0 → ready + selected, paper OK.
         * Classic BIOS AH status mask keeps bits 3–7.
         */
        const val STATUS_BUSY_N = 0x80
        const val STATUS_ACK_N = 0x40
        const val STATUS_PE = 0x20
        const val STATUS_SELECT = 0x10
        const val STATUS_ERROR_N = 0x08
        const val STATUS_READY =
            STATUS_BUSY_N or STATUS_ACK_N or STATUS_SELECT or STATUS_ERROR_N
    }
}

/** One completed LPT capture (raw guest bytes). */
data class CapturedPrintJob(
    val bytes: ByteArray,
    val capturedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPrintJob) return false
        return capturedAtMs == other.capturedAtMs && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + capturedAtMs.hashCode()
        return result
    }
}
