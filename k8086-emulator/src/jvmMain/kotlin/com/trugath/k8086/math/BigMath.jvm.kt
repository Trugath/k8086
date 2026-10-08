package com.trugath.k8086.math

import java.math.BigInteger as JBigInteger
import java.math.BigDecimal as JBigDecimal
import java.math.MathContext as JMathContext
import java.math.RoundingMode as JRoundingMode

internal actual class BigInteger internal constructor(internal val j: JBigInteger) : Comparable<BigInteger> {
    actual fun shiftLeft(n: Int) = BigInteger(j.shiftLeft(n))
    actual fun shiftRight(n: Int) = BigInteger(j.shiftRight(n))
    actual fun add(other: BigInteger) = BigInteger(j.add(other.j))
    actual fun subtract(other: BigInteger) = BigInteger(j.subtract(other.j))
    actual fun multiply(other: BigInteger) = BigInteger(j.multiply(other.j))
    actual fun divide(other: BigInteger) = BigInteger(j.divide(other.j))
    actual fun divideAndRemainder(other: BigInteger): Array<BigInteger> {
        val qr = j.divideAndRemainder(other.j)
        return arrayOf(BigInteger(qr[0]), BigInteger(qr[1]))
    }
    actual fun remainder(other: BigInteger) = BigInteger(j.remainder(other.j))
    actual fun mod(other: BigInteger) = BigInteger(j.mod(other.j))
    actual fun and(other: BigInteger) = BigInteger(j.and(other.j))
    actual fun or(other: BigInteger) = BigInteger(j.or(other.j))
    actual fun not() = BigInteger(j.not())
    actual fun signum(): Int = j.signum()
    actual fun testBit(n: Int): Boolean = j.testBit(n)
    actual fun bitLength(): Int = j.bitLength()
    actual fun toLong(): Long = j.toLong()
    actual fun toInt(): Int = j.toInt()
    actual fun toDouble(): Double = j.toDouble()
    actual operator fun plus(other: BigInteger) = add(other)
    actual operator fun minus(other: BigInteger) = subtract(other)
    actual operator fun times(other: BigInteger) = multiply(other)
    actual operator fun div(other: BigInteger) = divide(other)
    actual operator fun rem(other: BigInteger) = remainder(other)
    actual operator fun unaryMinus() = BigInteger(j.negate())
    actual override operator fun compareTo(other: BigInteger): Int = j.compareTo(other.j)
    override fun equals(other: Any?): Boolean = other is BigInteger && j == other.j
    override fun hashCode(): Int = j.hashCode()
    override fun toString(): String = j.toString()

    actual companion object {
        actual val ZERO = BigInteger(JBigInteger.ZERO)
        actual val ONE = BigInteger(JBigInteger.ONE)
        actual val TEN = BigInteger(JBigInteger.TEN)
        actual fun valueOf(value: Long) = BigInteger(JBigInteger.valueOf(value))
    }
}

internal actual class BigDecimal internal constructor(internal val j: JBigDecimal) : Comparable<BigDecimal> {
    actual constructor(value: String) : this(JBigDecimal(value))
    actual constructor(value: BigInteger) : this(JBigDecimal(value.j))
    actual fun add(other: BigDecimal) = BigDecimal(j.add(other.j))
    actual fun subtract(other: BigDecimal) = BigDecimal(j.subtract(other.j))
    actual fun multiply(other: BigDecimal) = BigDecimal(j.multiply(other.j))
    actual fun divide(other: BigDecimal, mc: MathContext) = BigDecimal(j.divide(other.j, mc.toJava()))
    actual fun pow(n: Int) = BigDecimal(j.pow(n))
    actual fun negate() = BigDecimal(j.negate())
    actual fun abs() = BigDecimal(j.abs())
    actual fun signum(): Int = j.signum()
    actual fun setScale(scale: Int, roundingMode: RoundingMode) =
        BigDecimal(j.setScale(scale, roundingMode.toJava()))
    actual fun toBigInteger() = BigInteger(j.toBigInteger())
    actual fun remainder(other: BigDecimal) = BigDecimal(j.remainder(other.j))
    actual fun scaleByPowerOfTen(n: Int) = BigDecimal(j.scaleByPowerOfTen(n))
    actual fun intValueExact(): Int = j.intValueExact()
    actual fun toDouble(): Double = j.toDouble()
    actual operator fun plus(other: BigDecimal) = add(other)
    actual operator fun minus(other: BigDecimal) = subtract(other)
    actual operator fun times(other: BigDecimal) = multiply(other)
    actual override operator fun compareTo(other: BigDecimal): Int = j.compareTo(other.j)
    override fun equals(other: Any?): Boolean = other is BigDecimal && j == other.j
    override fun hashCode(): Int = j.hashCode()

    actual companion object {
        actual val ZERO = BigDecimal(JBigDecimal.ZERO)
        actual val ONE = BigDecimal(JBigDecimal.ONE)
        actual fun valueOf(value: Long) = BigDecimal(JBigDecimal.valueOf(value))
        actual fun valueOf(value: Double) = BigDecimal(JBigDecimal.valueOf(value))
    }
}

private fun RoundingMode.toJava(): JRoundingMode = when (this) {
    RoundingMode.HALF_EVEN -> JRoundingMode.HALF_EVEN
    RoundingMode.FLOOR -> JRoundingMode.FLOOR
    RoundingMode.DOWN -> JRoundingMode.DOWN
}

private fun MathContext.toJava(): JMathContext = JMathContext(precision, roundingMode.toJava())
