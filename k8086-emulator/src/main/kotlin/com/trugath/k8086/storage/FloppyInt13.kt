package com.trugath.k8086.storage

import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.REG_AH
import com.trugath.k8086.cpu.REG_AL
import com.trugath.k8086.cpu.REG_BL
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CH
import com.trugath.k8086.cpu.REG_CL
import com.trugath.k8086.cpu.REG_DH
import com.trugath.k8086.cpu.REG_DL
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.diskImage
import java.io.RandomAccessFile

/**
 * Host INT 13h floppy shim for DL in 0x00..0x03.
 *
 * The IBM 5160 ROM BIOS diskette services only understand era drive types
 * (primarily 360 KB). Raw 720 KB / 1.44 MB images need host-side CHS mapping
 * from the image size so guests (and the boot sector) can read via INT 13h
 * without depending on the 1982 Disk Base Table.
 *
 * Supported AH: 00 reset, 01 status, 02 read, 03 write, 04 verify, 08 params, 15 DASD type.
 */
internal class FloppyInt13(
    private val cpu: Emulator8086,
) {
    private var lastStatus = 0
    private val sectorBuf = ByteArray(512)

    data class Geometry(val cylinders: Int, val heads: Int, val sectorsPerTrack: Int) {
        fun lba(cyl: Int, head: Int, sector: Int): Long =
            (cyl.toLong() * heads + head) * sectorsPerTrack + (sector - 1)
    }

    fun geometryFor(image: RandomAccessFile): Geometry = when (image.length()) {
        368640L -> Geometry(40, 2, 9)     // 360 KB
        737280L -> Geometry(80, 2, 9)     // 720 KB
        1228800L -> Geometry(80, 2, 15)   // 1.2 MB
        1474560L -> Geometry(80, 2, 18)   // 1.44 MB
        else -> Geometry(80, 2, 18)
    }

    /** Handle INT 13h when DL is a floppy drive. Returns true if handled. */
    fun handle(): Boolean {
        val dl = cpu.getReg8(REG_DL)
        if ((dl and 0x80) != 0 || dl > 3) return false
        val image = cpu.diskImage(dl)
        when (cpu.getReg8(REG_AH)) {
            0x00 -> {
                lastStatus = 0
                success(0)
            }
            0x01 -> {
                cpu.setReg8(REG_AH, lastStatus)
                setCarry(lastStatus != 0)
            }
            0x02 -> {
                if (image == null) {
                    fail(0x80); return true
                }
                readWrite(image, write = false)
            }
            0x03 -> {
                if (image == null) {
                    fail(0x80); return true
                }
                readWrite(image, write = true)
            }
            0x04 -> {
                if (image == null) {
                    fail(0x80); return true
                }
                readWrite(image, write = false, verifyOnly = true)
            }
            0x08 -> getParams(image, dl)
            0x15 -> {
                if (image == null) {
                    cpu.setReg8(REG_AH, 0)
                    setCarry(true)
                } else {
                    // 1 = floppy without change-line sensing (XT-safe).
                    cpu.setReg8(REG_AH, 0x01)
                    lastStatus = 0
                    setCarry(false)
                }
            }
            else -> {
                // Fall through to IBM BIOS for uncommon services.
                return false
            }
        }
        return true
    }

    private fun getParams(image: RandomAccessFile?, dl: Int) {
        if (image == null) {
            fail(0x01)
            return
        }
        val g = geometryFor(image)
        val cylMax = (g.cylinders - 1).coerceAtLeast(0)
        cpu.setReg8(REG_CH, cylMax and 0xFF)
        cpu.setReg8(REG_CL, g.sectorsPerTrack and 0x3F)
        cpu.setReg8(REG_DH, (g.heads - 1).coerceAtLeast(0))
        var driveCount = 0
        for (d in 0..3) {
            if (cpu.diskImage(d) != null) driveCount++
        }
        cpu.setReg8(REG_DL, driveCount.coerceAtLeast(1))
        // BL = drive type: 04h = 1.44M, 03h = 720K, 02h = 1.2M, 01h = 360K
        cpu.setReg8(
            REG_BL,
            when (image.length()) {
                368640L -> 0x01
                1228800L -> 0x02
                737280L -> 0x03
                else -> 0x04
            },
        )
        success(0)
    }

    private fun readWrite(image: RandomAccessFile, write: Boolean, verifyOnly: Boolean = false) {
        val count = cpu.getReg8(REG_AL)
        if (count == 0) {
            success(0)
            return
        }
        val g = geometryFor(image)
        val cyl = cpu.getReg8(REG_CH) or ((cpu.getReg8(REG_CL) and 0xC0) shl 2)
        val sector = cpu.getReg8(REG_CL) and 0x3F
        val head = cpu.getReg8(REG_DH)
        if (sector < 1 || sector > g.sectorsPerTrack || head >= g.heads || cyl >= g.cylinders) {
            fail(0x04)
            return
        }
        val lba = g.lba(cyl, head, sector)
        val maxLba = image.length() / 512
        if (lba < 0 || lba + count > maxLba) {
            fail(0x04)
            return
        }
        val bufSeg = cpu.getReg16(REG_ES)
        val bufOff = cpu.getReg16(REG_BX)
        try {
            for (n in 0 until count) {
                val addr = (bufSeg shl 4) + ((bufOff + n * 512) and 0xFFFF)
                image.seek((lba + n) * 512)
                if (write) {
                    for (i in 0 until 512) {
                        sectorBuf[i] = cpu.readPhysByte(addr + i).toByte()
                    }
                    if (!verifyOnly) image.write(sectorBuf)
                } else {
                    val read = image.read(sectorBuf)
                    if (read < 512) {
                        fail(0x04)
                        return
                    }
                    if (!verifyOnly) {
                        for (i in 0 until 512) {
                            cpu.writePhysByte(addr + i, sectorBuf[i].toInt() and 0xFF)
                        }
                    }
                }
            }
            success(count)
        } catch (_: Exception) {
            fail(0x10)
        }
    }

    private fun success(sectors: Int) {
        lastStatus = 0
        cpu.setReg8(REG_AH, 0)
        cpu.setReg8(REG_AL, sectors and 0xFF)
        setCarry(false)
    }

    private fun fail(status: Int) {
        lastStatus = status and 0xFF
        cpu.setReg8(REG_AH, lastStatus)
        cpu.setReg8(REG_AL, 0)
        setCarry(true)
    }

    private fun setCarry(set: Boolean) {
        cpu.setReg8(FLAG_CF, if (set) 1 else 0)
    }
}
