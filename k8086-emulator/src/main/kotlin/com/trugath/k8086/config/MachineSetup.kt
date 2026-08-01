package com.trugath.k8086.config

import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind
import com.trugath.k8086.cpu.XT_HARD_DISK_BYTES

/** Onboard / system display adapter. */
enum class GraphicsAdapter {
    NONE,
    CGA,
}

/**
 * Optional floppy disk controller (uPD765) plus zero or more drive images (A:–D:).
 *
 * By default INT 13h floppy is served by [com.trugath.k8086.storage.FloppyInt13]
 * (direct image I/O). Set [useInt13Shim] to false so the guest BIOS owns INT 13h
 * through the mapped FDC (DMA ch2 / IRQ6).
 */
data class FloppyControllerConfig(
    val enabled: Boolean = true,
    val driveImages: List<String> = emptyList(),
    /** Guest BIOS owns floppy INT 13h via FDC by default. Set true for legacy host shim. */
    val useInt13Shim: Boolean = false,
) {
    init {
        require(driveImages.size <= ConfigValidator.MAX_FLOPPY_DRIVES) {
            "At most ${ConfigValidator.MAX_FLOPPY_DRIVES} floppy drives"
        }
    }
}

/**
 * Optional XT fixed-disk controller (Xebec-style ports at [ioBase], IRQ5 / DMA3 by default).
 *
 * INT 13h ownership (when [enabled]):
 * - [useInt13Shim]=true → legacy [com.trugath.k8086.storage.HdInt13] (direct image I/O)
 * - else if [useHostFixedDiskBios]=true → host [com.trugath.k8086.storage.FixedDiskBios]
 * - else → guest Fixed Disk option ROM (C800:) owns INT 13h; Wd1003 stays mapped
 */
data class HardDiskControllerConfig(
    val enabled: Boolean = false,
    /** Primary disk image (BIOS 0x80); created/grown to [provisionBytes] when missing or empty. */
    val imagePath: String? = null,
    /** Optional second fixed disk (BIOS 0x81). */
    val secondImagePath: String? = null,
    val provisionBytes: Long = XT_HARD_DISK_BYTES,
    val bootFromDisk: Boolean = false,
    /** Classic XT Fixed Disk Adapter defaults. */
    val ioBase: Int = 0x320,
    val irq: Int = 5,
    val dmaChannel: Int = 3,
    /** Force legacy INT 13h shim (direct image I/O, no Wd1003). */
    val useInt13Shim: Boolean = false,
    /**
     * Host Fixed Disk BIOS intercept (default). Set false so the guest C800 option ROM
     * owns HD INT 13h while Wd1003 remains mapped.
     */
    val useHostFixedDiskBios: Boolean = true,
    /** Optional path to Fixed Disk option ROM (default: roms/fdrom.bin beside U18). */
    val fixedDiskRomPath: String? = null,
    /** Optional CHS overrides when auto-geometry from image size is wrong. */
    val cylinders: Int? = null,
    val heads: Int? = null,
    val sectorsPerTrack: Int? = null,
) {
    fun imagePathForBoot(): String? {
        val path = imagePath ?: return null
        if (!enabled) return null
        return if (bootFromDisk) "@$path" else path
    }

    fun resourceClaims(): List<ResourceClaim> {
        if (!enabled) return emptyList()
        val owner = "hd-controller"
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, ioBase, ioBase + 7, owner),
            ResourceClaim(ResourceKind.IRQ, irq, irq, owner),
            ResourceClaim(ResourceKind.DMA_CHANNEL, dmaChannel, dmaChannel, owner),
        )
    }
}

/**
 * Core motherboard devices that are always present (PIC/PIT/PPI/DMA/keyboard).
 * Optional system adapters are selected via [MachineSetup].
 */
