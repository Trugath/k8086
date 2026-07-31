package com.trugath.k8086

import com.trugath.k8086.cpu.FLAG_CF
import com.trugath.k8086.cpu.REG_AH
import com.trugath.k8086.cpu.REG_AL
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CH
import com.trugath.k8086.cpu.REG_CL
import com.trugath.k8086.cpu.REG_DH
import com.trugath.k8086.cpu.REG_DL
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.setupBootDisks
import com.trugath.k8086.storage.FloppyInt13
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class FloppyInt13Test {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun readsSectorFrom720kImage() {
        val img = tmp.resolve("fd720.img").toFile()
        val bytes = ByteArray(737280) { 0 }
        // Put a marker in LBA 1 (C0 H0 S2 with 9 SPT)
        val marker = "TXFS1".toByteArray()
        System.arraycopy(marker, 0, bytes, 512, marker.size)
        img.writeBytes(bytes)

        val machine = TestAssets.machine(showVideo = false)
        try {
            machine.cpu.setupBootDisks(img.absolutePath, null)
            val shim = FloppyInt13(machine.cpu)
            val cpu = machine.cpu

            cpu.setReg8(REG_AH, 0x02)
            cpu.setReg8(REG_AL, 1)
            cpu.setReg8(REG_CH, 0)
            cpu.setReg8(REG_CL, 2) // sector 2
            cpu.setReg8(REG_DH, 0)
            cpu.setReg8(REG_DL, 0)
            cpu.setReg16(REG_ES, 0x0000)
            cpu.setReg16(REG_BX, 0x0500)

            assertTrue(shim.handle())
            assertEquals(0, cpu.getReg8(FLAG_CF))
            assertEquals(0, cpu.getReg8(REG_AH))
            assertEquals('T'.code, cpu.readPhysByte(0x0500))
            assertEquals('X'.code, cpu.readPhysByte(0x0501))
            assertEquals('F'.code, cpu.readPhysByte(0x0502))
            assertEquals('S'.code, cpu.readPhysByte(0x0503))
            assertEquals('1'.code, cpu.readPhysByte(0x0504))
        } finally {
            // Release image handles so @TempDir can delete on Windows.
            machine.shutdown()
        }
    }

    @Test
    fun ignoresHardDiskDriveNumbers() {
        val machine = TestAssets.machine(showVideo = false)
        val shim = FloppyInt13(machine.cpu)
        machine.cpu.setReg8(REG_DL, 0x80)
        machine.cpu.setReg8(REG_AH, 0x02)
        assertFalse(shim.handle())
    }
}
