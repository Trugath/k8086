package com.trugath.k8086

import com.trugath.k8086.cpu.Emulator80386
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_BP
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.REG_FS
import com.trugath.k8086.cpu.REG_GS
import com.trugath.k8086.cpu.REG_SI
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import java.io.File

/**
 * State-level runner for SingleStepTests/80386 `v1_ex_real_mode` hardware vectors.
 *
 * Vectors terminate with HALT (0xF4). The core models functional instruction
 * prefetch; captured bus-cycle timing is not compared.
 * Physical RAM writes use a 24-bit address mask (386EX capture width); guest RAM
 * above the emulator image (`0x110000`) is skipped like the 80286 runner.
 *
 * Opcode metadata / undefined-flag masks come from `80386.csv` (replaces
 * `metadata.json` from earlier SingleStep suites).
 */
@Tag("single-step-80386")
class SingleStep80386Test {
    @Test
    fun hardwareGeneratedInstructionVectors() {
        assumeTrue(
            System.getProperty(ENABLED_PROPERTY) == "true",
            "Run with ./gradlew :k8086-emulator:singleStep80386Test",
        )

        val corpus = File("testdata/80386/v1_ex_real_mode")
        check(corpus.isDirectory) {
            "80386 corpus is missing. Run: git submodule update --init --recursive"
        }
        val csvMeta = loadCsvMetadata(File("testdata/80386/80386.csv"))
        val revoked = loadRevocations(File("testdata/80386/revocation_list.txt")) +
            loadRevocations(File("testdata/80386-local-revocations.txt"))
        val requested = System.getProperty(OPCODES_PROPERTY)
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::uppercase)
            .toSet()
        val limit = System.getProperty(LIMIT_PROPERTY).toInt().coerceAtLeast(1)
        val maxFailures = System.getProperty(FAILURES_PROPERTY).toInt().coerceAtLeast(1)

        val files = corpus.listFiles { file ->
            file.name.endsWith(".MOO.gz", ignoreCase = true) ||
                file.name.endsWith(".moo.gz", ignoreCase = true)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .filter { file ->
                val stem = stemOf(file.name)
                (requested.isEmpty() ||
                    stem in requested ||
                    stripSizePrefixes(stem) in requested ||
                    stem.substringBefore('.') in requested) &&
                    csvMeta.isRunnable(stripSizePrefixes(stem))
            }

        check(files.isNotEmpty()) {
            "No runnable vectors matched opcodes ${requested.ifEmpty { setOf("<all>") }}"
        }

        val failures = mutableListOf<String>()
        var executed = 0
        for (file in files) {
            val stem = stemOf(file.name)
            val contents = MooReader.readGzipFile(file.inputStream())
            check(contents.cpuName.contains("386", ignoreCase = true)) {
                "${file.name}: expected 386 MOO cpu id, got '${contents.cpuName}'"
            }
            val flagsMask = csvMeta.flagsCompareMask(stripSizePrefixes(stem))
            for (vector in contents.vectors.take(limit)) {
                if (failures.size >= maxFailures) break
                if (vector.hash != null && vector.hash in revoked) continue
                // These have individually named tests below, with a separate
                // architectural oracle or an explicit unresolved-state skip.
                if (vector.hash in KNOWN_EX_HASHES) continue
                executed++
                runVector(file.name, vector, definedFlagsMask(vector, flagsMask))?.let(failures::add)
            }
            if (failures.size >= maxFailures) break
        }

        if (failures.isNotEmpty()) {
            fail<Nothing>(
                buildString {
                    append("SingleStepTests/80386: ${failures.size} failure(s) after $executed vectors")
                    appendLine()
                    failures.forEach {
                        appendLine()
                        appendLine(it)
                    }
                },
            )
        }
        check(executed > 0) { "No vectors executed" }
    }

