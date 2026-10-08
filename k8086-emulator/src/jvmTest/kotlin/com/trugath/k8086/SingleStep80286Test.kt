package com.trugath.k8086

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trugath.k8086.cpu.Emulator80286
import com.trugath.k8086.cpu.REG_AX
import com.trugath.k8086.cpu.REG_BP
import com.trugath.k8086.cpu.REG_BX
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.cpu.REG_CX
import com.trugath.k8086.cpu.REG_DI
import com.trugath.k8086.cpu.REG_DS
import com.trugath.k8086.cpu.REG_DX
import com.trugath.k8086.cpu.REG_ES
import com.trugath.k8086.cpu.REG_SI
import com.trugath.k8086.cpu.REG_SP
import com.trugath.k8086.cpu.REG_SS
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * State-level runner for SingleStepTests/80286 `v1_real_mode` hardware vectors.
 *
 * Vectors terminate with HALT (0xF4). Prefetch queue and bus cycles are ignored.
 * Physical RAM writes use a 24-bit address mask; real-mode EA still fits the
 * existing guest map for typical vectors.
 */
@Tag("single-step-80286")
class SingleStep80286Test {
    private val mapper = ObjectMapper()

    @Test
    fun hardwareGeneratedInstructionVectors() {
        assumeTrue(
            System.getProperty(ENABLED_PROPERTY) == "true",
            "Run with ./gradlew :k8086-emulator:singleStep80286Test",
        )

        val corpus = File("testdata/80286/v1_real_mode")
        check(corpus.isDirectory) {
            "80286 corpus is missing. Run: git submodule update --init --recursive"
        }
        val metadata = mapper.readTree(File(corpus, "metadata.json"))
        val revoked = loadRevocations(File("testdata/80286/revocation_list.txt")) +
            loadRevocations(File("testdata/80286-local-revocations.txt"))
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
                val stem = file.name.removeSuffix(".gz").removeSuffix(".MOO").removeSuffix(".moo").uppercase()
                (requested.isEmpty() || stem in requested || stem.substringBefore('.') in requested) &&
                    metadataStatus(metadata, stem) in RUNNABLE_STATUSES
            }

        check(files.isNotEmpty()) {
            "No runnable vectors matched opcodes ${requested.ifEmpty { setOf("<all>") }}"
        }

        val failures = mutableListOf<String>()
        var executed = 0
        for (file in files) {
            val stem = file.name.removeSuffix(".gz").removeSuffix(".MOO").removeSuffix(".moo").uppercase()
            val vectors = MooReader.readGzip(file.inputStream())
            for (vector in vectors.take(limit)) {
                if (failures.size >= maxFailures) break
                if (vector.hash != null && vector.hash in revoked) continue
                executed++
                runVector(file.name, stem, vector, metadata)?.let(failures::add)
            }
            if (failures.size >= maxFailures) break
        }

