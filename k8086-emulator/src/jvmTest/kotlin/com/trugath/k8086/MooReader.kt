package com.trugath.k8086

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Minimal reader for SingleStepTests `MOO` chunk files (state fields only).
 * Cycle traces are skipped. Format documented at https://github.com/dbalsom/moo
 *
 * Supports 16-bit register chunks ([REGS]) used by 80286 corpora and 32-bit
 * chunks ([RG32]/REGS]) used by 80386 corpora (cpu id contains `"386"`).
 */
internal object MooReader {
    data class Vector(
        val idx: Int,
        val name: String,
        val bytes: List<Int>,
        val initial: CpuState,
        val final: CpuState,
        val hash: String?,
        val exception: ExceptionInfo?,
    )

    data class CpuState(
        val regs: Map<String, Int>,
        val ram: List<Pair<Int, Int>>,
    )

    data class ExceptionInfo(val number: Int, val flagAddress: Int)

    data class FileContents(
        val cpuName: String,
        val vectors: List<Vector>,
    )

    private val REG_ORDER_16 = listOf(
        "ax", "bx", "cx", "dx", "cs", "ss", "ds", "es",
        "sp", "bp", "si", "di", "ip", "flags",
    )

    private val REG_ORDER_386 = listOf(
        "cr0", "cr3", "eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp",
        "cs", "ds", "es", "fs", "gs", "ss", "eip", "eflags", "dr6", "dr7",
    )

    fun readGzip(input: InputStream): List<Vector> = readGzipFile(input).vectors

    fun readGzipFile(input: InputStream): FileContents =
        GZIPInputStream(input.buffered()).use { gzip -> readFile(gzip) }

    fun read(input: InputStream): List<Vector> = readFile(input).vectors

    fun readFile(input: InputStream): FileContents {
        val data = input.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        check(readTag(buf) == "MOO ") { "Not a MOO file" }
        val headerLen = buf.int
        val headerEnd = buf.position() + headerLen
        val cpuName = if (headerLen >= 12) {
            val raw = ByteArray(4)
            buf.position(buf.position() + 8)
            buf.get(raw)
            String(raw, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        } else {
            ""
        }
        buf.position(headerEnd)

        val is386 = cpuName.contains("386", ignoreCase = true)
        val tests = mutableListOf<Vector>()
        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val length = buf.int
            val end = buf.position() + length
            if (tag == "TEST") {
                tests += decodeTest(buf.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length), is386)
            }
            buf.position(end)
        }
        return FileContents(cpuName, tests)
    }

    private fun decodeTest(buf: ByteBuffer, is386: Boolean): Vector {
        val idx = buf.int
        var name = "<unnamed>"
        var bytes = emptyList<Int>()
        var initial = CpuState(emptyMap(), emptyList())
        var final = CpuState(emptyMap(), emptyList())
        var hash: String? = null
        var exception: ExceptionInfo? = null

        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val length = buf.int
            val end = buf.position() + length
            val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length)
            when (tag) {
                "NAME" -> {
                    val n = slice.int
                    val raw = ByteArray(n)
                    slice.get(raw)
                    name = String(raw, Charsets.UTF_8)
                }
                "BYTS" -> {
                    val count = slice.int
                    bytes = List(count) { slice.get().toInt() and 0xFF }
                }
                "INIT" -> initial = decodeCpuState(slice, is386)
                "FINA" -> final = decodeCpuState(slice, is386)
                "HASH" -> {
                    val raw = ByteArray(length)
                    slice.get(raw)
                    hash = raw.joinToString("") { "%02x".format(it) }
                }
                "EXCP" -> {
                    val number = slice.get().toInt() and 0xFF
                    val flagAddress = slice.int
                    exception = ExceptionInfo(number, flagAddress)
                }
            }
            buf.position(end)
        }
        return Vector(idx, name, bytes, initial, final, hash, exception)
    }

    private fun decodeCpuState(buf: ByteBuffer, is386: Boolean): CpuState {
        var regs = emptyMap<String, Int>()
        var ram = emptyList<Pair<Int, Int>>()
        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val length = buf.int
            val end = buf.position() + length
            val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length)
            when (tag) {
                "REGS", "RG32" -> regs = decodeRegs(slice, is386 || tag == "RG32")
                "RAM " -> {
                    val count = slice.int
                    ram = List(count) {
                        val addr = slice.int
                        val value = slice.get().toInt() and 0xFF
                        addr to value
                    }
                }
                // EA32 / QUEU ignored for state-level runners
            }
            buf.position(end)
        }
        return CpuState(regs, ram)
    }

    private fun decodeRegs(slice: ByteBuffer, is386: Boolean): Map<String, Int> {
        val map = linkedMapOf<String, Int>()
        if (is386) {
            var bitmask = slice.int
            REG_ORDER_386.forEachIndexed { i, name ->
                if (bitmask and (1 shl i) != 0) {
                    map[name] = slice.int
                }
            }
        } else {
            var bitmask = slice.short.toInt() and 0xFFFF
            REG_ORDER_16.forEachIndexed { i, name ->
                if (bitmask and (1 shl i) != 0) {
                    map[name] = slice.short.toInt() and 0xFFFF
                }
            }
        }
        return map
    }

    private fun readTag(buf: ByteBuffer): String {
        val raw = ByteArray(4)
        buf.get(raw)
        return String(raw, Charsets.US_ASCII)
    }
}
