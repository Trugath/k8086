package com.trugath.k8086

import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.config.CpuClocks
import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.config.InitialVideoMode
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.isa.CardSpec
import com.trugath.k8086.net.NetworkRegistry
import com.trugath.k8086.protocol.SystemRomDefaults
import com.trugath.k8086.ui.StartWizard
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

/** Shared virtual networks for CLI / start-wizard sessions. */
private val cliNetworks = NetworkRegistry()

fun main(args: Array<String>) {
    val (u18, u19) = SystemRomDefaults.resolve()

    if (!File(u18).exists() || !File(u19).exists()) {
        System.err.println("ROM BIOS not found. Expected:")
        System.err.println("  $u18")
        System.err.println("  $u19")
        System.err.println("Shipped defaults are ${SystemRomDefaults.U18_RELATIVE} / ${SystemRomDefaults.U19_RELATIVE}.")
        System.err.println("Override with K8086_U18_ROM / K8086_U19_ROM environment variables.")
        exitProcess(2)
    }

    println("k8086 - IBM 5155 Portable PC emulator")

    if (args.isEmpty()) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available for the start wizard; pass disk/card arguments.")
            printUsage()
            exitProcess(2)
        }
        val wizard = StartWizard.show(cliNetworks) ?: run {
            println("Setup cancelled.")
            return
        }
        runSetup(wizard.u18RomPath, wizard.u19RomPath, wizard.setup)
        return
    }

    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        printUsage()
        exitProcess(2)
    }

    if (!parsed.quiet) {
        printUsage()
    }
    val code = runCli(u18, u19, parsed)
    exitProcess(code)
}

private fun printUsage() {
    println("Usage: k8086 [floppy.img] [[@]harddisk.img] [--floppy path]... [--card path.jar[,k=v...]]...")
    println("         [--headless] [--serial-log path] [--parallel-log path] [--quiet]")
    println("         [--cga-expect text] [--max-instructions N]")
    println("         [--cpu 8088|8086|80286] [--mhz N] [--no-cga] [--initial-video cga80|special]")
    println("  Floppy drives optional (0–4). Repeat --floppy for B:/C:/D:.")
    println("  Hard disk optional; @prefix boots from it (enables HD controller).")
    println("  --headless       No display window; full-speed (realtime pacing off).")
    println("  --cpu MODEL      Motherboard CPU (default: 8088).")
    println("  --mhz N          Guest clock MHz (8088: 4.77; 286: 6/8/10/12.5; default per CPU).")
    println("  --no-cga         Disable built-in CGA (use with a VGA/EGA ISA card).")
    println("  --initial-video M  SW1 video: cga80 (default) or special (card BIOS).")
    println("  --serial-log P   Append COM1 TX bytes to file P.")
    println("  --parallel-log P Append LPT1 captured bytes to file P.")
    println("  --cga-expect T   Stop successfully when CGA text contains T.")
    println("  --max-instructions N  Stop after N instructions (default: unlimited).")
    println("  --turbo          Free-run CPU (ignore realtime pacing); useful for fast boots.")
    println("  --quiet          Suppress usage banner.")
    println("  --floppy-int13-shim     Host floppy INT 13h shim (default: guest FDC owns INT 13h).")
    println("  --no-floppy-int13-shim  Guest BIOS owns floppy INT 13h (FDC); same as default.")
    println("  --no-hd-int13-bios      Guest C800 Fixed Disk ROM owns HD INT 13h (default).")
    println("  --hd-int13-bios [true]  Host FixedDiskBios owns HD INT 13h (opt-in).")
    println("  Run with no arguments for the setup wizard.")
    println("  Env K8086_FLOPPY_INT13_SHIM=1 enables the host floppy INT 13h shim.")
    println("  Env K8086_HD_INT13_BIOS=1 enables the host Fixed Disk BIOS.")
}

