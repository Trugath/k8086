package com.trugath.k8086

import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.config.MachineSetup
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
        val setup = StartWizard.show(cliNetworks) ?: run {
            println("Setup cancelled.")
            return
        }
        runSetup(u18, u19, setup)
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
    println("  Floppy drives optional (0–4). Repeat --floppy for B:/C:/D:.")
    println("  Hard disk optional; @prefix boots from it (enables HD controller).")
    println("  --headless       No CGA window; full-speed (realtime pacing off).")
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
    val options = MachineOptions(
        showVideo = !parsed.headless,
        enableAudio = !parsed.headless,
        exitOnClose = !parsed.headless,
        realtime = !parsed.headless,
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
    )
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
