package com.trugath.k8086.math

internal enum class RoundingMode {
    HALF_EVEN,
    FLOOR,
    DOWN,
}

internal class MathContext(
    val precision: Int,
    val roundingMode: RoundingMode,
)

internal expect class BigInteger : Comparable<BigInteger> {
    fun shiftLeft(n: Int): BigInteger
    fun shiftRight(n: Int): BigInteger
    fun add(other: BigInteger): BigInteger
    fun subtract(other: BigInteger): BigInteger
    fun multiply(other: BigInteger): BigInteger
    fun divide(other: BigInteger): BigInteger
    fun divideAndRemainder(other: BigInteger): Array<BigInteger>
    fun remainder(other: BigInteger): BigInteger
    fun mod(other: BigInteger): BigInteger
    fun and(other: BigInteger): BigInteger
    fun or(other: BigInteger): BigInteger
    fun not(): BigInteger
    fun signum(): Int
    fun testBit(n: Int): Boolean
    fun bitLength(): Int
    fun toLong(): Long
    fun toInt(): Int
    fun toDouble(): Double
    operator fun plus(other: BigInteger): BigInteger
    operator fun minus(other: BigInteger): BigInteger
    operator fun times(other: BigInteger): BigInteger
    operator fun div(other: BigInteger): BigInteger
    operator fun rem(other: BigInteger): BigInteger
    operator fun unaryMinus(): BigInteger
    override operator fun compareTo(other: BigInteger): Int

    companion object {
        val ZERO: BigInteger
        val ONE: BigInteger
        val TEN: BigInteger
        fun valueOf(value: Long): BigInteger
    }
}

internal expect class BigDecimal : Comparable<BigDecimal> {
    constructor(value: String)
    constructor(value: BigInteger)
    fun add(other: BigDecimal): BigDecimal
    fun subtract(other: BigDecimal): BigDecimal
    fun multiply(other: BigDecimal): BigDecimal
    fun divide(other: BigDecimal, mc: MathContext): BigDecimal
    fun pow(n: Int): BigDecimal
    fun negate(): BigDecimal
    fun abs(): BigDecimal
    fun signum(): Int
    fun setScale(scale: Int, roundingMode: RoundingMode): BigDecimal
    fun toBigInteger(): BigInteger
    fun remainder(other: BigDecimal): BigDecimal
    fun scaleByPowerOfTen(n: Int): BigDecimal
    fun intValueExact(): Int
    fun toDouble(): Double
    operator fun plus(other: BigDecimal): BigDecimal
    operator fun minus(other: BigDecimal): BigDecimal
    operator fun times(other: BigDecimal): BigDecimal
    override operator fun compareTo(other: BigDecimal): Int

    companion object {
        val ZERO: BigDecimal
        val ONE: BigDecimal
        fun valueOf(value: Long): BigDecimal
        fun valueOf(value: Double): BigDecimal
    }
}
