package com.trugath.k8086

import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.ResourceKind
import com.trugath.k8086.cards.adlib.AdlibCardFactory
import com.trugath.k8086.cards.heartbeat.HeartbeatCardFactory
import com.trugath.k8086.cards.ramumb.RamUmbCardFactory
import com.trugath.k8086.config.CardSelection
import com.trugath.k8086.config.ConfigValidator
import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.config.MotherboardResources
import com.trugath.k8086.config.ValidationSeverity
import com.trugath.k8086.cpu.XT_HARD_DISK_BYTES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ConfigValidatorTest {
    @Test
    fun motherboardClaimsIncludeCom1WhenEnabled() {
        val with = MotherboardResources.claims(enableCom1 = true)
        assertTrue(with.any { it.kind == ResourceKind.IO_PORT && it.start == 0x3F8 })
        val without = MotherboardResources.claims(enableCom1 = false)
        assertFalse(without.any { it.start == 0x3F8 })
    }

    @Test
    fun hdControllerClaimsClassicXtResources() {
        val hd = HardDiskControllerConfig(enabled = true, imagePath = "x.img")
        val claims = hd.resourceClaims()
        assertTrue(claims.any { it.kind == ResourceKind.IO_PORT && it.start == 0x320 })
        assertTrue(claims.any { it.kind == ResourceKind.IRQ && it.start == 5 })
        assertTrue(claims.any { it.kind == ResourceKind.DMA_CHANNEL && it.start == 3 })
    }

    @Test
    fun adlibVsMotherboardDoesNotCollide() {
        val claims = MotherboardResources.claims() + AdlibCardFactory().resourceClaims(emptyMap())
        for (i in claims.indices) {
            for (j in i + 1 until claims.size) {
                if (claims[i].owner == claims[j].owner) continue
                assertFalse(claims[i].overlaps(claims[j]), "${claims[i]} vs ${claims[j]}")
            }
        }
    }

    @Test
    fun heartbeatIrqCollisionWithHdControllerWarns() {
        val hb = HeartbeatCardFactory()
        val tmp = File.createTempFile("heartbeat", ".jar").also { it.writeText("x") }
        val setup = MachineSetup(
            hardDisk = HardDiskControllerConfig(enabled = true, imagePath = "disks/hd.img", irq = 5),
            cards = listOf(
                CardSelection(tmp.absolutePath, hb, true, mapOf("port" to "0x310", "irq" to "5")),
            ),
        )
        val report = ConfigValidator.validate(setup)
        assertTrue(report.warnings.any { it.message.contains("IRQ5") }, report.issues.toString())
    }

    @Test
    fun heartbeatPortCollisionWithCom1IsError() {
        val hb = HeartbeatCardFactory()
        val tmp = File.createTempFile("heartbeat-port", ".jar").also { it.writeText("x") }
        val setup = MachineSetup(
            cards = listOf(
                CardSelection(tmp.absolutePath, hb, true, mapOf("port" to "0x3F8", "irq" to "5")),
            ),
        )
        val report = ConfigValidator.validate(setup)
        assertTrue(report.hasErrors, report.issues.toString())
        assertTrue(report.errors.any { it.message.contains("I/O") }, report.issues.toString())
    }

    @Test
    fun umbOverlappingBiosIsError() {
        val umb = RamUmbCardFactory()
        val tmp = File.createTempFile("umb-card", ".jar").also { it.writeText("x") }
        val setup = MachineSetup(
            cards = listOf(
                CardSelection(
                    tmp.absolutePath,
                    umb,
                    true,
                    mapOf("base" to "0xF6000", "size" to "0x1000"),
                ),
            ),
        )
        val report = ConfigValidator.validate(setup)
        assertTrue(report.hasErrors)
        assertTrue(report.errors.any { it.message.contains("mem") }, report.issues.toString())
    }

    @Test
    fun hdControllerWithoutImageIsError() {
        val report = ConfigValidator.validate(
            MachineSetup(hardDisk = HardDiskControllerConfig(enabled = true, imagePath = null)),
        )
        assertTrue(report.hasErrors)
    }

    @Test
    fun noGraphicsWarns() {
        val report = ConfigValidator.validate(MachineSetup(graphics = GraphicsAdapter.NONE))
        assertTrue(report.warnings.any { it.message.contains("graphics", ignoreCase = true) })
    }

    @Test
    fun floppyControllerWithoutDrivesIsOkInfo() {
        val report = ConfigValidator.validate(
            MachineSetup(floppy = FloppyControllerConfig(enabled = true, driveImages = emptyList())),
        )
        assertFalse(report.hasErrors)
        assertTrue(report.issues.any { it.message.contains("no drives", ignoreCase = true) })
    }

    @Test
    fun cardDescriptorsExposeIrqFieldType() {
        val fields = HeartbeatCardFactory().descriptor().fields
        assertTrue(fields.any { it.key == "irq" && it.type == ConfigFieldType.IRQ })
    }

    @Test
    fun hardDiskBytesDefaultMatchesXt() {
        assertEquals(XT_HARD_DISK_BYTES, MachineSetup().hardDiskBytes)
    }

    @Test
    fun severityOrderingHasErrors() {
        val report = ConfigValidator.validate(
            MachineSetup(hardDisk = HardDiskControllerConfig(enabled = true, bootFromDisk = true)),
        )
        assertEquals(ValidationSeverity.ERROR, report.errors.first().severity)
    }

    @Test
    fun ioPortOverlapDetectedViaResourceClaims() {
        val a = AdlibCardFactory().resourceClaims(mapOf("port" to "0x388"))
        val b = AdlibCardFactory().resourceClaims(mapOf("port" to "0x388"))
        assertTrue(a[0].overlaps(b[0]))
    }
}