object MotherboardResources {
    fun claims(
        graphics: GraphicsAdapter = GraphicsAdapter.CGA,
        enableCom1: Boolean = true,
        floppy: FloppyControllerConfig = FloppyControllerConfig(),
        hardDisk: HardDiskControllerConfig = HardDiskControllerConfig(),
    ): List<ResourceClaim> = buildList {
        add(ResourceClaim(ResourceKind.IO_PORT, 0x00, 0x0F, "motherboard-dma"))
        add(ResourceClaim(ResourceKind.IO_PORT, 0x80, 0x8F, "motherboard-dma"))
        add(ResourceClaim(ResourceKind.DMA_CHANNEL, 0, 0, "motherboard-refresh"))

        add(ResourceClaim(ResourceKind.IO_PORT, 0x20, 0x21, "motherboard-pic"))

        add(ResourceClaim(ResourceKind.IO_PORT, 0x40, 0x43, "motherboard-pit"))
        add(ResourceClaim(ResourceKind.IRQ, 0, 0, "motherboard-pit"))

        add(ResourceClaim(ResourceKind.IO_PORT, 0x60, 0x63, "motherboard-ppi"))
        add(ResourceClaim(ResourceKind.IRQ, 1, 1, "motherboard-keyboard"))

        if (graphics == GraphicsAdapter.CGA) {
            add(ResourceClaim(ResourceKind.IO_PORT, 0x3D0, 0x3DF, "cga-adapter"))
        }
        if (floppy.enabled) {
            add(ResourceClaim(ResourceKind.IO_PORT, 0x3F0, 0x3F7, "floppy-controller"))
            add(ResourceClaim(ResourceKind.IRQ, 6, 6, "floppy-controller"))
            add(ResourceClaim(ResourceKind.DMA_CHANNEL, 2, 2, "floppy-controller"))
        }
        if (enableCom1) {
            add(ResourceClaim(ResourceKind.IO_PORT, 0x3F8, 0x3FF, "com1-uart"))
            add(ResourceClaim(ResourceKind.IRQ, 4, 4, "com1-uart"))
        }
        addAll(hardDisk.resourceClaims())

        add(ResourceClaim(ResourceKind.MEMORY, 0xF6000, 0xFFFFF, "motherboard-bios"))
    }

    /** @deprecated Use [claims] with adapter flags. */
    fun claims(
        enableCom1: Boolean = true,
        enableCga: Boolean = true,
        enableFdc: Boolean = true,
    ): List<ResourceClaim> = claims(
        graphics = if (enableCga) GraphicsAdapter.CGA else GraphicsAdapter.NONE,
        enableCom1 = enableCom1,
        floppy = FloppyControllerConfig(enabled = enableFdc),
        hardDisk = HardDiskControllerConfig(enabled = false),
    )
}

enum class ValidationSeverity { ERROR, WARNING, INFO }

data class ValidationIssue(
    val severity: ValidationSeverity,
    val message: String,
)

data class ValidationReport(
    val issues: List<ValidationIssue>,
) {
    val hasErrors: Boolean get() = issues.any { it.severity == ValidationSeverity.ERROR }
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.WARNING }
}

data class CardSelection(
    val jarPath: String,
    val factory: com.trugath.k8086.api.IsaCardFactory,
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap(),
) {
    fun effectiveConfig(): Map<String, String> {
        val defaults = factory.descriptor().fields.associate { it.key to it.defaultValue }
        return defaults + config.filterValues { it.isNotBlank() }
    }
}

/**
 * Full machine setup from the wizard or CLI.
 *
 * System adapters (graphics, FDC, HD controller, COM1) are first-class; ISA JARs are
 * additional expansion cards.
 */
