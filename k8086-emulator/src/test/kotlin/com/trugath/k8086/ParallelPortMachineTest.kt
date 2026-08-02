package com.trugath.k8086

import com.trugath.k8086.chipset.ParallelPort
import com.trugath.k8086.config.HardDiskControllerConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ParallelPortMachineTest {
    @TempDir
    lateinit var temp: File

    private fun machineWithLpt(parallelLog: File? = null): Machine {
        TestAssets.assumeRomsPresent()
        return Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                realtime = false,
                showPrintPreview = false,
                parallelLogPath = parallelLog?.absolutePath,
                hardDisk = HardDiskControllerConfig(enabled = false),
            ),
        )
    }

    private fun biosStyleWrite(machine: Machine, byte: Int) {
        val base = 0x378
        val dev = machine.ioBus.deviceFor(base)!!
        // Strobe pulse on control bit 0 (matches BIOS INT 17h).
        dev.ioWriteByte(base, byte)
        val ctrl = dev.ioReadByte(base + 2)
        dev.ioWriteByte(base + 2, ctrl or ParallelPort.CTRL_STROBE)
        dev.ioWriteByte(base + 2, ctrl and ParallelPort.CTRL_STROBE.inv())
    }

    @Test
    fun machineMapsLpt1AndCapturesStrobedBytes() {
        val machine = machineWithLpt()
        try {
            assertNotNull(machine.ioBus.deviceFor(0x378))
            val status = machine.ioBus.deviceFor(0x379)!!.ioReadByte(0x379)
            assertTrue((status and ParallelPort.STATUS_SELECT) != 0)

            biosStyleWrite(machine, 'O'.code)
            biosStyleWrite(machine, 'K'.code)
            biosStyleWrite(machine, ParallelPort.FORM_FEED)

            val jobs = machine.drainCompletedPrintJobs()
            assertEquals(1, jobs.size)
            assertArrayEquals(
                byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), 0x0C),
                jobs[0].bytes,
            )
        } finally {
            machine.shutdown()
        }
    }

    @Test
    fun parallelLogAppendsCapturedBytes() {
        val log = File(temp, "lpt1.log")
        val machine = machineWithLpt(parallelLog = log)
        try {
            biosStyleWrite(machine, 'P'.code)
            biosStyleWrite(machine, 'R'.code)
            biosStyleWrite(machine, 'N'.code)
            biosStyleWrite(machine, ParallelPort.FORM_FEED)
            machine.drainCompletedPrintJobs()
            assertEquals("PRN\u000c", log.readText(Charsets.ISO_8859_1))
        } finally {
            machine.shutdown()
        }
    }
}
