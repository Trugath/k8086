package com.trugath.k8086.cpu.fpu

import java.math.BigInteger

/** Memory format converters for 8087 load/store operands. */
internal object FpuFormats {
    fun readU16(addr: Int, readByte: (Int) -> Int): Int =
        (readByte(addr) and 0xFF) or ((readByte(addr + 1) and 0xFF) shl 8)

    fun writeU16(addr: Int, value: Int, writeByte: (Int, Int) -> Unit) {
        writeByte(addr, value and 0xFF)
        writeByte(addr + 1, (value ushr 8) and 0xFF)
    }

    fun readU32(addr: Int, readByte: (Int) -> Int): Int {
        var result = 0
        for (i in 0 until 4) result = result or ((readByte(addr + i) and 0xFF) shl (i * 8))
        return result
    }

    fun readU64(addr: Int, readByte: (Int) -> Int): Long {
        var result = 0L
        for (i in 0 until 8) result = result or ((readByte(addr + i).toLong() and 0xFF) shl (i * 8))
        return result
    }

    fun writeUnsigned(addr: Int, value: Long, bytes: Int, writeByte: (Int, Int) -> Unit) {
        for (i in 0 until bytes) writeByte(addr + i, ((value ushr (i * 8)) and 0xFF).toInt())
    }

    fun readFloat32(addr: Int, readByte: (Int) -> Int): Ext80 =
        Ext80.fromDouble(Float.fromBits(readU32(addr, readByte)).toDouble())

    fun writeFloat32(addr: Int, value: Ext80, writeByte: (Int, Int) -> Unit) {
        val bits = value.toDouble().toFloat().toRawBits().toLong() and 0xFFFFFFFFL
        writeUnsigned(addr, bits, 4, writeByte)
    }

    fun readFloat64(addr: Int, readByte: (Int) -> Int): Ext80 =
        Ext80.fromDouble(Double.fromBits(readU64(addr, readByte)))

    fun writeFloat64(addr: Int, value: Ext80, writeByte: (Int, Int) -> Unit) {
        writeUnsigned(addr, value.toDouble().toRawBits(), 8, writeByte)
    }

    fun readFloat80(addr: Int, readByte: (Int) -> Int): Ext80 {
        var sig = 0L
        for (i in 0 until 8) {
            sig = sig or ((readByte(addr + i).toLong() and 0xFF) shl (i * 8))
        }
        val signExp = readU16(addr + 8, readByte)
        val exp = signExp and 0x7FFF
        val sign = (signExp and 0x8000) != 0
        return Ext80(sign, exp, sig)
    }

    fun writeFloat80(addr: Int, value: Ext80, writeByte: (Int, Int) -> Unit) {
        writeUnsigned(addr, value.sig, 8, writeByte)
        val expWord = (value.exp and 0x7FFF) or if (value.sign) 0x8000 else 0
        writeU16(addr + 8, expWord, writeByte)
    }

    fun readI16(addr: Int, readByte: (Int) -> Int): Ext80 =
        Ext80.fromLong(readU16(addr, readByte).toShort().toLong())

    fun readI32(addr: Int, readByte: (Int) -> Int): Ext80 =
        Ext80.fromLong(readU32(addr, readByte).toLong())

    fun readI64(addr: Int, readByte: (Int) -> Int): Ext80 =
        Ext80.fromLong(readU64(addr, readByte))

    fun writeInteger(addr: Int, value: Long, bytes: Int, writeByte: (Int, Int) -> Unit) {
        writeUnsigned(addr, value, bytes, writeByte)
    }

    fun readPackedBcd(addr: Int, readByte: (Int) -> Int): Ext80 {
        var value = BigInteger.ZERO
        var multiplier = BigInteger.ONE
        for (i in 0 until 9) {
            val b = readByte(addr + i)
            value += multiplier * BigInteger.valueOf((b and 0xF).toLong())
            multiplier *= BigInteger.TEN
            value += multiplier * BigInteger.valueOf(((b ushr 4) and 0xF).toLong())
            multiplier *= BigInteger.TEN
        }
        val negative = (readByte(addr + 9) and 0x80) != 0
        if (value.signum() == 0) return if (negative) Ext80.NEG_ZERO else Ext80.ZERO
        // Convert via long when possible, else Double.
        return if (value.bitLength() <= 63) {
            val asLong = value.toLong()
            Ext80.fromLong(if (negative) -asLong else asLong)
        } else {
            Ext80.fromDouble(value.toDouble() * if (negative) -1 else 1)
        }
    }

    fun writePackedBcd(addr: Int, input: Ext80, rc: Int, writeByte: (Int, Int) -> Unit) {
        val asLong = input.toIntegralLong(rc) ?: 0L
        var value = BigInteger.valueOf(kotlin.math.abs(asLong))
        for (i in 0 until 9) {
            val low = value.mod(BigInteger.TEN).toInt()
            value /= BigInteger.TEN
            val high = value.mod(BigInteger.TEN).toInt()
            value /= BigInteger.TEN
            writeByte(addr + i, low or (high shl 4))
        }
        writeByte(addr + 9, if (asLong < 0 || input.sign) 0x80 else 0)
    }
}
