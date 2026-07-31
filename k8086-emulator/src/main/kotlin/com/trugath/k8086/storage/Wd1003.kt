package com.trugath.k8086.storage

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.chipset.Dma8237
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.cpu.Emulator8086
import java.io.RandomAccessFile

/**
 * IBM XT Fixed Disk Adapter (Xebec / WD1002A-WX1 style) at [ioBase]..[ioBase]+3.
 *
 * Named [Wd1003] to match the project roadmap; the register protocol is the classic
 * XT HDC command-block interface (not AT IDE at 1F0h). Ports:
 * - +0 data (command block / sense / PIO)
 * - +1 status (R) / reset (W)
 * - +2 controller select pulse (W)
 * - +3 DMA + IRQ mask (W): bit0=DMA, bit1=IRQ
 *
 * IRQ5 and DMA channel 3 are the XT defaults (configurable).
 */
internal class Wd1003(
    private val cpu: Emulator8086,
    private val pic: Pic8259,
    private val dma: Dma8237,
    val ioBase: Int = 0x320,
    val irq: Int = 5,
    val dmaChannel: Int = 3,
    private val geometryOverrides: Array<HdGeometry?> = arrayOfNulls(2),
) : IoDevice {

    companion object {
        const val STA_READY = 0x01
        const val STA_INPUT = 0x02
        const val STA_COMMAND = 0x04
        const val STA_SELECT = 0x08
        const val STA_REQUEST = 0x10
        const val STA_INTERRUPT = 0x20

        const val CSB_ERROR = 0x02
        const val CSB_LUN = 0x20

        const val CMD_TESTREADY = 0x00
        const val CMD_RECALIBRATE = 0x01
        const val CMD_SENSE = 0x03
        const val CMD_FORMATDRV = 0x04
        const val CMD_VERIFY = 0x05
        const val CMD_FORMATTRK = 0x06
        const val CMD_READ = 0x08
        const val CMD_WRITE = 0x0A
        const val CMD_SEEK = 0x0B
        const val CMD_SETPARAM = 0x0C
    }

    private val images = arrayOfNulls<RandomAccessFile>(2)
    private val sectorBuf = ByteArray(512)

    private var status = STA_COMMAND or STA_READY
    private var control = 0 // bit0 DMA, bit1 IRQ
    private var csb = 0
    private var error = 0
    private var currentCmd = 0
    private var cmdExpect = 0
    private val cmdBuf = IntArray(14)
    private var cmdIdx = 0

    private var resultExpect = 0
    private var resultIdx = 0
    private val resultBuf = IntArray(8)

    private var drv = 0
    private val cyl = IntArray(2)
    private val head = IntArray(2)
    private val sector = IntArray(2)
    private val sectorCnt = IntArray(2)

    /** Geometry from SETPARAM / overrides / image size. */
    private val paramCyl = intArrayOf(306, 306)
    private val paramHeads = intArrayOf(4, 4)
    private val paramSpt = intArrayOf(17, 17)

    fun attachImage(drive: Int, image: RandomAccessFile?) {
        require(drive in 0..1)
        images[drive] = image
        if (image != null) {
            val g = geometryOverrides[drive] ?: HdGeometry.fromImage(image)
            paramCyl[drive] = g.cylinders
            paramHeads[drive] = g.heads
            paramSpt[drive] = g.sectorsPerTrack
        }
    }

    fun geometry(drive: Int): HdGeometry {
        require(drive in 0..1)
        geometryOverrides[drive]?.let { return it }
        val img = images[drive]
        return if (img != null) {
            HdGeometry.fromImage(img)
        } else {
            HdGeometry(paramCyl[drive], paramHeads[drive], paramSpt[drive])
        }
    }

    fun drivePresent(drive: Int): Boolean = drive in 0..1 && images[drive] != null

    fun statusRegister(): Int = status

    /**
     * High-level multi-sector transfer used by [FixedDiskBios].
     * Returns true on success.
     */
    fun transferSectors(
        drive: Int,
        cylinder: Int,
        hd: Int,
        sec: Int,
        count: Int,
        write: Boolean,
        verifyOnly: Boolean = false,
        memBase: Int? = null,
    ): Boolean {
        val image = images.getOrNull(drive) ?: return false
        val g = geometry(drive)
        if (sec < 1 || sec > g.sectorsPerTrack || hd >= g.heads || cylinder >= g.cylinders) return false
        var c = cylinder
        var h = hd
        var s = sec
        try {
            repeat(count) { n ->
                val lba = g.lba(c, h, s)
                if (lba < 0 || (lba + 1) * 512 > image.length()) return false
                image.seek(lba * 512)
                val addr = memBase
                if (write) {
                    if (addr != null) {
                        for (i in 0 until 512) sectorBuf[i] = cpu.readPhysByte(addr + n * 512 + i).toByte()
                    } else {
                        for (i in 0 until 512) sectorBuf[i] = dma.dmaReadByte(dmaChannel).toByte()
                    }
                    if (!verifyOnly) image.write(sectorBuf)
                } else {
                    if (image.read(sectorBuf) < 512) return false
                    if (!verifyOnly) {
                        if (addr != null) {
                            for (i in 0 until 512) cpu.writePhysByte(addr + n * 512 + i, sectorBuf[i].toInt() and 0xFF)
                        } else {
                            for (i in 0 until 512) dma.dmaWriteByte(dmaChannel, sectorBuf[i].toInt() and 0xFF)
                        }
                    }
                }
                val next = g.advanceChs(c, h, s)
                c = next.first; h = next.second; s = next.third
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun formatTrack(drive: Int, cylinder: Int, hd: Int): Boolean {
        val image = images.getOrNull(drive) ?: return false
        val g = geometry(drive)
        if (hd >= g.heads || cylinder >= g.cylinders) return false
        sectorBuf.fill(0)
        return try {
            for (sec in 1..g.sectorsPerTrack) {
                val lba = g.lba(cylinder, hd, sec)
                image.seek(lba * 512)
                image.write(sectorBuf)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun ioReadByte(port: Int): Int {
        val off = port - ioBase
        return when (off) {
            0 -> readData()
            1 -> status
            else -> 0xFF
        }
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (port - ioBase) {
            0 -> writeData(v)
            1 -> resetController()
            2 -> {
                status = status and STA_INTERRUPT.inv()
                status = status or STA_SELECT or STA_READY
            }
            3 -> {
                control = v
                if ((control and 0x02) == 0) {
                    // IRQ disabled — lower line conceptually (PIC edge model is raise-only)
                }
            }
        }
    }

    private fun resetController() {
        cyl[0] = 0; cyl[1] = 0
        head[0] = 0; head[1] = 0
        sector[0] = 0; sector[1] = 0
        csb = 0
        error = 0
        status = STA_COMMAND or STA_READY
        cmdExpect = 0
        resultExpect = 0
    }

    private fun writeData(data: Int) {
        if (cmdExpect == 0 && resultExpect == 0 && (status and STA_COMMAND) != 0) {
            // Start new command block
            currentCmd = data
            cmdIdx = 0
            cmdBuf[0] = data
            cmdExpect = when (data) {
                CMD_SETPARAM -> 14
                CMD_TESTREADY, CMD_RECALIBRATE, CMD_SENSE, CMD_FORMATDRV, CMD_VERIFY,
                CMD_FORMATTRK, CMD_READ, CMD_WRITE, CMD_SEEK -> 6
                else -> {
                    csb = CSB_ERROR or CSB_LUN
                    finishResult(includeError = true)
                    return
                }
            }
            status = (status and (STA_READY or STA_INPUT).inv()) or STA_REQUEST
            cmdIdx = 1
            if (cmdExpect == 1) {
                // should not happen
            }
            return
        }
        if (cmdExpect > 0) {
            cmdBuf[cmdIdx++] = data
            if (cmdIdx >= cmdExpect) {
                cmdExpect = 0
                status = status and (STA_COMMAND or STA_REQUEST or STA_READY or STA_INPUT).inv()
                processCommand()
            } else {
                status = status or STA_READY
            }
        }
    }

    private fun readData(): Int {
        if (resultExpect > 0) {
            val b = resultBuf[resultIdx++]
            status = status and STA_INTERRUPT.inv()
            if (resultIdx >= resultExpect) {
                resultExpect = 0
                status = (status and (STA_INPUT or STA_REQUEST or STA_SELECT).inv()) or STA_COMMAND or STA_READY
            }
            return b and 0xFF
        }
        return 0xFF
    }

    private fun processCommand() {
        drv = (cmdBuf[1] shr 5) and 1
        csb = if (drv != 0) CSB_LUN else 0
        error = 0
        when (currentCmd) {
            CMD_TESTREADY -> {
                if (!drivePresent(drv)) {
                    csb = csb or CSB_ERROR
                    error = 0x04
                }
                finishResult(includeError = false)
            }
            CMD_SENSE -> {
                resultBuf[0] = error and 0xFF
                resultBuf[1] = (drv shl 5) or (head[drv] and 0x1F)
                resultBuf[2] = ((cyl[drv] shr 2) and 0xC0) or (sector[drv] and 0x3F)
                resultBuf[3] = cyl[drv] and 0xFF
                resultExpect = 4
                resultIdx = 0
                status = status or STA_INTERRUPT or STA_INPUT or STA_REQUEST or STA_COMMAND or STA_READY
                raiseIrqIfEnabled()
            }
            CMD_RECALIBRATE -> {
                parseChs()
                cyl[drv] = 0
                finishResult(includeError = true)
            }
            CMD_SEEK, CMD_VERIFY, CMD_FORMATDRV -> {
                parseChs()
                if (!drivePresent(drv)) {
                    csb = csb or CSB_ERROR
                    error = 0x04 or 0x80
                }
                finishResult(includeError = true)
            }
            CMD_FORMATTRK -> {
                parseChs()
                if (!formatTrack(drv, cyl[drv], head[drv])) {
                    csb = csb or CSB_ERROR
                    error = 0x04 or 0x80
                }
                finishResult(includeError = true)
            }
            CMD_READ -> {
                parseChs()
                if (!drivePresent(drv)) {
                    csb = csb or CSB_ERROR
                    error = 0x04 or 0x80
                    finishResult(includeError = true)
                    return
                }
                val n = if (sectorCnt[drv] == 0) 256 else sectorCnt[drv]
                val ok = if ((control and 1) != 0) {
                    transferSectors(drv, cyl[drv], head[drv], sector[drv], n, write = false, memBase = null)
                } else {
                    // PIO: fill internal buffer then guest reads data port — simplified: DMA-less
                    // still write via DMA channel if programmed; else mark error
                    transferSectors(drv, cyl[drv], head[drv], sector[drv], n, write = false, memBase = null)
                }
                if (!ok) {
                    csb = csb or CSB_ERROR
                    error = 0x80
                }
                finishResult(includeError = true)
            }
            CMD_WRITE -> {
                parseChs()
                if (!drivePresent(drv)) {
                    csb = csb or CSB_ERROR
                    error = 0x04 or 0x80
                    finishResult(includeError = true)
                    return
                }
                val n = if (sectorCnt[drv] == 0) 256 else sectorCnt[drv]
                val ok = transferSectors(drv, cyl[drv], head[drv], sector[drv], n, write = true, memBase = null)
                if (!ok) {
                    csb = csb or CSB_ERROR
                    error = 0x80
                }
                finishResult(includeError = true)
            }
            CMD_SETPARAM -> {
                parseChs()
                paramCyl[drv] = ((cmdBuf[6] and 3) shl 8) or cmdBuf[7]
                paramHeads[drv] = cmdBuf[8] and 0x1F
                finishResult(includeError = true)
            }
            else -> {
                csb = csb or CSB_ERROR
                finishResult(includeError = true)
            }
        }
    }

    private fun parseChs() {
        head[drv] = cmdBuf[1] and 0x1F
        sector[drv] = cmdBuf[2] and 0x3F
        cyl[drv] = ((cmdBuf[2] and 0xC0) shl 2) or cmdBuf[3]
        sectorCnt[drv] = cmdBuf[4]
        error = 0x80
    }

    private fun finishResult(includeError: Boolean) {
        resultIdx = 0
        resultBuf[0] = csb
        resultExpect = 1
        if (includeError && (csb and CSB_ERROR) != 0 && (error and 0x80) != 0) {
            // sense details follow only after SENSE command; CSB alone for now
        }
        status = status or STA_INTERRUPT or STA_INPUT or STA_REQUEST or STA_COMMAND or STA_READY
        raiseIrqIfEnabled()
    }

    private fun raiseIrqIfEnabled() {
        if ((control and 0x02) != 0) {
            pic.raiseIrq(irq)
        }
    }

    /** Issue a 6-byte command via the data port (helper for tests / FixedDiskBios). */
    fun issueCommand(bytes: IntArray) {
        ioWriteByte(ioBase + 2, 0) // select
        for (b in bytes) ioWriteByte(ioBase, b and 0xFF)
    }
}
