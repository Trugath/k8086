package com.trugath.k8086.math

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign

/**
 * Wasm BigInteger: magnitude as little-endian 32-bit limbs, [sign] in {-1,0,1}.
 */
internal actual class BigInteger internal constructor(
    internal val mag: IntArray,
    internal val sign: Int,
) : Comparable<BigInteger> {
    actual fun shiftLeft(n: Int): BigInteger {
        if (sign == 0 || n == 0) return this
        if (n < 0) return shiftRight(-n)
        val limbShift = n ushr 5
        val bitShift = n and 31
        val out = IntArray(mag.size + limbShift + 1)
        if (bitShift == 0) {
            mag.copyInto(out, limbShift)
        } else {
            var carry = 0
            for (i in mag.indices) {
                val v = mag[i]
                out[i + limbShift] = (v shl bitShift) or carry
                carry = v ushr (32 - bitShift)
            }
            out[mag.size + limbShift] = carry
        }
        return fromLimbs(out, sign)
    }

    actual fun shiftRight(n: Int): BigInteger {
        if (sign == 0 || n == 0) return this
        if (n < 0) return shiftLeft(-n)
        val limbShift = n ushr 5
        if (limbShift >= mag.size) return ZERO
        val bitShift = n and 31
        val out = IntArray(mag.size - limbShift)
        if (bitShift == 0) {
            mag.copyInto(out, 0, limbShift)
        } else {
            for (i in out.indices) {
                val cur = mag[i + limbShift]
                val next = if (i + limbShift + 1 < mag.size) mag[i + limbShift + 1] else 0
                out[i] = (cur ushr bitShift) or (next shl (32 - bitShift))
            }
        }
        return fromLimbs(out, sign)
    }

    actual fun add(other: BigInteger): BigInteger = when {
        sign == 0 -> other
        other.sign == 0 -> this
        sign == other.sign -> fromLimbs(addMag(mag, other.mag), sign)
        else -> {
            val cmp = compareMag(mag, other.mag)
            when {
                cmp == 0 -> ZERO
                cmp > 0 -> fromLimbs(subMag(mag, other.mag), sign)
                else -> fromLimbs(subMag(other.mag, mag), other.sign)
            }
        }
    }

    actual fun subtract(other: BigInteger) = add(-other)
    actual fun multiply(other: BigInteger): BigInteger {
        if (sign == 0 || other.sign == 0) return ZERO
        val out = IntArray(mag.size + other.mag.size)
        for (i in mag.indices) {
            var carry = 0L
            val a = mag[i].toUInt().toLong()
            var k = i
            for (j in other.mag.indices) {
                val prod = a * other.mag[j].toUInt().toLong() + out[k].toUInt().toLong() + carry
                out[k] = prod.toInt()
                carry = prod ushr 32
                k++
            }
            while (carry != 0L && k < out.size) {
                val prod = out[k].toUInt().toLong() + carry
                out[k] = prod.toInt()
                carry = prod ushr 32
                k++
            }
        }
        return fromLimbs(out, sign * other.sign)
    }

    actual fun divide(other: BigInteger) = divRem(other).first
    actual fun divideAndRemainder(other: BigInteger): Array<BigInteger> {
        val qr = divRem(other)
        return arrayOf(qr.first, qr.second)
    }
    actual fun remainder(other: BigInteger) = divRem(other).second
    actual fun mod(other: BigInteger): BigInteger {
        val r = remainder(other)
        return if (r.sign < 0) r.add(other.abs()) else r
    }

    actual fun and(other: BigInteger) = bitwise(other) { a, b -> a and b }
    actual fun or(other: BigInteger) = bitwise(other) { a, b -> a or b }
    actual fun not(): BigInteger = add(ONE).unaryMinus()

    actual fun signum(): Int = sign
    actual fun testBit(n: Int): Boolean {
        if (n < 0) return false
        if (sign >= 0) {
            val limb = n ushr 5
            if (limb >= mag.size) return false
            return ((mag[limb] ushr (n and 31)) and 1) != 0
        }
        return twoComplementLimb(n ushr 5).let { ((it ushr (n and 31)) and 1) != 0 }
    }

    actual fun bitLength(): Int {
        if (sign == 0) return 0
        var i = mag.size - 1
        while (i > 0 && mag[i] == 0) i--
        val topBits = 32 - mag[i].countLeadingZeroBits()
        val len = i * 32 + topBits
        return if (sign > 0) len else subtract(ONE).unaryMinus().bitLength()
    }

    actual fun toLong(): Long {
        var v = 0UL
        val n = minOf(2, mag.size)
        for (i in n - 1 downTo 0) {
            v = (v shl 32) or mag[i].toUInt().toULong()
        }
        val signed = v.toLong()
        return if (sign < 0) -signed else signed
    }

    actual fun toInt(): Int = toLong().toInt()
    actual fun toDouble(): Double = toLong().toDouble()

    actual operator fun plus(other: BigInteger) = add(other)
    actual operator fun minus(other: BigInteger) = subtract(other)
    actual operator fun times(other: BigInteger) = multiply(other)
    actual operator fun div(other: BigInteger) = divide(other)
    actual operator fun rem(other: BigInteger) = remainder(other)
    actual operator fun unaryMinus() = if (sign == 0) this else BigInteger(mag, -sign)
    actual override operator fun compareTo(other: BigInteger): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0
        val cmp = compareMag(mag, other.mag)
        return if (sign > 0) cmp else -cmp
    }

    override fun equals(other: Any?): Boolean = other is BigInteger && compareTo(other) == 0
    override fun hashCode(): Int = sign * mag.fold(1) { a, b -> 31 * a + b }

    private fun abs() = if (sign < 0) unaryMinus() else this

    private fun divRem(other: BigInteger): Pair<BigInteger, BigInteger> {
        require(other.sign != 0) { "divide by zero" }
        if (sign == 0) return ZERO to ZERO
        val cmp = compareMag(mag, other.mag)
        if (cmp < 0) return ZERO to this
        if (cmp == 0) return valueOf((sign * other.sign).toLong()) to ZERO
        var rem = fromLimbs(mag, 1)
        var quot = ZERO
        val divisor = fromLimbs(other.mag, 1)
        var s = rem.bitLength() - divisor.bitLength()
        var d = divisor.shiftLeft(s.coerceAtLeast(0))
        var qBit = ONE.shiftLeft(s.coerceAtLeast(0))
        while (s >= 0) {
            if (rem.compareTo(d) >= 0) {
                rem = rem.subtract(d)
                quot = quot.or(qBit)
            }
            d = d.shiftRight(1)
            qBit = qBit.shiftRight(1)
            s--
        }
        val qSign = if (quot.sign == 0) 0 else sign * other.sign
        val rSign = if (rem.sign == 0) 0 else sign
        return fromLimbs(quot.mag, qSign) to fromLimbs(rem.mag, rSign)
    }

    private fun bitwise(other: BigInteger, op: (Int, Int) -> Int): BigInteger {
        val n = maxOf(mag.size, other.mag.size) + 1
        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = op(twoComplementLimb(i), other.twoComplementLimb(i))
        }
        return fromTwoComplement(out)
    }

    private fun twoComplementLimb(i: Int): Int {
        if (sign >= 0) return if (i < mag.size) mag[i] else 0
        var carry = 1L
        var limb = 0
        val last = maxOf(i, mag.size - 1)
        for (k in 0..last) {
            val magLimb = if (k < mag.size) mag[k].toUInt().toLong() else 0L
            val s = (magLimb.inv() and 0xFFFFFFFFL) + carry
            carry = s ushr 32
            if (k == i) limb = s.toInt()
        }
        return if (i > last) (if (carry != 0L) carry.toInt() else -1) else limb
    }

    actual companion object {
        actual val ZERO = BigInteger(intArrayOf(0), 0)
        actual val ONE = BigInteger(intArrayOf(1), 1)
        actual val TEN = BigInteger(intArrayOf(10), 1)
        actual fun valueOf(value: Long): BigInteger {
            if (value == 0L) return ZERO
            val s = if (value < 0) -1 else 1
            val v = if (value == Long.MIN_VALUE) 1uL shl 63 else kotlin.math.abs(value).toULong()
            val low = v.toInt()
            val high = (v shr 32).toInt()
            return fromLimbs(if (high == 0) intArrayOf(low) else intArrayOf(low, high), s)
        }
    }
}

