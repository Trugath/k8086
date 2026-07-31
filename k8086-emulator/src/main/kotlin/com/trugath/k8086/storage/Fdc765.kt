package com.trugath.k8086.storage

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.chipset.Dma8237
import com.trugath.k8086.chipset.Pic8259
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.diskImage

import java.io.RandomAccessFile

// NEC uPD765 floppy disk controller, as used on the IBM PC/XT, decoded at
// 0x3F0-0x3F7. It moves sector data to/from memory over DMA channel 2 and signals
// completion on IRQ6, which is what the real BIOS INT 13h and INT 19h paths expect.
//
//   0x3F2 DOR  Digital Output Register: motor/drive select, DMA+IRQ enable, reset
//   0x3F4 MSR  Main Status Register (read-only): RQM/DIO/CB handshake bits
//   0x3F5 DATA command/parameter bytes in, result/status bytes out
//
// The controller runs the classic three phases: command (CPU writes bytes), execution
// (data moves over DMA), result (CPU reads status bytes). Enough of the command set is
// implemented to reset, recalibrate, seek, read sectors and report status.
internal class Fdc765(
    private val cpu: Emulator8086,
    private val pic: Pic8259,
    private val dma: Dma8237,
) : IoDevice {

    private enum class Phase { COMMAND, EXECUTION, RESULT }

    private var phase = Phase.COMMAND
    private var dor = 0

    private val command = IntArray(16)
    private var commandLen = 0
    private var commandCount = 0

    private val result = IntArray(16)
    private var resultLen = 0
    private var resultIndex = 0

    private val presentCylinder = IntArray(4)
    private var st0 = 0
    private var interruptPending = false
    // After a controller reset the BIOS issues four Sense Interrupt Status commands to
    // poll all drives; this counter feeds them the expected 0xC0|drive results.
    private var resetSenseRemaining = 0
    // Sticky disk-change line (DIR bit 7 on XT); cleared when the drive is selected
    // with motor on. Host can mark a media change via [signalDiskChange].
    private var diskChanged = false

    /** Host helper: mark media as changed (DIR bit 7 set until drive/motor select). */
    fun signalDiskChange() {
        diskChanged = true
    }

    override fun ioReadByte(port: Int): Int = when (port) {
        0x3F4 -> mainStatus()
        0x3F5 -> readData()
        0x3F0 -> 0x00
        0x3F7 -> if (diskChanged) 0x80 else 0x00
        else -> 0xFF
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (port) {
            0x3F2 -> writeDor(v)
            0x3F5 -> writeData(v)
            0x3F7 -> { /* data-rate select: timing only */ }
        }
    }

    // MSR: RQM (ready) is always set in this model; DIO selects transfer direction;
    // CB marks the controller busy outside the idle command phase.
    private fun mainStatus(): Int {
        var m = 0x80 // RQM
        when (phase) {
            Phase.RESULT -> m = m or 0x40 or 0x10 // DIO (FDC->CPU) + CB
            Phase.EXECUTION -> m = m or 0x10 or 0x20 // CB + non-DMA/exec
            Phase.COMMAND -> if (commandCount > 0) m = m or 0x10 // CB once a command is in progress
        }
        return m
    }

    private fun writeDor(v: Int) {
        val wasReset = (dor and 0x04) == 0
        val prevDrive = dor and 0x03
        val prevMotor = (dor shr 4) and 0x0F
        dor = v
        val nowReset = (v and 0x04) == 0
        val drive = v and 0x03
        val motor = (v shr 4) and 0x0F
        // Selecting a drive with its motor on clears the sticky disk-change line.
        if ((motor and (1 shl drive)) != 0 && (drive != prevDrive || (prevMotor and (1 shl drive)) == 0)) {
            diskChanged = false
        }
        if (wasReset && !nowReset) {
            // Reset released: the controller interrupts and expects to be polled.
            phase = Phase.COMMAND
            commandCount = 0
            resultIndex = 0
            resultLen = 0
            st0 = 0xC0
            resetSenseRemaining = 4
            raiseInterrupt()
        }
    }

    private fun writeData(v: Int) {
        if (phase == Phase.RESULT) return // ignore stray writes during result phase
        if (commandCount == 0) {
            command[0] = v
            commandLen = commandLength(v and 0x1F)
            commandCount = 1
            phase = Phase.COMMAND
        } else {
            command[commandCount++] = v
        }
        if (commandCount >= commandLen) execute()
    }

    private fun readData(): Int {
        if (phase != Phase.RESULT) return 0xFF
        val b = result[resultIndex++]
        if (resultIndex >= resultLen) {
            phase = Phase.COMMAND
            commandCount = 0
        }
        return b and 0xFF
    }

    private fun execute() {
        val opcode = command[0] and 0x1F
        when (opcode) {
            0x03 -> { commandDone(); } // Specify: no result, no interrupt
            0x04 -> senseDriveStatus()
            0x07 -> recalibrate()
            0x08 -> senseInterruptStatus()
            0x0A -> readId()
            0x0F -> seek()
            0x05, 0x06, 0x09, 0x0C -> readWrite(opcode) // read/write (deleted) data
            0x0D -> formatTrack()
            else -> { // Invalid command
                st0 = 0x80
                startResult(intArrayOf(st0))
            }
        }
    }

    private fun commandDone() {
        phase = Phase.COMMAND
        commandCount = 0
    }

    private fun senseDriveStatus() {
        val drive = command[1] and 3
        // ST3: drive select always; ready + two-sided + track0 only when an image is mounted.
        var st3 = drive
        if (cpu.diskImage(drive) != null) {
            st3 = st3 or 0x28 // ready + two-sided
            if (presentCylinder[drive] == 0) st3 = st3 or 0x10 // track 0
        }
        startResult(intArrayOf(st3))
    }

    private fun recalibrate() {
        val drive = command[1] and 3
        presentCylinder[drive] = 0
        st0 = 0x20 or drive // seek end
        resetSenseRemaining = 0 // a real seek interrupt supersedes the reset poll
        commandDone()
        raiseInterrupt()
    }

    private fun seek() {
        val drive = command[1] and 3
        presentCylinder[drive] = command[2]
        st0 = 0x20 or drive // seek end
        resetSenseRemaining = 0
        commandDone()
        raiseInterrupt()
    }

    private fun senseInterruptStatus() {
        if (resetSenseRemaining > 0) {
            // Polling phase after reset: report each drive in turn.
            val drive = 4 - resetSenseRemaining
            resetSenseRemaining--
            startResult(intArrayOf(0xC0 or drive, presentCylinder[drive and 3]))
            return
        }
        val drive = st0 and 3
        startResult(intArrayOf(st0, presentCylinder[drive]))
        st0 = 0x80 // subsequent polls report "invalid" (no interrupt outstanding)
    }

    private fun readId() {
        val drive = command[1] and 3
        st0 = drive
        val c = presentCylinder[drive]
        startResult(intArrayOf(st0, 0, 0, c, 0, 1, 2))
    }

    private fun readWrite(opcode: Int) {
        // uPD765 read/write command layout: [0]=opcode, [1]=(HD<<2)|drive, [2]=C,
        // [3]=H, [4]=R (sector), [5]=N (size code), [6]=EOT, [7]=GPL, [8]=DTL.
        val drive = command[1] and 3
        val head = (command[1] shr 2) and 1
        var cyl = command[2]
        var hd = command[3]
        var sector = command[4]
        val n = command[5]
        val eot = command[6]
        val sectorSize = 128 shl n
        val img = cpu.diskImage(drive)
        val writing = opcode == 0x05 || opcode == 0x09

        if (img == null) {
            st0 = 0x40 or (head shl 2) or drive // abnormal termination, not ready
            startResult(intArrayOf(st0, 0x01, 0x00, cyl, hd, sector, n))
            raiseInterrupt()
            return
        }

        val geo = geometryFor(img.length())
        var guardBytes = 0
        val maxBytes = geo.totalSectors * sectorSize + sectorSize
        try {
            transfer@ while (true) {
                val lba = ((cyl * geo.heads + hd) * geo.sectorsPerTrack) + (sector - 1)
                val offset = lba.toLong() * sectorSize
                for (i in 0 until sectorSize) {
                    if (writing) {
                        val b = dma.dmaReadByte(2)
                        img.seek(offset + i)
                        img.write(b)
                    } else {
                        img.seek(offset + i)
                        val b = img.read()
                        dma.dmaWriteByte(2, if (b < 0) 0 else b)
                    }
                    if (dma.terminalCount(2)) break@transfer
                    if (++guardBytes > maxBytes) break@transfer
                }
                // Advance to the next sector (CHS order), stopping at end of track/side.
                if (sector >= eot) {
                    sector = 1
                    hd++
                    if (hd >= geo.heads) { hd = 0; cyl++ }
                } else sector++
            }
        } catch (_: Exception) {
            st0 = 0x40 or (head shl 2) or drive
            startResult(intArrayOf(st0, 0x00, 0x00, cyl, hd, sector, n))
            raiseInterrupt()
            return
        }

        st0 = (head shl 2) or drive // normal termination
        presentCylinder[drive] = cyl
        startResult(intArrayOf(st0, 0x00, 0x00, cyl, hd, sector, n))
        raiseInterrupt()
    }

    // Format Track: [0]=opcode, [1]=(HD<<2)|drive, [2]=N, [3]=SC, [4]=GPL, [5]=D (fill).
    // For each of SC sectors the FDC DMA-reads a 4-byte ID (C,H,R,N) then writes
    // sectorSize fill bytes into the image at that CHS offset.
    private fun formatTrack() {
        val drive = command[1] and 3
        val head = (command[1] shr 2) and 1
        val n = command[2]
        val sc = command[3]
        val fill = command[5] and 0xFF
        val img = cpu.diskImage(drive)

        if (img == null) {
            st0 = 0x40 or (head shl 2) or drive
            startResult(intArrayOf(st0, 0x01, 0x00, 0, head, 0, n))
            raiseInterrupt()
            return
        }

        val geo = geometryFor(img.length())
        var lastC = 0
        var lastH = head
        var lastR = 0
        var lastN = n
        try {
            for (s in 0 until sc) {
                val c = dma.dmaReadByte(2) and 0xFF
                val h = dma.dmaReadByte(2) and 0xFF
                val r = dma.dmaReadByte(2) and 0xFF
                val idN = dma.dmaReadByte(2) and 0xFF
                lastC = c
                lastH = h
                lastR = r
                lastN = idN
                val idSectorSize = 128 shl idN
                val lba = ((c * geo.heads + h) * geo.sectorsPerTrack) + (r - 1)
                if (lba < 0 || r < 1) {
                    st0 = 0x40 or (head shl 2) or drive
                    startResult(intArrayOf(st0, 0x00, 0x00, c, h, r, idN))
                    raiseInterrupt()
                    return
                }
                val offset = lba.toLong() * idSectorSize
                val fillBuf = ByteArray(idSectorSize) { fill.toByte() }
                img.seek(offset)
                img.write(fillBuf)
                if (dma.terminalCount(2) && s + 1 < sc) break
            }
        } catch (_: Exception) {
            st0 = 0x40 or (head shl 2) or drive
            startResult(intArrayOf(st0, 0x00, 0x00, lastC, lastH, lastR, lastN))
            raiseInterrupt()
            return
        }

        st0 = (head shl 2) or drive
        presentCylinder[drive] = lastC
        startResult(intArrayOf(st0, 0x00, 0x00, lastC, lastH, lastR, lastN))
        raiseInterrupt()
    }

    private fun startResult(bytes: IntArray) {
        for (i in bytes.indices) result[i] = bytes[i]
        resultLen = bytes.size
        resultIndex = 0
        phase = Phase.RESULT
    }

    private fun raiseInterrupt() {
        interruptPending = true
        pic.raiseIrq(6)
    }

    private data class Geometry(val sectorsPerTrack: Int, val heads: Int, val cylinders: Int) {
        val totalSectors get() = sectorsPerTrack * heads * cylinders
    }

    private fun geometryFor(sizeBytes: Long): Geometry = when (sizeBytes) {
        368640L -> Geometry(9, 2, 40)    // 360 KB
        737280L -> Geometry(9, 2, 80)    // 720 KB
        1228800L -> Geometry(15, 2, 80)  // 1.2 MB
        1474560L -> Geometry(18, 2, 80)  // 1.44 MB
        else -> Geometry(18, 2, 80)
    }

    private fun commandLength(opcode: Int): Int = when (opcode) {
        0x03 -> 3
        0x04 -> 2
        0x07 -> 2
        0x08 -> 1
        0x0A -> 2
        0x0F -> 3
        0x02, 0x05, 0x06, 0x09, 0x0C -> 9
        0x0D -> 6
        0x11 -> 9
        else -> 1
    }

    // Inspection helpers.
    fun digitalOutputRegister(): Int = dor
    fun presentCylinderOf(drive: Int): Int = presentCylinder[drive and 3]
}