    @TestFactory
    fun known386ExCaptureDifferences(): List<DynamicTest> {
        assumeTrue(System.getProperty(ENABLED_PROPERTY) == "true")
        val requested = System.getProperty(OPCODES_PROPERTY).split(',')
            .map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val hardware = System.getProperty("k8086.singleStep.386ExHardware") == "true"
        // Known fixtures are always checked in full, independently of sampling.
        // The opcode filter still applies, so focused runs remain focused.
        val captures = KNOWN_EX_CAPTURES.filter {
            val stem = stemOf(it.file)
            requested.isEmpty() || stem in requested || stripSizePrefixes(stem) in requested ||
                stem.substringBefore('.') in requested
        }
        val files = captures.map { it.file }.distinct().associateWith {
            MooReader.readGzipFile(File("testdata/80386/v1_ex_real_mode", it).inputStream()).vectors
        }
        val metadata = loadCsvMetadata(File("testdata/80386/80386.csv"))
        return captures.map { capture ->
            DynamicTest.dynamicTest("${capture.file} #${capture.index}: ${capture.reason}") {
                val vector = files.getValue(capture.file).single { it.idx == capture.index }
                check(vector.hash == capture.hash) { "Known capture changed; re-evaluate its classification" }
                if (!hardware && capture.dividend == null) {
                    assumeTrue(false, "Unresolved 386EX REMAPCFG initial state; run singleStep386ExHardwareTest")
                }
                if (!hardware) {
                    check(vector.exception == null)
                    check(vector.initial.regs.getValue("eax").toShort().toInt() == capture.dividend)
                    check(capture.dividend!! / capture.divisor!! !in -128..127)
                }
                val flags = metadata.flagsCompareMask(stripSizePrefixes(stemOf(capture.file)))
                assertNull(runVector(capture.file, vector, definedFlagsMask(vector, flags), !hardware))
            }
        }
    }

    private fun definedFlagsMask(vector: MooReader.Vector, metadataMask: Int): Int {
        var opcodeIndex = 0
        var dword = false
        var address32 = false
        while (opcodeIndex < vector.bytes.size) {
            when (vector.bytes[opcodeIndex]) {
                0x66 -> dword = true
                0x67 -> address32 = true
                0x26, 0x2E, 0x36, 0x3E, 0x64, 0x65, 0xF0, 0xF2, 0xF3 -> Unit
                else -> break
            }
            opcodeIndex++
        }
        val opcode = vector.bytes.getOrNull(opcodeIndex) ?: return metadataMask
        if (opcode !in listOf(0xC0, 0xC1, 0xD0, 0xD1, 0xD2, 0xD3)) return metadataMask
        val modrm = vector.bytes.getOrNull(opcodeIndex + 1) ?: return metadataMask
        val operation = (modrm shr 3) and 7
        val count = when (opcode) {
            0xD0, 0xD1 -> 1
            0xD2, 0xD3 -> vector.initial.regs.getValue("ecx") and 31
            else -> {
                val mod = modrm shr 6
                val rm = modrm and 7
                var immediateIndex = opcodeIndex + 2
                if (mod != 3 && address32 && rm == 4) {
                    val sib = vector.bytes.getOrNull(immediateIndex++) ?: return metadataMask
                    if (mod == 0 && (sib and 7) == 5) immediateIndex += 4
                }
                immediateIndex += when (mod) {
                    0 -> if (address32 && rm == 5) 4 else if (!address32 && rm == 6) 2 else 0
                    1 -> 1
                    2 -> if (address32) 4 else 2
                    else -> 0
                }
                (vector.bytes.getOrNull(immediateIndex) ?: return metadataMask) and 31
            }
        }
        if (count == 0) return 0xFFFF // Zero counts preserve every flag, including AF and OF.
        // CSV masks conservatively exclude flags across all counts, and label
        // /6 as legacy SETMO(C). On the 386 /6 is the SHL alias.
        var mask = if (operation < 4) 0xFFFF else 0xFFEF // AF is undefined for shifts.
        // Intel SAL/SAR/SHL/SHR and rotate specifications: OF is undefined
        // for multi-bit operations; SHL/SHR CF is undefined at/above the width.
        if (count > 1) mask = mask and 0x800.inv()
        val width = if (opcode == 0xC0 || opcode == 0xD0 || opcode == 0xD2) 8 else if (dword) 32 else 16
        if ((operation == 4 || operation == 5 || operation == 6) && count >= width) {
            mask = mask and 1.inv()
        }
        return mask
    }