private fun fromLimbs(limbs: IntArray, sign: Int): BigInteger {
    var n = limbs.size
    while (n > 1 && limbs[n - 1] == 0) n--
    if (n == 0 || (n == 1 && limbs[0] == 0) || sign == 0) return BigInteger.ZERO
    val mag = if (n == limbs.size) limbs else limbs.copyOf(n)
    return BigInteger(mag, sign)
}

private fun addMag(a: IntArray, b: IntArray): IntArray {
    val n = maxOf(a.size, b.size)
    val out = IntArray(n + 1)
    var carry = 0L
    for (i in 0 until n) {
        val s = (if (i < a.size) a[i].toUInt().toLong() else 0L) +
            (if (i < b.size) b[i].toUInt().toLong() else 0L) + carry
        out[i] = s.toInt()
        carry = s ushr 32
    }
    out[n] = carry.toInt()
    return out
}

private fun subMag(a: IntArray, b: IntArray): IntArray {
    val out = IntArray(a.size)
    var borrow = 0L
    for (i in a.indices) {
        var s = a[i].toUInt().toLong() - (if (i < b.size) b[i].toUInt().toLong() else 0L) - borrow
        if (s < 0) {
            s += 1L shl 32
            borrow = 1
        } else {
            borrow = 0
        }
        out[i] = s.toInt()
    }
    return out
}