        if (failures.isNotEmpty()) {
            fail<Nothing>(
                buildString {
                    append("SingleStepTests/80286: ${failures.size} failure(s) after $executed vectors")
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

    private fun runVector(
        fileName: String,
        opcodeStem: String,
        vector: MooReader.Vector,
        metadata: JsonNode,
    ): String? {
        val initialRegs = vector.initial.regs
        val expectedRegs = linkedMapOf<String, Int>()
        REGISTER_IDS.keys.forEach { name ->
            expectedRegs[name] = initialRegs.getValue(name) and 0xFFFF
        }
        expectedRegs["ip"] = initialRegs.getValue("ip") and 0xFFFF
        initialRegs["flags"]?.let { expectedRegs["flags"] = it and 0xFFFF }
        vector.final.regs.forEach { (name, value) ->
            expectedRegs[name] = value and 0xFFFF
        }

        val expectedRam = linkedMapOf<Int, Int>()
        vector.initial.ram.forEach { (address, value) -> expectedRam[address and ADDRESS_MASK] = value }
        vector.final.ram.forEach { (address, value) -> expectedRam[address and ADDRESS_MASK] = value }

        val cpu = Emulator80286()
        cpu.conventionalMemoryEnd = 0xA0000
        REGISTER_IDS.forEach { (name, id) ->
            cpu.setReg16(id, initialRegs.getValue(name))
        }
        cpu.setIp(initialRegs.getValue("ip"))
        cpu.setFlagsValue(initialRegs.getValue("flags"))
        vector.initial.ram.forEach { (address, value) ->
            writePhys(cpu, address, value)
        }

        val initialCs = initialRegs.getValue("cs") and 0xFFFF
        val initialIp = initialRegs.getValue("ip") and 0xFFFF
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
        }
        if (!cpu.isHalted()) {
            return failureHeader(fileName, vector) + "\ndid not reach HALT within $MAX_STEPS steps"
        }

        val flagsMask = metadataFlagsMask(metadata, opcodeStem)
        val mismatches = mutableListOf<String>()
        expectedRegs.forEach { (name, expected) ->
            val actual = when (name) {
                "ip" -> cpu.getIp()
                "flags" -> cpu.getFlags()
                else -> cpu.getReg16(REGISTER_IDS.getValue(name))
            }
            val mask = if (name == "flags") flagsMask else 0xFFFF
            if ((actual and mask) != (expected and mask)) {
                mismatches += "$name expected=${hex16(expected)} actual=${hex16(actual)} mask=${hex16(mask)}"
            }
        }

        val flagAddr = vector.exception?.flagAddress?.and(ADDRESS_MASK) ?: -1
        val flagAddrHi = if (flagAddr >= 0) (flagAddr and 0xFF0000) or (((flagAddr and 0xFFFF) + 1) and 0xFFFF) else -1

        if (flagAddr >= 0 && flagsMask != 0xFFFF) {
            val expectedWord =
                (expectedRam[flagAddr] ?: 0) or ((expectedRam[flagAddrHi] ?: 0) shl 8)
            val actualWord = readPhys(cpu, flagAddr) or (readPhys(cpu, flagAddrHi) shl 8)
            if ((actualWord and flagsMask) != (expectedWord and flagsMask)) {
                mismatches += "ram[flags@${hex24(flagAddr)}] " +
                    "expected=${hex16(expectedWord)} actual=${hex16(actualWord)} mask=${hex16(flagsMask)}"
            }
        }

        expectedRam.forEach { (address, expected) ->
            if (address == flagAddr || address == flagAddrHi) {
                if (flagsMask != 0xFFFF) return@forEach
            }
            // Skip register-file region used by the emulator's internal mapping.
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

    private fun writePhys(cpu: Emulator80286, address: Int, value: Int) {
        val addr = address and ADDRESS_MASK
        if (addr >= 0x110000) return
        cpu.writePhysByte(addr, value)
    }

    private fun readPhys(cpu: Emulator80286, address: Int): Int {
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

    private fun metadataStatus(metadata: JsonNode, stem: String): String {
        val node = metadataNode(metadata, stem)
        return node?.get("status")?.asText()
            ?: metadata["opcodes"]?.get(stem.substringBefore('.'))?.get("status")?.asText()
            ?: "undefined"
    }

    private fun metadataFlagsMask(metadata: JsonNode, stem: String): Int {
        val base = metadata["opcodes"]?.get(stem.substringBefore('.'))
        val specific = metadataNode(metadata, stem)
        return specific?.get("flags-mask")?.asInt()
            ?: base?.get("flags-mask")?.asInt()
            ?: 0xFFFF
    }

    private fun metadataNode(metadata: JsonNode, stem: String): JsonNode? {
        val parts = stem.split('.', limit = 2)
        val base = metadata["opcodes"]?.get(parts[0]) ?: return null
        return if (parts.size == 2) base["reg"]?.get(parts[1]) ?: base else base
    }

    private fun failureHeader(fileName: String, vector: MooReader.Vector): String =
        "$fileName #${vector.idx} ${vector.name} hash=${vector.hash ?: "<none>"}"

    private fun hex8(value: Int) = "%02X".format(value and 0xFF)
    private fun hex16(value: Int) = "%04X".format(value and 0xFFFF)
    private fun hex24(value: Int) = "%06X".format(value and ADDRESS_MASK)

    companion object {
        private const val ENABLED_PROPERTY = "k8086.singleStep.enabled"
        private const val OPCODES_PROPERTY = "k8086.singleStep.opcodes"
        private const val LIMIT_PROPERTY = "k8086.singleStep.limit"
        private const val FAILURES_PROPERTY = "k8086.singleStep.failures"
        private const val ADDRESS_MASK = 0xFFFFFF
        private const val MAX_STEPS = 10_000

        private val RUNNABLE_STATUSES = setOf("normal", "alias", "undocumented")
        private val REGISTER_IDS = linkedMapOf(
            "ax" to REG_AX,
            "bx" to REG_BX,
            "cx" to REG_CX,
            "dx" to REG_DX,
            "sp" to REG_SP,
            "bp" to REG_BP,
            "si" to REG_SI,
            "di" to REG_DI,
            "es" to REG_ES,
            "cs" to REG_CS,
            "ss" to REG_SS,
            "ds" to REG_DS,
        )
    }
}