    private fun runVector(
        fileName: String,
        vector: MooReader.Vector,
        flagsMask: Int,
        architecturalDivideError: Boolean = false,
    ): String? {
        val initialRegs = vector.initial.regs
        val expectedRegs = linkedMapOf<String, Int>()
        COMPARE_REGS.forEach { name ->
            initialRegs[name]?.let { expectedRegs[name] = it }
        }
        vector.final.regs.forEach { (name, value) ->
            if (name in COMPARE_REGS) expectedRegs[name] = value
        }

        val expectedRam = linkedMapOf<Int, Int>()
        vector.initial.ram.forEach { (address, value) -> expectedRam[address and ADDRESS_MASK] = value }
        vector.final.ram.forEach { (address, value) -> expectedRam[address and ADDRESS_MASK] = value }

        val cpu = Emulator80386()
        var unexpectedDivideError = false
        var observedException: Int? = null
        if (vector.exception == null &&
            (fileName.contains("F6.7.") || fileName.contains("F7.7."))
        ) {
            cpu.interruptTrace = { number, _, _ ->
                if (number == 0) unexpectedDivideError = true
                observedException = number
            }
        }
        cpu.conventionalMemoryEnd = 0xA0000
        cpu.allowRomWrites = true
        applyRegs(cpu, initialRegs)

        vector.initial.ram.forEach { (address, value) ->
            writePhys(cpu, address, value)
        }

        val initialCs = (initialRegs.getValue("cs") and 0xFFFF)
        val initialIp = (initialRegs.getValue("eip") and 0xFFFF)
        vector.bytes.forEachIndexed { index, value ->
            val offset = (initialIp + index) and 0xFFFF
            val address = ((initialCs shl 4) + offset) and ADDRESS_MASK
            writePhys(cpu, address, value)
        }

        var steps = 0
        while (!cpu.isHalted() && steps < MAX_STEPS) {
            if (!cpu.executeSingleInstruction()) {
                return failureHeader(fileName, vector) +
                    "\nexecution stopped at step $steps, CS:IP=" +
                    "${hex16(cpu.getReg16(REG_CS))}:${hex16(cpu.getIp())}"
            }
            steps++
            if (architecturalDivideError && observedException != null) {
                if (observedException != 0) {
                    return failureHeader(fileName, vector) + "\nexpected architectural #DE, got interrupt $observedException"
                }
                val sp = cpu.getReg16(REG_SP)
                val ssBase = cpu.getReg16(REG_SS) shl 4
                fun frameWord(offset: Int): Int = readPhys(cpu, ssBase + ((sp + offset) and 0xFFFF)) or
                    (readPhys(cpu, ssBase + ((sp + offset + 1) and 0xFFFF)) shl 8)
                val problems = mutableListOf<String>()
                for (name in listOf("eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "ds", "es", "fs", "gs", "ss")) {
                    if (readReg(cpu, name) != initialRegs.getValue(name)) problems += "$name changed on #DE"
                }
                val initialEsp = initialRegs.getValue("esp")
                val expectedEsp = (initialEsp and -0x10000) or ((initialEsp - 6) and 0xFFFF)
                if (cpu.getReg32(REG_SP) != expectedEsp) problems += "incorrect #DE stack pointer"
                if (frameWord(0) != initialIp || frameWord(2) != initialCs) problems += "incorrect #DE return CS:IP"
                // IDIV arithmetic flags are undefined; defined control flags
                // must still be saved in the exception frame.
                if ((frameWord(4) and 0x7700) != (initialRegs.getValue("eflags") and 0x7700)) {
                    problems += "incorrect saved #DE control flags"
                }
                return if (problems.isEmpty()) null else failureHeader(fileName, vector) + "\n" + problems.joinToString("\n")
            }
            if (unexpectedDivideError) {
                return failureHeader(fileName, vector) +
                    "\nCPU raised #DE, but the hardware capture expects successful IDIV; " +
                    "check Intel386 EX Specification Update, specification change 7"
            }
        }
        if (!cpu.isHalted()) {
            return failureHeader(fileName, vector) + "\ndid not reach HALT within $MAX_STEPS steps"
        }
        if (architecturalDivideError) return failureHeader(fileName, vector) + "\nexpected architectural #DE, reached HALT"

        val mismatches = mutableListOf<String>()
        expectedRegs.forEach { (name, expected) ->
            val actual = readReg(cpu, name)
            val mask = when (name) {
                "eflags" -> flagsMask
                "cs", "ds", "es", "fs", "gs", "ss" -> 0xFFFF
                "eip" -> 0xFFFF
                else -> -1
            }
            if ((actual and mask) != (expected and mask)) {
                mismatches += "$name expected=${hex32(expected)} actual=${hex32(actual)} mask=${hex32(mask)}"
            }
        }

        val flagAddr = vector.exception?.flagAddress?.and(ADDRESS_MASK) ?: -1
        // MOO supplies a physical address, not a segment offset. A valid
        // FLAGS word may cross a physical 64 KiB boundary without wrapping.
        val flagAddrHi = if (flagAddr >= 0) (flagAddr + 1) and ADDRESS_MASK else -1
        val flagsWordMask = flagsMask and 0xFFFF

        if (flagAddr >= 0 && flagsWordMask != 0xFFFF) {
            val expectedWord =
                (expectedRam[flagAddr] ?: 0) or ((expectedRam[flagAddrHi] ?: 0) shl 8)
            val actualWord = readPhys(cpu, flagAddr) or (readPhys(cpu, flagAddrHi) shl 8)
            if ((actualWord and flagsWordMask) != (expectedWord and flagsWordMask)) {
                mismatches += "ram[flags@${hex24(flagAddr)}] " +
                    "expected=${hex16(expectedWord)} actual=${hex16(actualWord)} mask=${hex16(flagsWordMask)}"
            }
        }

        expectedRam.forEach { (address, expected) ->
            if (address == flagAddr || address == flagAddrHi) {
                if (flagsWordMask != 0xFFFF) return@forEach
            }
            if (address >= 0x110000) return@forEach
            val actual = readPhys(cpu, address)
            if (actual != expected) {
                mismatches += "ram[${hex24(address)}] expected=${hex8(expected)} actual=${hex8(actual)}"
            }
        }
        return if (mismatches.isEmpty()) null else {
            failureHeader(fileName, vector) + "\n" + mismatches.joinToString("\n")
        }
    }

    private fun applyRegs(cpu: Emulator80386, regs: Map<String, Int>) {
        regs["eax"]?.let { cpu.setReg32Value(REG_AX, it) }
        regs["ebx"]?.let { cpu.setReg32Value(REG_BX, it) }
        regs["ecx"]?.let { cpu.setReg32Value(REG_CX, it) }
        regs["edx"]?.let { cpu.setReg32Value(REG_DX, it) }
        regs["esi"]?.let { cpu.setReg32Value(REG_SI, it) }
        regs["edi"]?.let { cpu.setReg32Value(REG_DI, it) }
        regs["ebp"]?.let { cpu.setReg32Value(REG_BP, it) }
        regs["esp"]?.let { cpu.setReg32Value(REG_SP, it) }
        regs["cs"]?.let { cpu.setReg16(REG_CS, it and 0xFFFF) }
        regs["ds"]?.let { cpu.setReg16(REG_DS, it and 0xFFFF) }
        regs["es"]?.let { cpu.setReg16(REG_ES, it and 0xFFFF) }
        regs["fs"]?.let { cpu.setReg16(REG_FS, it and 0xFFFF) }
        regs["gs"]?.let { cpu.setReg16(REG_GS, it and 0xFFFF) }
        regs["ss"]?.let { cpu.setReg16(REG_SS, it and 0xFFFF) }
        regs["eip"]?.let { cpu.setIp(it and 0xFFFF) }
        regs["eflags"]?.let { cpu.setFlagsValue(it) }
        regs["cr0"]?.let { cpu.cr0 = it }
        regs["cr3"]?.let { cpu.cr3 = it }
    }

    private fun readReg(cpu: Emulator80386, name: String): Int = when (name) {
        "eax" -> cpu.getReg32(REG_AX)
        "ebx" -> cpu.getReg32(REG_BX)
        "ecx" -> cpu.getReg32(REG_CX)
        "edx" -> cpu.getReg32(REG_DX)
        "esi" -> cpu.getReg32(REG_SI)
        "edi" -> cpu.getReg32(REG_DI)
        "ebp" -> cpu.getReg32(REG_BP)
        "esp" -> cpu.getReg32(REG_SP)
        "cs" -> cpu.getReg16(REG_CS)
        "ds" -> cpu.getReg16(REG_DS)
        "es" -> cpu.getReg16(REG_ES)
        "fs" -> cpu.getReg16(REG_FS)
        "gs" -> cpu.getReg16(REG_GS)
        "ss" -> cpu.getReg16(REG_SS)
        "eip" -> cpu.getIp()
        "eflags" -> cpu.getFlags()
        "cr0" -> cpu.cr0
        "cr3" -> cpu.cr3
        else -> error("unhandled register $name")
    }

    private fun writePhys(cpu: Emulator80386, address: Int, value: Int) {
        val addr = address and ADDRESS_MASK
        if (addr >= 0x110000) return
        cpu.writePhysByte(addr, value)
    }

    private fun readPhys(cpu: Emulator80386, address: Int): Int {
        val addr = address and ADDRESS_MASK
        if (addr >= 0x110000) return 0xFF
        return cpu.readPhysByte(addr)
    }

    private fun loadRevocations(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return file.readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private fun loadCsvMetadata(file: File): CsvMetadata {
        check(file.isFile) { "80386.csv missing next to submodule checkout" }
        val rows = linkedMapOf<String, CsvRow>()
        file.bufferedReader().useLines { lines ->
            val iter = lines.iterator()
            if (!iter.hasNext()) return CsvMetadata(rows)
            val header = iter.next().split(',')
            val idx = header.withIndex().associate { it.value to it.index }
            fun col(cols: List<String>, name: String): String {
                val i = idx[name] ?: return ""
                return cols.getOrElse(i) { "" }.trim()
            }
            while (iter.hasNext()) {
                val cols = splitCsvLine(iter.next())
                val op = col(cols, "op").uppercase()
                if (op.isEmpty()) continue
                val ex = col(cols, "ex")
                val key = if (ex.isNotEmpty()) "$op.$ex" else op
                rows[key] = CsvRow(
                    ud = col(cols, "ud") == "1",
                    pm = col(cols, "pm") == "1",
                    pf = col(cols, "pf") == "1",
                    fUmask = parseHexOrZero(col(cols, "f_umask")),
                )
            }
        }
        return CsvMetadata(rows)
    }

    /** Minimal CSV split (fields in 80386.csv do not embed commas inside quotes we need). */
    private fun splitCsvLine(line: String): List<String> = line.split(',')

    private fun parseHexOrZero(raw: String): Int {
        if (raw.isEmpty()) return 0
        val s = raw.removePrefix("0x").removePrefix("0X")
        return s.toLongOrNull(16)?.toInt() ?: 0
    }

    private fun failureHeader(fileName: String, vector: MooReader.Vector): String =
        "$fileName #${vector.idx} ${vector.name} hash=${vector.hash ?: "<none>"}"

    private fun hex8(value: Int) = "%02X".format(value and 0xFF)
    private fun hex16(value: Int) = "%04X".format(value and 0xFFFF)
    private fun hex24(value: Int) = "%06X".format(value and ADDRESS_MASK)
    private fun hex32(value: Int) = "%08X".format(value)

    private data class CsvRow(
        val ud: Boolean,
        val pm: Boolean,
        val pf: Boolean,
        val fUmask: Int,
    )

    private class CsvMetadata(private val rows: Map<String, CsvRow>) {
        fun isRunnable(stem: String): Boolean {
            val row = rowFor(stem) ?: return true
            return !row.ud && !row.pm && !row.pf
        }

        fun flagsCompareMask(stem: String): Int {
            // 80386.csv `f_umask`: bits set are defined (compare); bits clear are
            // undefined (ignore). Empty means all flags defined. High EFLAGS always
            // compared. Matches f_undef (e.g. OR umask=0xFFEF clears AF only).
            val umask = rowFor(stem)?.fUmask ?: 0
            val definedLow = if (umask == 0) 0xFFFF else (umask and 0xFFFF)
            return definedLow or -0x10000
        }

        private fun rowFor(stem: String): CsvRow? {
            rows[stem]?.let { return it }
            val base = stem.substringBefore('.')
            val ex = stem.substringAfter('.', missingDelimiterValue = "")
            if (ex.isNotEmpty()) {
                rows["$base.$ex"]?.let { return it }
            }
            return rows[base]
        }
    }

    companion object {
        private data class ExCapture(
            val file: String, val index: Int, val hash: String,
            val dividend: Int? = null, val divisor: Int? = null,
        ) {
            val reason: String get() = if (dividend == null) "unresolved EX peripheral state" else "IDIV overflow: architectural #DE"
        }

        // Exact captures only: no opcode-wide exclusions or synthesized I/O.
        // Intel386 EX Specification Update 272873-009, specification change 7.
        private val KNOWN_EX_CAPTURES = listOf(
            ExCapture("67F6.7.MOO.gz", 375, "c30147a2fb7f67459cb7a1a4c888b670edd838f5", 29726, -104),
            ExCapture("67F6.7.MOO.gz", 460, "8bddec44e46b709759d5d1550cd9fa16ab8e89fc", 24960, -67),
            ExCapture("67F6.7.MOO.gz", 516, "a06745936bac371e6afb861b5bc87be4b2dd676e", -25487, 71),
            ExCapture("F6.7.MOO.gz", 469, "8813025dcf45588bd5141eb4d7081c67d6cf3ec3", 25740, -73),
            ExCapture("F6.7.MOO.gz", 516, "c6d731127bec724d40594edc28a6ad8fdf14acbc", -25487, 71),
            ExCapture("F6.7.MOO.gz", 850, "c1392c8316c2d0532d80fb1fcb78832533093e4e", -30393, 109),
            ExCapture("F6.7.MOO.gz", 1265, "b674afe8d525fdf51217456b4a3f0b9102350cc4", 18432, -16),
            ExCapture("F6.7.MOO.gz", 1754, "13e7537c7cd00f676d17da613b4289afb93b338d", -21272, 38),
            ExCapture("F6.7.MOO.gz", 2348, "a4a926d8bbca1f281b0a479fd8c79fe3231bad34", 32189, -123),
            ExCapture("66E5.MOO.gz", 190, "29c9c6b39824411334d44d57db62504bb4807fc6"),
            ExCapture("66E5.MOO.gz", 254, "ab010dbcc86182e4ce40933f61f0864ddfd38bab"),
            ExCapture("66E5.MOO.gz", 340, "f9d9686381f6845b06163938406074037c1768a2"),
            ExCapture("66E5.MOO.gz", 348, "c923d58b0eca0d62696e03e56c9fd46ae645bee6"),
            ExCapture("66E5.MOO.gz", 422, "62f9cffa058135d552793d2e2505fc93e353ffad"),
            ExCapture("E5.MOO.gz", 422, "4fb5d80f331625dd650d55e8a1ab9d1da3b38784"),
        )
        private val KNOWN_EX_HASHES = KNOWN_EX_CAPTURES.map { it.hash }.toSet()
        private const val ENABLED_PROPERTY = "k8086.singleStep.enabled"
        private const val OPCODES_PROPERTY = "k8086.singleStep.opcodes"
        private const val LIMIT_PROPERTY = "k8086.singleStep.limit"
        private const val FAILURES_PROPERTY = "k8086.singleStep.failures"
        /** 386EX capture width in the published corpus. */
        private const val ADDRESS_MASK = 0xFFFFFF
        private const val MAX_STEPS = 10_000

        /** Registers compared at HALT. Debug regs are not modeled. */
        private val COMPARE_REGS = setOf(
            "eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp",
            "cs", "ds", "es", "fs", "gs", "ss", "eip", "eflags", "cr0", "cr3",
        )

        private fun stemOf(fileName: String): String =
            fileName.removeSuffix(".gz").removeSuffix(".MOO").removeSuffix(".moo").uppercase()

        /** Strip leading 66h/67h size-prefix nibbles from a corpus filename stem. */
        internal fun stripSizePrefixes(stem: String): String {
            var s = stem.uppercase()
            while (true) {
                s = when {
                    s.startsWith("6766") || s.startsWith("6667") -> s.drop(4)
                    s.startsWith("66") || s.startsWith("67") -> s.drop(2)
                    else -> return s
                }
            }
        }
    }
}
