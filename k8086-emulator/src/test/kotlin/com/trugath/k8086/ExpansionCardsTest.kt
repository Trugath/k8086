package com.trugath.k8086

import com.trugath.k8086.cards.gameport.GameportCardFactory
import com.trugath.k8086.cards.lpt.LptCardFactory
import com.trugath.k8086.cards.memexpansion.MemExpansionCardFactory
import com.trugath.k8086.cards.rtcmm58167.Mm58167
import com.trugath.k8086.cards.rtcmm58167.RtcMm58167CardFactory
import com.trugath.k8086.cards.rtcmm58167.buildRtcOptionRom
import com.trugath.k8086.cards.uart8250.Uart8250CardFactory
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.config.MotherboardResources
import com.trugath.k8086.config.ConfigValidator
import com.trugath.k8086.config.CardSelection
import com.trugath.k8086.api.ResourceKind
import com.trugath.k8086.isa.IsaHostImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ExpansionCardsTest {
    @Test
    fun memExpansionFillsConventionalAndMapsUmb() {
        TestAssets.assumeRomsPresent()
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(
                motherboard = MotherboardConfig(baseMemoryKb = 256),
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                realtime = false,
            ),
        )
        val host = IsaHostImpl(machine, "mem")
        MemExpansionCardFactory().create(emptyMap()).attach(host)
        assertEquals(0xA0000, host.conventionalMemoryEnd())
        host.cpuWrite8(0x50000, 0x42)
        assertEquals(0x42, host.cpuRead8(0x50000))
        host.cpuWrite8(0xD0000, 0x7E)
        assertEquals(0x7E, host.cpuRead8(0xD0000))
    }

    @Test
    fun memExpansionCollidesWithMotherboardRam() {
        val tmp = File.createTempFile("mem-exp", ".jar").also { it.writeText("x") }
        val setup = MachineSetup(
            motherboard = MotherboardConfig(baseMemoryKb = 640),
            cards = listOf(
                CardSelection(tmp.absolutePath, MemExpansionCardFactory(), true, emptyMap()),
            ),
        )
        val report = ConfigValidator.validate(setup)
        assertTrue(report.hasErrors, report.issues.toString())
        assertTrue(report.errors.any { it.message.contains("mem") }, report.issues.toString())
    }

    @Test
    fun memExpansionCollidesWithFdromWhenHdEnabled() {
        val tmp = File.createTempFile("mem-fdrom", ".jar").also { it.writeText("x") }
        val setup = MachineSetup(
            motherboard = MotherboardConfig(baseMemoryKb = 256),
            hardDisk = HardDiskControllerConfig(enabled = true, imagePath = "disks/hd.img"),
            cards = listOf(
                CardSelection(
                    tmp.absolutePath,
                    MemExpansionCardFactory(),
                    true,
                    mapOf("umbBase" to "0xC8000", "umbSize" to "0x1000", "convSize" to "0"),
                ),
            ),
        )
        val report = ConfigValidator.validate(setup)
        assertTrue(report.hasErrors, report.issues.toString())
    }

    @Test
    fun uartAndLptAndGameportClaimExpectedPorts() {
        val uart = Uart8250CardFactory().resourceClaims(emptyMap())
        assertTrue(uart.any { it.kind == ResourceKind.IO_PORT && it.start == 0x2F8 })
        assertTrue(uart.any { it.kind == ResourceKind.IRQ && it.start == 3 })

        val lpt = LptCardFactory().resourceClaims(emptyMap())
        assertTrue(lpt.any { it.start == 0x278 })

        val game = GameportCardFactory().resourceClaims(emptyMap())
        assertTrue(game.any { it.start == 0x201 })
    }

    @Test
    fun uartDoesNotCollideWithMotherboardCom1() {
        val claims = MotherboardResources.claims(
            motherboard = MotherboardConfig(baseMemoryKb = 256),
        ) + Uart8250CardFactory().resourceClaims(emptyMap()) +
            LptCardFactory().resourceClaims(emptyMap()) +
            GameportCardFactory().resourceClaims(emptyMap()) +
            RtcMm58167CardFactory().resourceClaims(emptyMap()) +
            MemExpansionCardFactory().resourceClaims(emptyMap())
        for (i in claims.indices) {
            for (j in i + 1 until claims.size) {
                if (claims[i].owner == claims[j].owner) continue
                assertFalse(claims[i].overlaps(claims[j]), "${claims[i]} vs ${claims[j]}")
            }
        }
    }

    @Test
    fun rtcChipRoundTripAndOptionRomChecksum() {
        val chip = Mm58167(0x2C0)
        chip.ioWriteByte(0x2C1, Mm58167.toBcd(45))
        assertEquals(45, Mm58167.fromBcd(chip.ioReadByte(0x2C1)))

        val rom = buildRtcOptionRom(0x2C0)
        assertEquals(0x55, rom[0].toInt() and 0xFF)
        assertEquals(0xAA, rom[1].toInt() and 0xFF)
        var sum = 0
        for (b in rom) sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        assertEquals(0, sum)
    }

    @Test
    fun uartCardAttachesAtCom2() {
        TestAssets.assumeRomsPresent()
        val machine = Machine(
            TestAssets.u18.absolutePath,
            TestAssets.u19.absolutePath,
            MachineOptions(showVideo = false, enableAudio = false, exitOnClose = false, realtime = false),
        )
        Uart8250CardFactory().create(emptyMap()).attach(IsaHostImpl(machine, "uart"))
        assertEquals("uart", machine.ioBus.ownerFor(0x2F8))
        // Scratch register probe
        machine.ioBus.deviceFor(0x2FF)!!.ioWriteByte(0x2FF, 0x5A)
        assertEquals(0x5A, machine.ioBus.deviceFor(0x2FF)!!.ioReadByte(0x2FF))
    }
}