data class MachineSetup(
    val motherboard: MotherboardConfig = MotherboardConfig(),
    val graphics: GraphicsAdapter = GraphicsAdapter.CGA,
    /** Open the CGA host window when [graphics] is CGA. */
    val showVideo: Boolean = true,
    val enableCom1: Boolean = true,
    val floppy: FloppyControllerConfig = FloppyControllerConfig(),
    val hardDisk: HardDiskControllerConfig = HardDiskControllerConfig(),
    val cards: List<CardSelection> = emptyList(),
) {
    val floppyImages: List<String>
        get() = if (floppy.enabled) floppy.driveImages else emptyList()

    val floppyImage: String? get() = floppyImages.firstOrNull()

    val hardDiskImage: String? get() = hardDisk.imagePath?.takeIf { hardDisk.enabled }

    val bootFromHardDisk: Boolean get() = hardDisk.enabled && hardDisk.bootFromDisk

    val hardDiskBytes: Long get() = hardDisk.provisionBytes

    fun hardDiskPathForBoot(): String? = hardDisk.imagePathForBoot()
}

object ConfigValidator {
    const val MAX_FLOPPY_DRIVES = 4

    fun validate(setup: MachineSetup): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        if (setup.floppy.enabled) {
            if (setup.floppy.driveImages.size > MAX_FLOPPY_DRIVES) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "At most $MAX_FLOPPY_DRIVES floppy drives (got ${setup.floppy.driveImages.size})",
                )
            }
            setup.floppy.driveImages.forEachIndexed { i, path ->
                if (!java.io.File(path).isFile) {
                    issues += ValidationIssue(
                        ValidationSeverity.ERROR,
                        "Floppy ${'A' + i}: image not found: $path",
                    )
                }
            }
            if (setup.floppy.driveImages.isEmpty()) {
                issues += ValidationIssue(
                    ValidationSeverity.INFO,
                    "Floppy controller installed with no drives",
                )
            } else {
                val labels = setup.floppy.driveImages.indices.joinToString(", ") { "${'A' + it}:" }
                issues += ValidationIssue(
                    ValidationSeverity.INFO,
                    "Floppy controller + ${setup.floppy.driveImages.size} drive(s): $labels",
                )
            }
        } else if (setup.floppy.driveImages.isNotEmpty()) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "Floppy images listed but floppy controller is disabled — drives will be ignored",
            )
        }

        if (setup.hardDisk.enabled) {
            if (setup.hardDisk.imagePath.isNullOrBlank()) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "Hard disk controller enabled but no image path set",
                )
            }
            if (setup.hardDisk.bootFromDisk && setup.hardDisk.imagePath.isNullOrBlank()) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "Boot from hard disk requires an image path",
                )
            }
            if (setup.hardDisk.provisionBytes < 512L * 17 * 4) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "Hard-disk size is too small (need at least one cylinder)",
                )
            }
            if (setup.hardDisk.irq !in 2..7) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "HD controller IRQ must be 2..7 (got ${setup.hardDisk.irq})",
                )
            }
            if (setup.hardDisk.dmaChannel !in 0..3) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "HD controller DMA must be 0..3 (got ${setup.hardDisk.dmaChannel})",
                )
            }
            issues += ValidationIssue(
                ValidationSeverity.INFO,
                "HD controller (Wd1003/Xebec @ 0x${setup.hardDisk.ioBase.toString(16)}, " +
                    "IRQ${setup.hardDisk.irq}, DMA${setup.hardDisk.dmaChannel})" +
                    if (setup.hardDisk.useInt13Shim) " [INT13 shim]" else " [FixedDiskBios]",
            )
            if (!setup.hardDisk.secondImagePath.isNullOrBlank()) {
                issues += ValidationIssue(ValidationSeverity.INFO, "Second fixed disk (0x81) configured")
            }
        } else {
            issues += ValidationIssue(ValidationSeverity.INFO, "No hard disk controller")
        }

        val hasBootFloppy = setup.floppy.enabled && setup.floppy.driveImages.isNotEmpty()
        val hasBootHd = setup.hardDisk.enabled && !setup.hardDisk.imagePath.isNullOrBlank()
        when {
            !hasBootFloppy && !hasBootHd ->
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "No boot media — enable a floppy drive or hard disk controller",
                )
            !hasBootFloppy && hasBootHd && !setup.hardDisk.bootFromDisk ->
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "No floppy drives; enable “Boot from hard disk” on the HD controller",
                )
        }

        if (setup.graphics == GraphicsAdapter.NONE) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "No graphics adapter — POST may stop on missing video",
            )
        }

        val mb = setup.motherboard
        if (mb.baseMemoryKb !in MotherboardConfig.MIN_MEMORY_KB..MotherboardConfig.MAX_MEMORY_KB) {
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "Base memory must be ${MotherboardConfig.MIN_MEMORY_KB}..${MotherboardConfig.MAX_MEMORY_KB} KB",
            )
        } else {
            issues += ValidationIssue(
                ValidationSeverity.INFO,
                "Motherboard: ${mb.cpu.wireName}, ${mb.baseMemoryKb} KB" +
                    (if (mb.mathCoprocessor) ", 8087" else "") +
                    ", video=${mb.initialVideo.name}",
            )
        }
        if (mb.mathCoprocessor) {
            issues += ValidationIssue(
                ValidationSeverity.INFO,
                "8087 reported present (software numeric coprocessor enabled)",
            )
        }
        if (mb.postLoop) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "POST loop mode enabled — machine will re-run diagnostics continuously",
            )
        }
        if (setup.graphics == GraphicsAdapter.CGA &&
            mb.initialVideo != InitialVideoMode.CGA_40x25 &&
            mb.initialVideo != InitialVideoMode.CGA_80x25
        ) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "CGA adapter selected but SW1 initial video is ${mb.initialVideo.name}",
            )
        }

        val claims = mutableListOf<ResourceClaim>()
        claims += MotherboardResources.claims(
            graphics = setup.graphics,
            enableCom1 = setup.enableCom1,
            floppy = setup.floppy,
            hardDisk = setup.hardDisk,
        )

        val enabledCards = setup.cards.filter { it.enabled }
        for (sel in enabledCards) {
            val jar = java.io.File(sel.jarPath)
            if (!jar.isFile) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "Card JAR not found: ${sel.jarPath}",
                )
                continue
            }
            val cfg = sel.effectiveConfig()
            for (field in sel.factory.descriptor().fields) {
                val raw = cfg[field.key] ?: continue
                when (field.type) {
                    com.trugath.k8086.api.ConfigFieldType.IRQ -> {
                        val v = raw.toIntOrNull()
                        val min = field.min
                        val max = field.max
                        if (v == null || (min != null && v < min) || (max != null && v > max)) {
                            issues += ValidationIssue(
                                ValidationSeverity.ERROR,
                                "${sel.factory.descriptor().name}: IRQ '${field.key}' out of range ($raw)",
                            )
                        }
                    }
                    com.trugath.k8086.api.ConfigFieldType.DMA -> {
                        val v = raw.toIntOrNull()
                        if (v == null || v !in 0..3) {
                            issues += ValidationIssue(
                                ValidationSeverity.ERROR,
                                "${sel.factory.descriptor().name}: DMA '${field.key}' must be 0..3 ($raw)",
                            )
                        }
                    }
                    else -> Unit
                }
            }
            try {
                claims += sel.factory.resourceClaims(cfg)
            } catch (e: Exception) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "${sel.factory.descriptor().name}: cannot compute resources (${e.message})",
                )
            }
        }

        for (i in claims.indices) {
            for (j in i + 1 until claims.size) {
                val a = claims[i]
                val b = claims[j]
                if (a.owner == b.owner) continue
                if (!a.overlaps(b)) continue
                val msg = "${a.describe()} claimed by both '${a.owner}' and '${b.owner}'"
                val sev = when (a.kind) {
                    ResourceKind.IRQ -> ValidationSeverity.WARNING
                    else -> ValidationSeverity.ERROR
                }
                issues += ValidationIssue(sev, msg)
            }
        }

        if (enabledCards.isEmpty()) {
            issues += ValidationIssue(ValidationSeverity.INFO, "No expansion cards enabled")
        }

        return ValidationReport(issues)
    }
}
