package com.trugath.k8086

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trugath.k8086.cpu.Emulator8086
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
import java.util.zip.GZIPInputStream

/**
 * State-level runner for SingleStepTests/8086 hardware vectors.
 *
 * The emulator does not model the 8086 prefetch queue or bus cycles, so this validates
 * architectural registers, flags, IP, and all RAM locations listed by each vector.
 */
@Tag("single-step-8086")
class SingleStep8086Test {
    private val mapper = ObjectMapper()

    @Test
    fun hardwareGeneratedInstructionVectors() {
        assumeTrue(
            System.getProperty(ENABLED_PROPERTY) == "true",
            "Run with ./gradlew :k8086-emulator:singleStep8086Test",
        )

        val corpus = File("testdata/8086/v1")
        check(corpus.isDirectory) {
            "8086 corpus is missing. Run: git submodule update --init --recursive"
        }
        val metadata = mapper.readTree(File(corpus, "metadata.json"))
        val requested = System.getProperty(OPCODES_PROPERTY)
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::uppercase)
            .toSet()
        val limit = System.getProperty(LIMIT_PROPERTY).toInt().coerceAtLeast(1)
        val maxFailures = System.getProperty(FAILURES_PROPERTY).toInt().coerceAtLeast(1)

        val files = corpus.listFiles { file -> file.name.endsWith(".json.gz") }
            .orEmpty()
            .sortedBy(File::getName)
            .filter { file ->
                val stem = file.name.removeSuffix(".json.gz").uppercase()
                (requested.isEmpty() || stem in requested || stem.substringBefore('.') in requested) &&
                    metadataStatus(metadata, stem) in RUNNABLE_STATUSES
            }

        check(files.isNotEmpty()) {
            "No runnable vectors matched opcodes ${requested.ifEmpty { setOf("<all>") }}"
        }

        val failures = mutableListOf<String>()
        var executed = 0
        for (file in files) {
            streamTests(file, limit) { vector ->
                if (failures.size >= maxFailures) return@streamTests false
                executed++
                runVector(file.name, vector, metadata)?.let(failures::add)
                true
            }
            if (failures.size >= maxFailures) break
        }