private fun compareMag(a: IntArray, b: IntArray): Int {
    var na = a.size
    var nb = b.size
    while (na > 1 && a[na - 1] == 0) na--
    while (nb > 1 && b[nb - 1] == 0) nb--
    if (na != nb) return na.compareTo(nb)
    for (i in na - 1 downTo 0) {
        val c = a[i].toUInt().compareTo(b[i].toUInt())
        if (c != 0) return c
    }
    return 0
}

private fun fromTwoComplement(limbs: IntArray): BigInteger {
    if (limbs.isEmpty()) return BigInteger.ZERO
    val negative = limbs.last() < 0
    if (!negative) return fromLimbs(limbs, 1)
    val out = IntArray(limbs.size)
    var carry = 1L
    for (i in limbs.indices) {
        val s = (limbs[i].inv().toLong() and 0xFFFFFFFFL) + carry
        out[i] = s.toInt()
        carry = s ushr 32
    }
    return fromLimbs(out, -1)
}

internal actual class BigDecimal private constructor(
    private val value: Double,
) : Comparable<BigDecimal> {
    actual constructor(value: String) : this(value.toDouble())
    actual constructor(value: BigInteger) : this(value.toDouble())
    actual fun add(other: BigDecimal) = BigDecimal(value + other.value)
    actual fun subtract(other: BigDecimal) = BigDecimal(value - other.value)
    actual fun multiply(other: BigDecimal) = BigDecimal(value * other.value)
    actual fun divide(other: BigDecimal, mc: MathContext) = BigDecimal(value / other.value)
    actual fun pow(n: Int) = BigDecimal(value.pow(n))
    actual fun negate() = BigDecimal(-value)
    actual fun abs() = BigDecimal(kotlin.math.abs(value))
    actual fun signum(): Int = if (value == 0.0) 0 else value.sign.toInt()
    actual fun setScale(scale: Int, roundingMode: RoundingMode): BigDecimal {
        val p = 10.0.pow(scale)
        val x = value * p
        val r = when (roundingMode) {
            RoundingMode.FLOOR -> floor(x)
            RoundingMode.DOWN -> if (x >= 0) floor(x) else kotlin.math.ceil(x)
            RoundingMode.HALF_EVEN -> kotlin.math.round(x)
        }
        return BigDecimal(r / p)
    }
    actual fun toBigInteger() = BigInteger.valueOf(floor(value).toLong())
    actual fun remainder(other: BigDecimal) = BigDecimal(value % other.value)
    actual fun scaleByPowerOfTen(n: Int) = BigDecimal(value * 10.0.pow(n))
    actual fun intValueExact(): Int {
        val i = value.toInt()
        require(i.toDouble() == floor(value) && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
        return i
    }
    actual fun toDouble(): Double = value
    actual operator fun plus(other: BigDecimal) = add(other)
    actual operator fun minus(other: BigDecimal) = subtract(other)
    actual operator fun times(other: BigDecimal) = multiply(other)
    actual override operator fun compareTo(other: BigDecimal): Int = value.compareTo(other.value)

    actual companion object {
        actual val ZERO = BigDecimal(0.0)
        actual val ONE = BigDecimal(1.0)
        actual fun valueOf(value: Long) = BigDecimal(value.toDouble())
        actual fun valueOf(value: Double) = BigDecimal(value)
    }
}
