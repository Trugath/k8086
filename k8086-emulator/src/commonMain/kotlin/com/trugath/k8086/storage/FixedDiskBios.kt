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

/**
 * Host-side Fixed Disk BIOS substitute: intercepts INT 13h for DL≥0x80 and
 * services I/O through [Wd1003] (image + optional DMA path). Stands in for the
 * adapter option ROM at C8000h until a real ROM image is loaded.
 */
internal class FixedDiskBios(
    private val cpu: Emulator8086,
    private val hdc: Wd1003,
) {
    private var lastStatus = 0

    fun handle(): Boolean {
        val dl = cpu.getReg8(REG_DL)
        if ((dl and 0x80) == 0) return false
        val drive = dl and 0x7F
        if (drive > 1 || !hdc.drivePresent(drive)) {
            fail(0x01)
            return true
        }
        when (cpu.getReg8(REG_AH)) {
            0x00, 0x0D -> {
                hdc.ioWriteByte(hdc.ioBase + 1, 0)
                lastStatus = 0
                success(0)
            }
            0x01 -> {
                cpu.setReg8(REG_AH, lastStatus)
                setCarry(lastStatus != 0)
            }
            0x02 -> transfer(drive, write = false)
            0x03 -> transfer(drive, write = true)
            0x04 -> transfer(drive, write = false, verifyOnly = true)
            0x05 -> format(drive)
            0x08 -> getParams(drive)
            0x09 -> {
                lastStatus = 0
                success(0)
            }
            0x0C -> seek(drive)
            0x15 -> {
                val g = hdc.geometry(drive)
                val sectors = g.totalSectors
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

    private fun getParams(drive: Int) {
        val g = hdc.geometry(drive)
        val cylMax = (g.cylinders - 1).coerceAtLeast(0)
        val cl = (g.sectorsPerTrack and 0x3F) or ((cylMax shr 2) and 0xC0)
        cpu.setReg8(REG_CH, cylMax and 0xFF)
        cpu.setReg8(REG_CL, cl)
        cpu.setReg8(REG_DH, (g.heads - 1).coerceAtLeast(0))
        var count = 0
        if (hdc.drivePresent(0)) count++
        if (hdc.drivePresent(1)) count++
        cpu.setReg8(REG_DL, count.coerceAtLeast(1))
        cpu.setReg8(REG_AH, 0)
        lastStatus = 0
        setCarry(false)
    }

    private fun seek(drive: Int) {
        val g = hdc.geometry(drive)
        val cyl = cpu.getReg8(REG_CH) or ((cpu.getReg8(REG_CL) and 0xC0) shl 2)
        val head = cpu.getReg8(REG_DH)
        if (head >= g.heads || cyl >= g.cylinders) {
            fail(0x04)
            return
        }
        success(0)
    }

    private fun format(drive: Int) {
        val cyl = cpu.getReg8(REG_CH) or ((cpu.getReg8(REG_CL) and 0xC0) shl 2)
        val head = cpu.getReg8(REG_DH)
        if (!hdc.formatTrack(drive, cyl, head)) {
            fail(0x04)
            return
        }
        success(0)
    }

    private fun transfer(drive: Int, write: Boolean, verifyOnly: Boolean = false) {
        val count = cpu.getReg8(REG_AL)
        if (count == 0) {
            success(0)
            return
        }
        val cyl = cpu.getReg8(REG_CH) or ((cpu.getReg8(REG_CL) and 0xC0) shl 2)
        val sector = cpu.getReg8(REG_CL) and 0x3F
        val head = cpu.getReg8(REG_DH)
        val mem = (cpu.getReg16(REG_ES) shl 4) + cpu.getReg16(REG_BX)
        val ok = hdc.transferSectors(
            drive = drive,
            cylinder = cyl,
            hd = head,
            sec = sector,
            count = count,
            write = write,
            verifyOnly = verifyOnly,
            memBase = mem,
        )
        if (!ok) {
            fail(0x04)
            return
        }
        success(count)
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