private fun runSetup(u18: String, u19: String, setup: MachineSetup) {
    val machine = Machine(u18, u19, MachineOptions.fromSetup(setup))
    machine.nicAttach = { networkId, mac -> cliNetworks.attachNic(networkId, mac) }
    try {
        for (c in setup.cards.filter { it.enabled }) {
            println("ISA card: ${c.factory.descriptor().name} (${c.jarPath})")
        }
        machine.boot(setup)
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

private fun runCli(u18: String, u19: String, parsed: CliArgs): Int {
    val hdRaw = parsed.hardDisk
    val bootHd = hdRaw?.startsWith("@") == true
    val hdPath = hdRaw?.removePrefix("@")
    val initialVideo = parsed.initialVideo
        ?: if (parsed.graphics == GraphicsAdapter.NONE) {
            InitialVideoMode.SPECIAL_OR_NONE
        } else {
            InitialVideoMode.CGA_80x25
        }
    val motherboard = MotherboardConfig(
        cpu = parsed.cpu,
        cpuMhz = parsed.cpuMhz ?: CpuClocks.defaultMhz(parsed.cpu),
        initialVideo = initialVideo,
    )
    val options = MachineOptions(
        motherboard = motherboard,
        graphics = parsed.graphics,
        showVideo = !parsed.headless,
        enableAudio = !parsed.headless,
        exitOnClose = !parsed.headless,
        realtime = !parsed.headless,
        realtimeCpuHz = motherboard.clockHz(),
        serialLogPath = parsed.serialLog,
        parallelLogPath = parsed.parallelLog,
        floppy = FloppyControllerConfig(
            enabled = true,
            driveImages = parsed.floppies,
            useInt13Shim = parsed.floppyInt13Shim,
        ),
        hardDisk = HardDiskControllerConfig(
            enabled = hdPath != null,
            imagePath = hdPath,
            bootFromDisk = bootHd,
            useHostFixedDiskBios = parsed.hdInt13Bios,
        ),
        cgaExpect = parsed.cgaExpect,
        turbo = parsed.turbo,
    )
    val machine = Machine(u18, u19, options)
    machine.nicAttach = { networkId, mac -> cliNetworks.attachNic(networkId, mac) }
    return try {
        if (parsed.cards.isNotEmpty()) {
            val cards = machine.loadCards(parsed.cards)
            for (c in cards) println("Loaded ISA card: ${c.name} (${c.id})")
        }
        machine.boot(
            floppyImages = parsed.floppies,
            hardDiskImage = parsed.hardDisk,
            maxInstructions = parsed.maxInstructions,
        )
        val reason = machine.stopReason()
        if (reason == RunStopReason.GUEST_FAULT) {
            val cs = machine.cpu.getReg16(com.trugath.k8086.cpu.REG_CS)
            val ip = machine.cpu.getIp()
            System.err.println(
                "GUEST_FAULT at CS:IP=%04X:%04X".format(cs, ip),
            )
        }
        if (parsed.cgaExpect != null && reason != RunStopReason.CGA_EXPECT) {
            System.err.println(
                "CGA expect '${parsed.cgaExpect}' not seen (stop=$reason). Screen:\n" +
                    machine.cgaScreenText(),
            )
            return 1
        }
        when (reason) {
            RunStopReason.SHUTDOWN_PORT, RunStopReason.CGA_EXPECT -> 0
            RunStopReason.NONE, RunStopReason.HOST_STOP, RunStopReason.MAX_INSTRUCTIONS -> 0
            RunStopReason.GUEST_FAULT -> 1
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        e.printStackTrace()
        1
    }
}

internal data class CliArgs(
    val floppies: List<String> = emptyList(),
    val hardDisk: String? = null,
    val cards: List<CardSpec> = emptyList(),
    val headless: Boolean = false,
    val serialLog: String? = null,
    val parallelLog: String? = null,
    val quiet: Boolean = false,
    val cgaExpect: String? = null,
    val maxInstructions: Long = Long.MAX_VALUE,
    val turbo: Boolean = false,
    val floppyInt13Shim: Boolean = false,
    val hdInt13Bios: Boolean = false,
    val cpu: CpuModel = CpuModel.I8088,
    /** Null = [CpuClocks.defaultMhz] for [cpu]. */
    val cpuMhz: Double? = null,
    val graphics: GraphicsAdapter = GraphicsAdapter.CGA,
    val initialVideo: InitialVideoMode? = null,
) {
    val floppy: String? get() = floppies.firstOrNull()
}

internal fun parseArgs(args: Array<String>): CliArgs {
    val floppies = mutableListOf<String>()
    var hardDisk: String? = null
    val cards = mutableListOf<CardSpec>()
    var headless = false
    var serialLog: String? = null
    var parallelLog: String? = null
    var quiet = false
    var cgaExpect: String? = null
    var maxInstructions = Long.MAX_VALUE
    var turbo = false
    var floppyInt13Shim = when (System.getenv("K8086_FLOPPY_INT13_SHIM")?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
    var hdInt13Bios = when (System.getenv("K8086_HD_INT13_BIOS")?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
    var cpu = CpuModel.I8088
    var cpuMhz: Double? = null
    var graphics = GraphicsAdapter.CGA
    var initialVideo: InitialVideoMode? = null
    var positional = 0
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--floppy" -> {
                val path = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--floppy requires an image path")
                floppies += path
                i += 2
            }
            a.startsWith("--floppy=") -> {
                floppies += a.removePrefix("--floppy=")
                i += 1
            }
            a == "--card" -> {
                val spec = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--card requires a jar path")
                cards += parseCardSpec(spec)
                i += 2
            }
            a.startsWith("--card=") -> {
                cards += parseCardSpec(a.removePrefix("--card="))
                i += 1
            }
            a == "--cpu" -> {
                val v = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--cpu requires 8088|8086|80286")
                val parsed = parseCpuArgWithOptionalMhz(v)
                cpu = parsed.first
                if (parsed.second != null) cpuMhz = parsed.second
                i += 2
            }
            a.startsWith("--cpu=") -> {
                val parsed = parseCpuArgWithOptionalMhz(a.removePrefix("--cpu="))
                cpu = parsed.first
                if (parsed.second != null) cpuMhz = parsed.second
                i += 1
            }
            a == "--mhz" -> {
                val v = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--mhz requires a number")
                cpuMhz = v.toDoubleOrNull()
                    ?: throw IllegalArgumentException("bad --mhz: $v")
                require(cpuMhz!! > 0.0) { "--mhz must be positive" }
                i += 2
            }
            a.startsWith("--mhz=") -> {
                val v = a.removePrefix("--mhz=")
                cpuMhz = v.toDoubleOrNull()
                    ?: throw IllegalArgumentException("bad --mhz: $v")
                require(cpuMhz!! > 0.0) { "--mhz must be positive" }
                i += 1
            }
            a == "--no-cga" -> {
                graphics = GraphicsAdapter.NONE
                i += 1
            }
            a == "--initial-video" -> {
                val v = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--initial-video requires cga80|special")
                initialVideo = parseInitialVideoArg(v)
                i += 2
            }
            a.startsWith("--initial-video=") -> {
                initialVideo = parseInitialVideoArg(a.removePrefix("--initial-video="))
                i += 1
            }
            a == "--headless" -> {
                headless = true
                i += 1
            }
            a == "--serial-log" -> {
                val path = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--serial-log requires a path")
                serialLog = path
                i += 2
            }
            a.startsWith("--serial-log=") -> {
                serialLog = a.removePrefix("--serial-log=")
                i += 1
            }
            a == "--parallel-log" -> {
                val path = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--parallel-log requires a path")
                parallelLog = path
                i += 2
            }
            a.startsWith("--parallel-log=") -> {
                parallelLog = a.removePrefix("--parallel-log=")
                i += 1
            }
            a == "--cga-expect" -> {
                cgaExpect = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--cga-expect requires text")
                i += 2
            }
            a.startsWith("--cga-expect=") -> {
                cgaExpect = a.removePrefix("--cga-expect=")
                i += 1
            }
            a == "--max-instructions" -> {
                val n = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--max-instructions requires a number")
                maxInstructions = n.toLongOrNull()
                    ?: throw IllegalArgumentException("bad --max-instructions: $n")
                i += 2
            }
            a.startsWith("--max-instructions=") -> {
                val n = a.removePrefix("--max-instructions=")
                maxInstructions = n.toLongOrNull()
                    ?: throw IllegalArgumentException("bad --max-instructions: $n")
                i += 1
            }
            a == "--turbo" -> {
                turbo = true
                i += 1
            }
            a == "--quiet" -> {
                quiet = true
                i += 1
            }
            a == "--no-floppy-int13-shim" -> {
                floppyInt13Shim = false
                i += 1
            }
            a == "--floppy-int13-shim" -> {
                val v = args.getOrNull(i + 1)
                    ?: throw IllegalArgumentException("--floppy-int13-shim requires true/false")
                floppyInt13Shim = v == "1" || v.equals("true", ignoreCase = true)
                i += 2
            }
            a.startsWith("--floppy-int13-shim=") -> {
                val v = a.removePrefix("--floppy-int13-shim=")
                floppyInt13Shim = v == "1" || v.equals("true", ignoreCase = true)
                i += 1
            }
            a == "--no-hd-int13-bios" -> {
                hdInt13Bios = false
                i += 1
            }
            a == "--hd-int13-bios" -> {
                val v = args.getOrNull(i + 1)
                if (v != null && !v.startsWith("-") &&
                    (v == "0" || v == "1" || v.equals("true", ignoreCase = true) ||
                        v.equals("false", ignoreCase = true))
                ) {
                    hdInt13Bios = v == "1" || v.equals("true", ignoreCase = true)
                    i += 2
                } else {
                    hdInt13Bios = true
                    i += 1
                }
            }
            a.startsWith("--hd-int13-bios=") -> {
                val v = a.removePrefix("--hd-int13-bios=")
                hdInt13Bios = v == "1" || v.equals("true", ignoreCase = true)
                i += 1
            }
            a.startsWith("-") -> throw IllegalArgumentException("Unknown option: $a")
            positional == 0 && floppies.isEmpty() -> {
                floppies += a
                positional = 1
                i += 1
            }
            hardDisk == null -> {
                hardDisk = a
                positional = 2
                i += 1
            }
            else -> throw IllegalArgumentException("Unexpected argument: $a")
        }
    }
    require(floppies.size <= 4) { "At most 4 floppy drives" }
    return CliArgs(
        floppies, hardDisk, cards, headless, serialLog, parallelLog, quiet,
        cgaExpect, maxInstructions, turbo, floppyInt13Shim, hdInt13Bios,
        cpu, cpuMhz, graphics, initialVideo,
    )
}

/** Parse `--cpu 80286` or legacy `--cpu 80286@10`. */
internal fun parseCpuArgWithOptionalMhz(value: String): Pair<CpuModel, Double?> {
    val raw = value.trim()
    val at = raw.lastIndexOf('@')
    if (at > 0) {
        val mhz = raw.substring(at + 1).toDoubleOrNull()
            ?: throw IllegalArgumentException("bad --cpu MHz suffix: $value")
        require(mhz > 0.0) { "--cpu MHz must be positive" }
        return parseCpuArg(raw.substring(0, at)) to mhz
    }
    return parseCpuArg(raw) to null
}

internal fun parseCpuArg(value: String): CpuModel = when (value.trim().lowercase()) {
    "8088", "i8088" -> CpuModel.I8088
    "8086", "i8086" -> CpuModel.I8086
    "80286", "i80286", "286" -> CpuModel.I80286
    else -> throw IllegalArgumentException("bad --cpu: $value (want 8088|8086|80286)")
}

internal fun parseInitialVideoArg(value: String): InitialVideoMode = when (value.trim().lowercase()) {
    "cga80", "cga", "cga_80x25" -> InitialVideoMode.CGA_80x25
    "cga40", "cga_40x25" -> InitialVideoMode.CGA_40x25
    "special", "none", "vga", "ega" -> InitialVideoMode.SPECIAL_OR_NONE
    "mda" -> InitialVideoMode.MDA_80x25
    else -> throw IllegalArgumentException("bad --initial-video: $value (want cga80|special)")
}

internal fun parseCardSpec(spec: String): CardSpec {
    val parts = spec.split(',')
    val jar = parts.first().trim()
    require(jar.isNotEmpty()) { "Empty --card path" }
    val config = linkedMapOf<String, String>()
    for (p in parts.drop(1)) {
        val eq = p.indexOf('=')
        require(eq > 0) { "Card config must be key=value, got: $p" }
        config[p.substring(0, eq).trim()] = p.substring(eq + 1).trim()
    }
    return CardSpec(jar, config)
}
