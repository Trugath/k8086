package com.trugath.k8086.storage

import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.REG_AH
import com.trugath.k8086.cpu.REG_AL
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CH
import com.trugath.k8086.cpu.REG_CL
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DH
import com.trugath.k8086.cpu.REG_DL
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.diskImage
import java.io.RandomAccessFile

/**
 * Host INT 13h hard-disk shim for drive numbers with bit 7 set (0x80 / 0x81).
 *
 * Supported AH:
 * - 00 reset, 01 status, 02 read, 03 write, 04 verify
 * - 05 format track, 08 get params, 09 init drive params
 * - 0C seek, 0D alternate HD reset, 15 DASD type
 *
 * Floppy requests (DL &lt; 0x80) return false so the IBM BIOS → FDC path runs.
 * Prefer [FixedDiskBios] + [Wd1003] when the port controller is wired; this shim
 * remains for tests and [HardDiskControllerConfig.useInt13Shim].
 */
internal class HdInt13(
    private val cpu: Emulator8086,
    private val geometryOverrides: GeometryOverrides = GeometryOverrides(),
) {
    data class GeometryOverrides(
        val cylinders: Int? = null,
        val heads: Int? = null,
        val sectorsPerTrack: Int? = null,
    )

    private var lastStatus = 0
    private val sectorBuf = ByteArray(512)

    /** @deprecated Use [HdGeometry]; kept for existing tests. */
    data class Geometry(val cylinders: Int, val heads: Int, val sectorsPerTrack: Int) {
        val sectorsPerCylinder: Int get() = heads * sectorsPerTrack
        fun lba(cyl: Int, head: Int, sector: Int): Long =
            (cyl.toLong() * heads + head) * sectorsPerTrack + (sector - 1)
    }

    fun geometryFor(image: RandomAccessFile): Geometry {
        val g = HdGeometry.fromImage(
            image,
            overrideCylinders = geometryOverrides.cylinders,
            overrideHeads = geometryOverrides.heads,
            overrideSpt = geometryOverrides.sectorsPerTrack,
        )
        return Geometry(g.cylinders, g.heads, g.sectorsPerTrack)
    }

    /** Handle INT 13h when DL has the HD bit. Returns true if fully handled. */
    fun handle(): Boolean {
        val dl = cpu.getReg8(REG_DL)
        if ((dl and 0x80) == 0) return false
        val image = cpu.diskImage(dl) ?: run {
            fail(0x01)
            return true
        }
        when (cpu.getReg8(REG_AH)) {
            0x00, 0x0D -> { // reset / alternate hard-disk reset
                lastStatus = 0
                success(0)
            }
            0x01 -> {
                cpu.setReg8(REG_AH, lastStatus)
                setCarry(lastStatus != 0)
            }
            0x02 -> readWrite(image, write = false)
            0x03 -> readWrite(image, write = true)
            0x04 -> readWrite(image, write = false, verifyOnly = true)
            0x05 -> formatTrack(image)
            0x08 -> getParams(image, dl)
            0x09 -> { // initialize drive parameters — accept geometry from caller
                lastStatus = 0
                success(0)
            }
            0x0C -> seek(image)
            0x15 -> {
                val sectors = image.length() / 512
                cpu.setReg8(REG_AH, 0x02)
                cpu.setReg16(REG_CX, ((sectors shr 16) and 0xFFFF).toInt())
                cpu.setReg16(REG_DX, (sectors and 0xFFFF).toInt())
                lastStatus = 0
                setCarry(false)
            }
            else -> fail(0x01)
        }
        return true
    }

    private fun getParams(image: RandomAccessFile, dl: Int) {
        val g = geometryFor(image)
        val cylMax = (g.cylinders - 1).coerceAtLeast(0)
        val sec = g.sectorsPerTrack and 0x3F
        val cl = sec or ((cylMax shr 2) and 0xC0)
        cpu.setReg8(REG_CH, cylMax and 0xFF)
        cpu.setReg8(REG_CL, cl)
        cpu.setReg8(REG_DH, (g.heads - 1).coerceAtLeast(0))
        var driveCount = 0
        if (cpu.diskImage(0x80) != null) driveCount++
        if (cpu.diskImage(0x81) != null) driveCount++
        cpu.setReg8(REG_DL, driveCount.coerceAtLeast(1))
        cpu.setReg8(REG_AH, 0)
        lastStatus = 0
        setCarry(false)
        // keep AH=08 from clobbering DL drive number expectation in older tests: they check DL==1
    }

    private fun seek(image: RandomAccessFile) {
        val g = geometryFor(image)
        val cyl = cpu.getReg8(REG_CH) or ((cpu.getReg8(REG_CL) and 0xC0) shl 2)
        val head = cpu.getReg8(REG_DH)
        if (head >= g.heads || cyl >= g.cylinders) {
            fail(0x04)
            return
        }
        success(0)
    }

    private fun formatTrack(image: RandomAccessFile) {
        val g = geometryFor(image)
        val cyl = cpu.getReg8(REG_CH) or ((cpu.getReg8(REG_CL) and 0xC0) shl 2)
        val head = cpu.getReg8(REG_DH)
        if (head >= g.heads || cyl >= g.cylinders) {
            fail(0x04)
            return
        }
        try {
            sectorBuf.fill(0)
            for (sector in 1..g.sectorsPerTrack) {
                val lba = g.lba(cyl, head, sector)
                if (lba < 0 || (lba + 1) * 512 > image.length()) {
                    fail(0x04)
                    return
                }
                image.seek(lba * 512)
                image.write(sectorBuf)
            }
            success(0)
        } catch (_: Exception) {
            fail(0x10)
        }
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
            // Multi-sector with a reusable 512-byte buffer.
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
