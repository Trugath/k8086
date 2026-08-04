package com.trugath.k8086.host

import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.InitialVideoMode
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.protocol.CardSpecDto
import com.trugath.k8086.protocol.FloppySpec
import com.trugath.k8086.protocol.GraphicsKind
import com.trugath.k8086.protocol.HardDiskSpec
import com.trugath.k8086.protocol.InitialVideoKind
import com.trugath.k8086.protocol.MotherboardSpec
import com.trugath.k8086.protocol.VmDefinition
import com.trugath.k8086.protocol.VmId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SetupMapperTest {
    @Test
    fun toMachineSetupMapsEnumsAndForcesHeadlessShowVideo() {
        val def = VmDefinition(
            id = VmId(UUID.randomUUID().toString()),
            name = "map",
            u18RomPath = "u18",
            u19RomPath = "u19",
            motherboard = MotherboardSpec(
                cpu = "8086",
                baseMemoryKb = 256,
                mathCoprocessor = true,
                initialVideo = InitialVideoKind.MDA_80x25,
            ),
            graphics = GraphicsKind.NONE,
            enableCom1 = true,
            floppy = FloppySpec(enabled = true, driveImages = listOf("a.img")),
            hardDisk = HardDiskSpec(enabled = true, imagePath = "hd.img", bootFromDisk = true),
            cards = listOf(CardSpecDto("x.jar", config = mapOf("k" to "v"))),
        )
        val setup = SetupMapper.toMachineSetup(def)
        assertEquals(CpuModel.I8086, setup.motherboard.cpu)
        assertEquals(256, setup.motherboard.baseMemoryKb)
        assertEquals(true, setup.motherboard.mathCoprocessor)
        assertEquals(InitialVideoMode.MDA_80x25, setup.motherboard.initialVideo)
        assertEquals(GraphicsAdapter.NONE, setup.graphics)
        assertFalse(setup.showVideo)
        assertEquals(listOf("a.img"), setup.floppy.driveImages)
        assertEquals("hd.img", setup.hardDisk.imagePath)
        assertEquals(true, setup.hardDisk.bootFromDisk)
        assertTrue(setup.cards.isEmpty()) // cards loaded by jar path in host
    }

    @Test
    fun fromMachineSetupRoundTripsCoreFields() {
        val setup = MachineSetup(
            motherboard = MotherboardConfig(
                cpu = CpuModel.I8088,
                baseMemoryKb = 640,
                initialVideo = InitialVideoMode.CGA_80x25,
            ),
            graphics = GraphicsAdapter.CGA,
            showVideo = true,
            enableCom1 = false,
            floppy = com.trugath.k8086.config.FloppyControllerConfig(enabled = false),
            hardDisk = com.trugath.k8086.config.HardDiskControllerConfig(enabled = false),
        )
        val id = VmId(UUID.randomUUID().toString())
        val def = SetupMapper.fromMachineSetup(setup, "round", "u18.bin", "u19.bin", id)
        assertEquals(id, def.id)
        assertEquals("round", def.name)
        assertEquals("u18.bin", def.u18RomPath)
        assertEquals(GraphicsKind.CGA, def.graphics)
        assertEquals(false, def.enableCom1)
        assertEquals(false, def.floppy.enabled)
        assertEquals("8088", def.motherboard.cpu)
        assertEquals(InitialVideoKind.CGA_80x25, def.motherboard.initialVideo)

        val back = SetupMapper.toMachineSetup(def)
        assertEquals(CpuModel.I8088, back.motherboard.cpu)
        assertEquals(640, back.motherboard.baseMemoryKb)
        assertEquals(GraphicsAdapter.CGA, back.graphics)
        assertFalse(back.showVideo)
    }

    @Test
    fun roundTrips80286CpuWireName() {
        val setup = MachineSetup(
            motherboard = MotherboardConfig(cpu = CpuModel.I80286),
            graphics = GraphicsAdapter.CGA,
            showVideo = false,
        )
        val def = SetupMapper.fromMachineSetup(
            setup,
            "286",
            "u18.bin",
            "u19.bin",
            VmId(UUID.randomUUID().toString()),
        )
        assertEquals("80286", def.motherboard.cpu)
        assertEquals(8.0, def.motherboard.cpuMhz)
        assertEquals(CpuModel.I80286, SetupMapper.toMachineSetup(def).motherboard.cpu)
        assertEquals(CpuModel.I80286, CpuModel.fromWire("286"))
    }

    @Test
    fun roundTripsCpuMhz() {
        val setup = MachineSetup(
            motherboard = MotherboardConfig(cpu = CpuModel.I80286, cpuMhz = 10.0),
            graphics = GraphicsAdapter.CGA,
            showVideo = false,
        )
        val def = SetupMapper.fromMachineSetup(
            setup,
            "286-10",
            "u18.bin",
            "u19.bin",
            VmId(UUID.randomUUID().toString()),
        )
        assertEquals(10.0, def.motherboard.cpuMhz)
        val back = SetupMapper.toMachineSetup(def)
        assertEquals(10.0, back.motherboard.effectiveCpuMhz())
        assertEquals(10_000_000.0, back.motherboard.clockHz())
    }
}