        if (failures.isNotEmpty()) {
            fail<Nothing>(
                buildString {
                    append("SingleStepTests/8086: ${failures.size} failure(s) after $executed vectors")
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

    private fun runVector(fileName: String, vector: JsonNode, metadata: JsonNode): String? {
        val initial = vector.required("initial")
        val final = vector.required("final")
        val initialRegs = initial.required("regs")
        val expectedRegs = linkedMapOf<String, Int>()
        REGISTER_IDS.keys.forEach { name ->
            expectedRegs[name] = initialRegs.required(name).asInt() and 0xFFFF
        }
        expectedRegs["ip"] = initialRegs.required("ip").asInt() and 0xFFFF
        initialRegs["flags"]?.let { expectedRegs["flags"] = it.asInt() and 0xFFFF }
        final["regs"]?.properties()?.forEach { (name, value) ->
            expectedRegs[name] = value.asInt() and 0xFFFF
        }

        val expectedRam = linkedMapOf<Int, Int>()
        readRam(initial["ram"]).forEach { (address, value) -> expectedRam[address] = value }
        readRam(final["ram"]).forEach { (address, value) -> expectedRam[address] = value }

        val cpu = Emulator8086()
        cpu.conventionalMemoryEnd = 0xA0000
        REGISTER_IDS.forEach { (name, id) ->
            cpu.setReg16(id, initialRegs.required(name).asInt())
        }
        cpu.setIp(initialRegs.required("ip").asInt())
        cpu.setFlagsValue(initialRegs.required("flags").asInt())
        readRam(initial["ram"]).forEach { (address, value) ->
            cpu.writePhysByte(address and ADDRESS_MASK, value)
        }

        val bytes = vector.required("bytes").map(JsonNode::asInt)
        val initialCs = initialRegs.required("cs").asInt() and 0xFFFF
        val initialIp = initialRegs.required("ip").asInt() and 0xFFFF
        bytes.forEachIndexed { index, value ->
            val offset = (initialIp + index) and 0xFFFF
            val address = ((initialCs shl 4) + offset) and ADDRESS_MASK
            cpu.writePhysByte(address, value)
        }
        val steps = semanticStepCount(bytes)
        repeat(steps) { step ->
            if (!cpu.executeSingleInstruction()) {
                return failureHeader(fileName, vector) +
                    "\nexecution stopped at semantic step $step, CS:IP=" +
                    "${hex16(cpu.getReg16(REG_CS))}:${hex16(cpu.getIp())}"
            }
        }

        val opcodeStem = fileName.removeSuffix(".json.gz").uppercase()
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

        // Divide/#DE (and similar) may push flags containing undefined bits. When the
        // vector took an interrupt (SP decreased by 6), apply flags-mask to that word.
        val initialSp = initialRegs.required("sp").asInt() and 0xFFFF
        val finalSp = expectedRegs.getValue("sp")
        val finalSs = expectedRegs.getValue("ss")
        val pushedFlagsAddr = if (((initialSp - finalSp) and 0xFFFF) == 6) {
            ((finalSs shl 4) + ((finalSp + 4) and 0xFFFF)) and ADDRESS_MASK
        } else {
            -1
        }
        val pushedFlagsAddrHi = if (pushedFlagsAddr >= 0) {
            ((finalSs shl 4) + ((finalSp + 5) and 0xFFFF)) and ADDRESS_MASK
        } else {
            -1
        }

        if (pushedFlagsAddr >= 0 && flagsMask != 0xFFFF) {
            val expectedWord =
                (expectedRam[pushedFlagsAddr] ?: 0) or ((expectedRam[pushedFlagsAddrHi] ?: 0) shl 8)
            val actualWord =
                cpu.readPhysByte(pushedFlagsAddr) or (cpu.readPhysByte(pushedFlagsAddrHi) shl 8)
            if ((actualWord and flagsMask) != (expectedWord and flagsMask)) {
                mismatches += "ram[flags@${hex20(pushedFlagsAddr)}] " +
                    "expected=${hex16(expectedWord)} actual=${hex16(actualWord)} mask=${hex16(flagsMask)}"
            }
        }

        expectedRam.forEach { (address, expected) ->
            if (address == pushedFlagsAddr || address == pushedFlagsAddrHi) {
                if (flagsMask != 0xFFFF) return@forEach
            }
            val actual = cpu.readPhysByte(address and ADDRESS_MASK)
            if (actual != expected) {
                mismatches += "ram[${hex20(address)}] expected=${hex8(expected)} actual=${hex8(actual)}"
            }
        }
        return if (mismatches.isEmpty()) null else {
            failureHeader(fileName, vector) + "\n" + mismatches.joinToString("\n")
        }
    }

    private fun streamTests(file: File, limit: Int, consume: (JsonNode) -> Boolean) {
        GZIPInputStream(file.inputStream().buffered()).use { gzip ->
            mapper.factory.createParser(gzip).use { parser ->
                check(parser.nextToken()?.isStructStart == true) { "Expected JSON array in $file" }
                var count = 0
                while (count < limit && parser.nextToken()?.isStructStart == true) {
                    val vector: JsonNode = mapper.readTree(parser)
                    count++
                    if (!consume(vector)) return
                }
            }
        }
    }

    private fun semanticStepCount(bytes: List<Int>): Int {
        var prefixes = 0
        while (prefixes < bytes.size && bytes[prefixes] in PREFIXES) prefixes++
        return prefixes + 1
    }

    private fun readRam(node: JsonNode?): List<Pair<Int, Int>> {
        if (node == null || !node.isArray) return emptyList()
        return node.map { pair ->
            (pair[0].asInt() and ADDRESS_MASK) to (pair[1].asInt() and 0xFF)
        }
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

    private fun failureHeader(fileName: String, vector: JsonNode): String =
        "$fileName #${vector["test_num"]?.asInt() ?: vector["idx"]?.asInt() ?: -1} " +
            "${vector["name"]?.asText() ?: "<unnamed>"} " +
            "hash=${vector["test_hash"]?.asText() ?: vector["hash"]?.asText() ?: "<none>"}"

    private fun JsonNode.required(name: String): JsonNode =
        get(name) ?: error("Missing '$name' in single-step vector")

    private fun hex8(value: Int) = "%02X".format(value and 0xFF)
    private fun hex16(value: Int) = "%04X".format(value and 0xFFFF)
    private fun hex20(value: Int) = "%05X".format(value and ADDRESS_MASK)

    companion object {
        private const val ENABLED_PROPERTY = "k8086.singleStep.enabled"
        private const val OPCODES_PROPERTY = "k8086.singleStep.opcodes"
        private const val LIMIT_PROPERTY = "k8086.singleStep.limit"
        private const val FAILURES_PROPERTY = "k8086.singleStep.failures"
        private const val ADDRESS_MASK = 0xFFFFF

        private val RUNNABLE_STATUSES = setOf("normal", "alias", "undocumented")
        private val PREFIXES = setOf(0x26, 0x2E, 0x36, 0x3E, 0xF0, 0xF2, 0xF3)
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
