package com.trugath.k8086.host

import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.HardDiskControllerConfig
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
import java.util.UUID

object SetupMapper {
    fun toMachineSetup(def: VmDefinition): MachineSetup = MachineSetup(
        motherboard = MotherboardConfig(
            cpu = CpuModel.fromWire(def.motherboard.cpu),
            baseMemoryKb = def.motherboard.baseMemoryKb,
            mathCoprocessor = def.motherboard.mathCoprocessor,
            initialVideo = when (def.motherboard.initialVideo) {
                InitialVideoKind.SPECIAL_OR_NONE -> InitialVideoMode.SPECIAL_OR_NONE
                InitialVideoKind.CGA_40x25 -> InitialVideoMode.CGA_40x25
                InitialVideoKind.CGA_80x25 -> InitialVideoMode.CGA_80x25
                InitialVideoKind.MDA_80x25 -> InitialVideoMode.MDA_80x25
            },
            postLoop = def.motherboard.postLoop,
        ),
        graphics = when (def.graphics) {
            GraphicsKind.NONE -> GraphicsAdapter.NONE
            GraphicsKind.CGA -> GraphicsAdapter.CGA
        },
        showVideo = false,
        enableCom1 = def.enableCom1,
        floppy = FloppyControllerConfig(
            enabled = def.floppy.enabled,
            driveImages = def.floppy.driveImages,
            useInt13Shim = def.floppy.useInt13Shim,
        ),
        hardDisk = HardDiskControllerConfig(
            enabled = def.hardDisk.enabled,
            imagePath = def.hardDisk.imagePath,
            secondImagePath = def.hardDisk.secondImagePath,
            provisionBytes = def.hardDisk.provisionBytes,
            bootFromDisk = def.hardDisk.bootFromDisk,
            ioBase = def.hardDisk.ioBase,
            irq = def.hardDisk.irq,
            dmaChannel = def.hardDisk.dmaChannel,
            useInt13Shim = def.hardDisk.useInt13Shim,
            useHostFixedDiskBios = def.hardDisk.useHostFixedDiskBios,
            fixedDiskRomPath = def.hardDisk.fixedDiskRomPath,
            cylinders = def.hardDisk.cylinders,
            heads = def.hardDisk.heads,
            sectorsPerTrack = def.hardDisk.sectorsPerTrack,
        ),
        cards = emptyList(), // ISA cards loaded by jar path in LocalHost
    )

    fun fromMachineSetup(
        setup: MachineSetup,
        name: String,
        u18: String,
        u19: String,
        id: VmId = VmId(UUID.randomUUID().toString()),
        cards: List<CardSpecDto> = setup.cards.map {
            CardSpecDto(it.jarPath, it.enabled, it.effectiveConfig())
        },
    ): VmDefinition = VmDefinition(
        id = id,
        name = name,
        u18RomPath = u18,
        u19RomPath = u19,
        motherboard = MotherboardSpec(
            cpu = setup.motherboard.cpu.wireName,
            baseMemoryKb = setup.motherboard.baseMemoryKb,
            mathCoprocessor = setup.motherboard.mathCoprocessor,
            initialVideo = when (setup.motherboard.initialVideo) {
                InitialVideoMode.SPECIAL_OR_NONE -> InitialVideoKind.SPECIAL_OR_NONE
                InitialVideoMode.CGA_40x25 -> InitialVideoKind.CGA_40x25
                InitialVideoMode.CGA_80x25 -> InitialVideoKind.CGA_80x25
                InitialVideoMode.MDA_80x25 -> InitialVideoKind.MDA_80x25
            },
            postLoop = setup.motherboard.postLoop,
        ),
        graphics = when (setup.graphics) {
            GraphicsAdapter.NONE -> GraphicsKind.NONE
            GraphicsAdapter.CGA -> GraphicsKind.CGA
        },
        enableCom1 = setup.enableCom1,
        floppy = FloppySpec(
            enabled = setup.floppy.enabled,
            driveImages = setup.floppy.driveImages,
            useInt13Shim = setup.floppy.useInt13Shim,
        ),
        hardDisk = HardDiskSpec(
            enabled = setup.hardDisk.enabled,
            imagePath = setup.hardDisk.imagePath,
            secondImagePath = setup.hardDisk.secondImagePath,
            provisionBytes = setup.hardDisk.provisionBytes,
            bootFromDisk = setup.hardDisk.bootFromDisk,
            ioBase = setup.hardDisk.ioBase,
            irq = setup.hardDisk.irq,
            dmaChannel = setup.hardDisk.dmaChannel,
            useInt13Shim = setup.hardDisk.useInt13Shim,
            useHostFixedDiskBios = setup.hardDisk.useHostFixedDiskBios,
            fixedDiskRomPath = setup.hardDisk.fixedDiskRomPath,
            cylinders = setup.hardDisk.cylinders,
            heads = setup.hardDisk.heads,
            sectorsPerTrack = setup.hardDisk.sectorsPerTrack,
        ),
        cards = cards,
    )
}
