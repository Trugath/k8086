package com.trugath.k8086

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Minimal reader for SingleStepTests/80286 `MOO` chunk files (state fields only).
 * Cycle traces are skipped. Format documented at https://github.com/dbalsom/moo
 * and converted by `tools/moo2json.py` in the 80286 corpus.
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

    private val REG_ORDER = listOf(
        "ax", "bx", "cx", "dx", "cs", "ss", "ds", "es",
        "sp", "bp", "si", "di", "ip", "flags",
    )

    fun readGzip(input: InputStream): List<Vector> =
        GZIPInputStream(input.buffered()).use { gzip -> read(gzip) }

    fun read(input: InputStream): List<Vector> {
        val data = input.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        check(readTag(buf) == "MOO ") { "Not a MOO file" }
        val headerLen = buf.int
        buf.position(buf.position() + headerLen)

        val tests = mutableListOf<Vector>()
        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val length = buf.int
            val end = buf.position() + length
            if (tag == "TEST") {
                tests += decodeTest(buf.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length))
            }
            buf.position(end)
        }
        return tests
    }

    private fun decodeTest(buf: ByteBuffer): Vector {
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
                "INIT" -> initial = decodeCpuState(slice)
                "FINA" -> final = decodeCpuState(slice)
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
                // CYCL and unknown chunks ignored
            }
            buf.position(end)
        }
        return Vector(idx, name, bytes, initial, final, hash, exception)
    }

    private fun decodeCpuState(buf: ByteBuffer): CpuState {
        var regs = emptyMap<String, Int>()
        var ram = emptyList<Pair<Int, Int>>()
        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val length = buf.int
            val end = buf.position() + length
            val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length)
            when (tag) {
                "REGS" -> {
                    var bitmask = slice.short.toInt() and 0xFFFF
                    val map = linkedMapOf<String, Int>()
                    REG_ORDER.forEachIndexed { i, name ->
                        if (bitmask and (1 shl i) != 0) {
                            map[name] = slice.short.toInt() and 0xFFFF
                        }
                    }
                    regs = map
                }
                "RAM " -> {
                    val count = slice.int
                    ram = List(count) {
                        val addr = slice.int
                        val value = slice.get().toInt() and 0xFF
                        addr to value
                    }
                }
            }
            buf.position(end)
        }
        return CpuState(regs, ram)
    }

    private fun readTag(buf: ByteBuffer): String {
        val raw = ByteArray(4)
        buf.get(raw)
        return String(raw, Charsets.US_ASCII)
    }
}
