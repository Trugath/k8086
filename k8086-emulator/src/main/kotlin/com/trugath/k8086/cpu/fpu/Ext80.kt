package com.trugath.k8086.cpu.fpu

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Intel 8087-style 80-bit extended precision value.
 *
 * Memory layout matches m80real: 64-bit significand (bit 63 = explicit integer bit),
 * 15-bit biased exponent, and a sign bit.
 */
internal data class Ext80(
    val sign: Boolean,
    val exp: Int,
    val sig: Long,
) {
    val isZero: Boolean get() = exp == 0 && sig == 0L
    val isSpecialExp: Boolean get() = exp == EXP_INF
    val isInfinity: Boolean
        get() = isSpecialExp && (sig and SIG_FRACTION_MASK) == 0L && (sig and INTEGER_BIT) != 0L
    val isNaN: Boolean get() = isSpecialExp && (sig and SIG_FRACTION_MASK) != 0L
    val isDenormal: Boolean get() = exp == 0 && sig != 0L
    val isFinite: Boolean get() = !isSpecialExp

    fun abs(): Ext80 = copy(sign = false)
    fun negate(): Ext80 = if (isNaN) this else copy(sign = !sign)

    fun tag(): Int = when {
        isZero -> TAG_ZERO
        isNaN || isInfinity || isDenormal -> TAG_SPECIAL
        else -> TAG_VALID
    }

    /** -1, 0, 1, or [CMP_UNORDERED]. */
    fun compareTo(other: Ext80): Int {
        if (isNaN || other.isNaN) return CMP_UNORDERED
        if (isZero && other.isZero) return 0
        if (sign != other.sign) return if (sign) -1 else 1
        val mag = when {
            exp != other.exp -> exp.compareTo(other.exp)
            else -> sig.toULong().compareTo(other.sig.toULong())
        }
        return if (sign) -mag else mag
    }

    fun toDouble(): Double {
        if (isZero) return if (sign) -0.0 else 0.0
        if (isNaN) return Double.NaN
        if (isInfinity) return if (sign) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        val fraction = uSig().toDouble() / TWO_POW_63
        val unbiased = if (isDenormal) 1 - BIAS else exp - BIAS
        val value = Math.scalb(fraction, unbiased)
        return if (sign) -value else value
    }

    fun toBigDecimal(): BigDecimal {
        if (isZero) return BigDecimal.ZERO
        if (isNaN || isInfinity) throw ArithmeticException("non-finite Ext80")
        val unb = if (isDenormal) 1 - BIAS else exp - BIAS
        // value = sig * 2^(unb - 63)
        val scalePow = unb - 63
        val sigBd = BigDecimal(uSig())
        return if (scalePow >= 0) {
            val v = sigBd.multiply(BigDecimal.valueOf(2).pow(scalePow))
            if (sign) v.negate() else v
        } else {
            val v = sigBd.divide(BigDecimal.valueOf(2).pow(-scalePow), MC_CONVERT)
            if (sign) v.negate() else v
        }
    }

    /**
     * Round to integral value using RC, staying in significand space (no Double).
     */
    fun roundToIntegral(rc: Int): Ext80 {
        if (!isFinite || isZero) return this
        val unb = if (isDenormal) 1 - BIAS else exp - BIAS
        // Already an integer if unbiased >= 63 (significand fully left of binary point).
        if (unb >= 63) return this
        // |x| < 1: round to 0, ±1, or -1.
        if (unb < 0) {
            val roundAway = when (rc) {
                RC_NEAR -> {
                    // |x| >= 0.5 → away from 0 to ±1; ties (|x|==0.5) → even → 0
                    if (unb < -1) false
                    else if (unb == -1) {
                        // [0.5, 1): tie at exactly 0.5 (sig==INTEGER_BIT && exp==BIAS-1)
                        if (sig == INTEGER_BIT && !isDenormal) false // tie → even 0
                        else true
                    } else true
                }
                RC_DOWN -> sign // toward -∞: negative → -1
                RC_UP -> !sign
                else -> false // chop toward 0
            }
            return when {
                !roundAway -> if (sign) NEG_ZERO else ZERO
                sign -> fromLong(-1)
                else -> ONE
            }
        }
        // Discard fractional bits: shift = 63 - unb.
        val shift = 63 - unb
        val us = uSig()
        val kept = us.shiftRight(shift)
        val fracMask = BigInteger.ONE.shiftLeft(shift) - BigInteger.ONE
        val frac = us.and(fracMask)
        val half = BigInteger.ONE.shiftLeft(shift - 1)
        val roundUp = when (rc) {
            RC_NEAR -> frac > half || (frac == half && kept.testBit(0))
            RC_DOWN -> sign && frac.signum() != 0
            RC_UP -> !sign && frac.signum() != 0
            else -> false
        }
        var integ = kept
        if (roundUp) integ = integ.add(BigInteger.ONE)
        if (integ.signum() == 0) return if (sign) NEG_ZERO else ZERO
        // Rebuild Ext80 from integer magnitude.
        val bitLen = integ.bitLength()
        val newExp = BIAS + bitLen - 1
        val newSig = integ.shiftLeft(64 - bitLen).and(MASK64)
        return Ext80(sign, newExp, newSig.toLong())
    }

    /**
     * Convert rounded integral value to a signed long, or null if out of range / non-finite.
     */
    fun toIntegralLong(rc: Int): Long? {
        if (!isFinite) return null
        val rounded = roundToIntegral(rc)
        if (rounded.isZero) return 0L
        val unb = rounded.exp - BIAS
        if (unb > 63) return null
        if (unb == 63) {
            // Only Long.MIN_VALUE fits as negative 2^63.
            if (!rounded.sign || rounded.sig != INTEGER_BIT) return null
            return Long.MIN_VALUE
        }
        val shift = 63 - unb
        val mag = unsignedLong(rounded.sig).shiftRight(shift)
        if (mag.bitLength() > 63) return null
        val asLong = mag.toLong()
        return if (rounded.sign) -asLong else asLong
    }

    fun extractExponent(): Ext80 {
        if (isZero || isNaN || isInfinity) return this
        val unbiased = if (isDenormal) {
            val lz = java.lang.Long.numberOfLeadingZeros(sig)
            (1 - BIAS - lz).toLong()
        } else {
            (exp - BIAS).toLong()
        }
        return fromLong(unbiased)
    }

    fun extractSignificand(): Ext80 {
        if (!isFinite || isZero) return this
        return copy(exp = BIAS)
    }

    private fun uSig(): BigInteger = unsignedLong(sig)

    companion object {
        const val BIAS = 16383
        const val EXP_INF = 0x7FFF
        const val INTEGER_BIT = 1L shl 63
        const val SIG_FRACTION_MASK = 0x7FFFFFFFFFFFFFFFL
        const val TAG_VALID = 0
        const val TAG_ZERO = 1
        const val TAG_SPECIAL = 2
        const val TAG_EMPTY = 3
        const val CMP_UNORDERED = 2

        const val RC_NEAR = 0
        const val RC_DOWN = 1
        const val RC_UP = 2
        const val RC_CHOP = 3

        const val PC_SINGLE = 0
        const val PC_DOUBLE = 2
        const val PC_EXTENDED = 3

        val ZERO = Ext80(false, 0, 0)
        val NEG_ZERO = Ext80(true, 0, 0)
        // Silicon ROM constants (Linux math-emu reg_constant.c / 8087).
        val ONE = Ext80(false, BIAS + 0, INTEGER_BIT)
        val PI = Ext80(false, BIAS + 1, 0xc90fdaa22168c235uL.toLong())
        val L2T = Ext80(false, BIAS + 1, 0xd49a784bcd1b8afeuL.toLong())
        val L2E = Ext80(false, BIAS + 0, 0xb8aa3b295c17f0bcuL.toLong())
        val LG2 = Ext80(false, BIAS - 2, 0x9a209a84fbcff799uL.toLong())
        val LN2 = Ext80(false, BIAS - 1, 0xb17217f7d1cf79acuL.toLong())
        val INDEFINITE = Ext80(true, EXP_INF, 0xC000_0000_0000_0000uL.toLong())
        val POS_INF = Ext80(false, EXP_INF, INTEGER_BIT)
        val NEG_INF = Ext80(true, EXP_INF, INTEGER_BIT)

        private const val TWO_POW_63 = 9.223372036854776E18
        private val MASK64 = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
        private val MC_CONVERT = MathContext(40, RoundingMode.HALF_EVEN)
        internal val MC_TRANS = MathContext(48, RoundingMode.HALF_EVEN)

        fun fromDouble(value: Double): Ext80 {
            if (value == 0.0) return if (java.lang.Double.doubleToRawLongBits(value) < 0) NEG_ZERO else ZERO
            if (value.isNaN()) return INDEFINITE
            if (value.isInfinite()) return if (value < 0) NEG_INF else POS_INF
            val sign = java.lang.Double.doubleToRawLongBits(value) < 0
            val mag = abs(value)
            val bits = mag.toRawBits()
            val dExp = ((bits ushr 52) and 0x7FF).toInt()
            val dSig = bits and 0x000F_FFFF_FFFF_FFFFL
            return if (dExp == 0) {
                if (dSig == 0L) if (sign) NEG_ZERO else ZERO
                else {
                    var s = dSig shl 11
                    var e = 1 - 1023 + BIAS
                    while (s and INTEGER_BIT == 0L) {
                        s = s shl 1
                        e--
                    }
                    Ext80(sign, e, s)
                }
            } else {
                val s = (dSig shl 11) or INTEGER_BIT
                val e = dExp - 1023 + BIAS
                Ext80(sign, e, s)
            }
        }

        fun fromLong(value: Long): Ext80 {
            if (value == 0L) return ZERO
            val sign = value < 0
            var mag = if (value == Long.MIN_VALUE) {
                BigInteger.ONE.shiftLeft(63)
            } else {
                BigInteger.valueOf(if (sign) -value else value)
            }
            val bitLen = mag.bitLength()
            val exp = BIAS + bitLen - 1
            mag = mag.shiftLeft(64 - bitLen)
            return Ext80(sign, exp, mag.toLong())
        }

        fun fromBigDecimal(value: BigDecimal, rc: Int = RC_NEAR, pc: Int = PC_EXTENDED): Ext80 {
            if (value.signum() == 0) return ZERO
            val sign = value.signum() < 0
            var mag = value.abs()
            val two = BigDecimal.valueOf(2)
            var unb = 0
            while (mag >= two) {
                mag = mag.divide(two, MC_CONVERT)
                unb++
            }
            while (mag < BigDecimal.ONE && mag.signum() != 0) {
                mag = mag.multiply(two)
                unb--
            }
            // mant in [1, 2)
            return packSignificand(sign, unb + BIAS, mag, rc, pc)
        }

        private fun packSignificand(sign: Boolean, exp: Int, mantIn1to2: BigDecimal, rc: Int, pc: Int): Ext80 {
            // significand = mant * 2^63
            val raw = mantIn1to2.multiply(BigDecimal.valueOf(2).pow(63))
            val hi = raw.setScale(0, RoundingMode.FLOOR).toBigInteger()
            val frac = raw.subtract(BigDecimal(hi))
            val sticky = if (frac.signum() != 0) BigInteger.ONE else BigInteger.ZERO
            return Ext80Math.roundPackPublic(sign, exp, hi, sticky, rc, pc).value
        }

        private fun log2Big(x: BigDecimal): BigDecimal {
            val ln = lnBig(x)
            return ln.divide(LN2_BD, MC_CONVERT)
        }

        /** Natural log for positive BigDecimal via series on reduced argument. */
        internal fun lnBig(x: BigDecimal): BigDecimal {
            require(x.signum() > 0)
            var v = x
            var exp = 0
            val two = BigDecimal.valueOf(2)
            val half = BigDecimal("0.5")
            while (v >= two) {
                v = v.divide(two, MC_TRANS)
                exp++
            }
            while (v < half) {
                v = v.multiply(two)
                exp--
            }
            val one = BigDecimal.ONE
            val t = v.subtract(one).divide(v.add(one), MC_TRANS)
            var tPow = t
            var sum = BigDecimal.ZERO
            var n = 1
            while (n < 80) {
                sum = sum.add(tPow.divide(BigDecimal.valueOf(n.toLong()), MC_TRANS))
                tPow = tPow.multiply(t).multiply(t)
                n += 2
                if (tPow.abs() < BigDecimal.ONE.scaleByPowerOfTen(-45)) break
            }
            val lnV = sum.multiply(two)
            return if (exp == 0) lnV else lnV.add(LN2_BD.multiply(BigDecimal.valueOf(exp.toLong())))
        }

        /** Precomputed ln(2) matching 8087 LN2 ROM when converted. */
        private val LN2_BD: BigDecimal = BigDecimal("0.693147180559945309417232121458176568")

        fun roundDouble(value: Double, rc: Int): Double = when (rc) {
            RC_NEAR -> Math.rint(value)
            RC_DOWN -> floor(value)
            RC_UP -> ceil(value)
            else -> if (value < 0) ceil(value) else floor(value)
        }

        fun unsignedLong(sig: Long): BigInteger {
            var x = BigInteger.ZERO
            for (i in 7 downTo 0) {
                x = x.shiftLeft(8).or(BigInteger.valueOf((sig ushr (i * 8)) and 0xFF))
            }
            return x
        }
    }
}

/** Extended-precision arithmetic with RC/PC rounding and sticky exception bits. */
internal object Ext80Math {
    data class Result(
        val value: Ext80,
        val exceptions: Int = 0,
        val c1: Boolean = false,
    )

    fun add(a: Ext80, b: Ext80, rc: Int, pc: Int): Result = addSub(a, b, false, rc, pc)
    fun sub(a: Ext80, b: Ext80, rc: Int, pc: Int): Result = addSub(a, b, true, rc, pc)

    fun mul(a: Ext80, b: Ext80, rc: Int, pc: Int): Result {
        specialMulDiv(a, b)?.let { return it }
        if (a.isZero || b.isZero) {
            return Result(if (a.sign xor b.sign) Ext80.NEG_ZERO else Ext80.ZERO)
        }
        val sign = a.sign xor b.sign
        val product = Ext80.unsignedLong(a.sig) * Ext80.unsignedLong(b.sig) // 128-bit
        var exp = a.exp + b.exp - Ext80.BIAS
        val hi: BigInteger
        val lo: BigInteger
        if (product.testBit(127)) {
            hi = product.shiftRight(64).and(MASK64)
            lo = product.and(MASK64)
            exp++
        } else {
            hi = product.shiftRight(63).and(MASK64)
            lo = product.and(BigInteger.ONE.shiftLeft(63) - BigInteger.ONE).shiftLeft(1)
        }
        return roundPack(sign, exp, hi, lo, rc, pc, denormalOperand = a.isDenormal || b.isDenormal)
    }

    fun div(a: Ext80, b: Ext80, rc: Int, pc: Int): Result {
        if (a.isNaN || b.isNaN) return Result(Ext80.INDEFINITE, IE)
        if (b.isZero) {
            if (a.isZero) return Result(Ext80.INDEFINITE, IE)
            return Result(if (a.sign xor b.sign) Ext80.NEG_INF else Ext80.POS_INF, ZE)
        }
        if (a.isZero) return Result(if (a.sign xor b.sign) Ext80.NEG_ZERO else Ext80.ZERO)
        if (a.isInfinity && b.isInfinity) return Result(Ext80.INDEFINITE, IE)
        if (a.isInfinity) return Result(if (a.sign xor b.sign) Ext80.NEG_INF else Ext80.POS_INF)
        if (b.isInfinity) return Result(if (a.sign xor b.sign) Ext80.NEG_ZERO else Ext80.ZERO)

        val sign = a.sign xor b.sign
        val num = Ext80.unsignedLong(a.sig).shiftLeft(63)
        val den = Ext80.unsignedLong(b.sig)
        val qr = num.divideAndRemainder(den)
        var quot = qr[0]
        val rem = qr[1]
        var exp = a.exp - b.exp + Ext80.BIAS
        if (quot.testBit(64)) {
            val stickyBit = quot.testBit(0) || rem.signum() != 0
            quot = quot.shiftRight(1)
            val sticky = if (stickyBit) BigInteger.ONE else BigInteger.ZERO
            return roundPack(sign, exp, quot.and(MASK64), sticky, rc, pc, denormalOperand = a.isDenormal || b.isDenormal)
        }
        if (!quot.testBit(63) && quot.signum() != 0) {
            quot = quot.shiftLeft(1)
            exp--
        }
        val sticky = if (rem.signum() != 0) BigInteger.ONE else BigInteger.ZERO
        return roundPack(sign, exp, quot.and(MASK64), sticky, rc, pc, denormalOperand = a.isDenormal || b.isDenormal)
    }

    fun sqrt(a: Ext80, rc: Int, pc: Int): Result {
        if (a.isNaN) return Result(Ext80.INDEFINITE, IE)
        if (a.isZero) return Result(a)
        if (a.sign) return Result(Ext80.INDEFINITE, IE)
        if (a.isInfinity) return Result(Ext80.POS_INF)
        var expA = if (a.isDenormal) {
            // normalize
            var s = Ext80.unsignedLong(a.sig)
            var e = 1
            while (!s.testBit(63)) {
                s = s.shiftLeft(1)
                e--
            }
            return sqrt(Ext80(false, e + Ext80.BIAS, s.toLong()), rc, pc)
        } else a.exp
        var sig = Ext80.unsignedLong(a.sig)
        var unb = expA - Ext80.BIAS
        // value = sig/2^63 * 2^unb; make unb even
        if ((unb and 1) != 0) {
            sig = sig.shiftLeft(1)
            unb--
        }
        val expZ = (unb shr 1) + Ext80.BIAS
        // sqrt(sig/2^63) = sqrt(sig << 63) / 2^63 → root is the new significand.
        val radical = sig.shiftLeft(63)
        val rootRem = isqrtRem(radical)
        var root = rootRem.first
        var rem = rootRem.second
        var exp = expZ
        if (root.testBit(64)) {
            if (root.testBit(0)) rem = rem.or(BigInteger.ONE)
            root = root.shiftRight(1)
            exp++
        }
        if (!root.testBit(63) && root.signum() != 0) {
            root = root.shiftLeft(1)
            exp--
        }
        val sticky = if (rem.signum() != 0) BigInteger.ONE else BigInteger.ZERO
        return roundPack(false, exp, root.and(MASK64), sticky, rc, pc, denormalOperand = a.isDenormal)
    }

    /**
     * Partial remainder toward zero (FPREM) or nearest-even (FPREM1).
     * Returns remainder, quotient low bits, and whether reduction is incomplete (C2).
     */
    data class RemResult(
        val value: Ext80,
        val quotientLow: Int,
        val incomplete: Boolean,
        val exceptions: Int = 0,
    )

    fun remainder(dividend: Ext80, divisor: Ext80, nearest: Boolean, rc: Int, pc: Int): RemResult {
        if (dividend.isNaN || divisor.isNaN || !dividend.isFinite || !divisor.isFinite || divisor.isZero) {
            return RemResult(Ext80.INDEFINITE, 0, false, IE)
        }
        if (dividend.isZero) return RemResult(dividend, 0, false)

        // Work with absolute values; apply sign of dividend to result.
        val sign = dividend.sign
        var rem = dividend.abs()
        val dvs = divisor.abs()
        var quotAcc = 0
        // At most 32 iterations of 32-bit quotient chunks (8087 style); CheckIt uses small Q.
        var steps = 0
        while (rem.compareTo(dvs) >= 0 && steps < 32) {
            val expDiff = rem.exp - dvs.exp
            if (expDiff >= 64) {
                // Reduce by scaling: rem -= dvs * 2^(expDiff-32) roughly via left-shift dvs
                val shift = expDiff - 32
                val chunk = Ext80(false, dvs.exp + shift, dvs.sig)
                rem = sub(rem, chunk, Ext80.RC_CHOP, Ext80.PC_EXTENDED).value.abs()
                quotAcc = (quotAcc + (1 shl 32)) and 7 // only need low 3 bits eventually; mark incomplete
                steps++
                if (expDiff >= 64) {
                    // Still large — signal incomplete if we bail early
                    if (steps >= 32) return RemResult(if (sign) rem.negate() else rem, quotAcc and 7, true)
                }
                continue
            }
            // Quotient estimate from exponents/significands
            val num = Ext80.unsignedLong(rem.sig).shiftLeft(32)
            val den = Ext80.unsignedLong(dvs.sig)
            var q = if (den.signum() == 0) BigInteger.ZERO else num.divide(den)
            if (expDiff < 0) break
            // Adjust q by 2^(expDiff) scale in integer sense — use BigDecimal for exact small cases
            break
        }

        // Exact path via BigDecimal for fidelity (partial rem when |Q| fits).
        val dd = dividend.toBigDecimal()
        val ds = divisor.toBigDecimal()
        val rawQ = dd.divide(ds, Ext80.MC_TRANS)
        val qIntegral = if (nearest) {
            rawQ.setScale(0, RoundingMode.HALF_EVEN)
        } else {
            // toward zero
            rawQ.setScale(0, RoundingMode.DOWN)
        }
        // Incomplete if |Q| >= 2^64
        if (qIntegral.abs() >= BigDecimal.valueOf(2).pow(64)) {
            // Reduce partially: Q' = Q with high bits cleared leaving 32 bits
            val qPartial = rawQ.setScale(0, RoundingMode.DOWN)
                .remainder(BigDecimal.valueOf(2).pow(32))
            val remBd = dd.subtract(qPartial.multiply(ds))
            val qLow = qPartial.toBigInteger().and(BigInteger.valueOf(7)).toInt()
            return RemResult(Ext80.fromBigDecimal(remBd, rc, pc), qLow, true)
        }
        val remBd = dd.subtract(qIntegral.multiply(ds))
        val qLow = try {
            qIntegral.toBigInteger().and(BigInteger.valueOf(7)).toInt()
        } catch (_: Exception) {
            0
        }
        // Preserve sign of dividend for zero
        val out = Ext80.fromBigDecimal(remBd, rc, pc)
        val signed = when {
            remBd.signum() == 0 -> if (sign) Ext80.NEG_ZERO else Ext80.ZERO
            out.sign != (remBd.signum() < 0) -> out.negate()
            else -> out
        }
        return RemResult(signed, qLow and 7, false)
    }

    fun scale(a: Ext80, n: Int): Result {
        if (!a.isFinite || a.isZero) return Result(a)
        val exp = a.exp + n
        return when {
            exp >= Ext80.EXP_INF -> Result(if (a.sign) Ext80.NEG_INF else Ext80.POS_INF, OE)
            exp <= 0 -> Result(if (a.sign) Ext80.NEG_ZERO else Ext80.ZERO, UE)
            else -> Result(a.copy(exp = exp))
        }
    }

    internal fun roundPackPublic(
        sign: Boolean,
        expIn: Int,
        hi: BigInteger,
        lo: BigInteger,
        rc: Int,
        pc: Int,
    ): Result = roundPack(sign, expIn, hi, lo, rc, pc, false)

    private fun isqrtRem(n: BigInteger): Pair<BigInteger, BigInteger> {
        if (n.signum() <= 0) return BigInteger.ZERO to BigInteger.ZERO
        // Integer square root via Newton
        var x = BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2)
        while (true) {
            val q = n.divide(x)
            val next = x.add(q).shiftRight(1)
            if (next >= x) {
                val root = x
                val rem = n.subtract(root.multiply(root))
                return root to rem
            }
            x = next
        }
    }

    private fun specialMulDiv(a: Ext80, b: Ext80): Result? {
        if (a.isNaN || b.isNaN) return Result(Ext80.INDEFINITE, IE)
        if ((a.isZero && b.isInfinity) || (b.isZero && a.isInfinity)) return Result(Ext80.INDEFINITE, IE)
        if (a.isInfinity || b.isInfinity) {
            return Result(if (a.sign xor b.sign) Ext80.NEG_INF else Ext80.POS_INF)
        }
        return null
    }

    private fun addSub(a0: Ext80, b0: Ext80, subtract: Boolean, rc: Int, pc: Int): Result {
        val b1 = if (subtract) b0.negate() else b0
        if (a0.isNaN || b1.isNaN) return Result(Ext80.INDEFINITE, IE)
        if (a0.isInfinity && b1.isInfinity && a0.sign != b1.sign) return Result(Ext80.INDEFINITE, IE)
        if (a0.isInfinity) return Result(a0)
        if (b1.isInfinity) return Result(b1)
        if (a0.isZero && b1.isZero) {
            val sign = if (rc == Ext80.RC_DOWN) true else a0.sign && b1.sign
            return Result(if (sign) Ext80.NEG_ZERO else Ext80.ZERO)
        }
        if (a0.isZero) return Result(b1)
        if (b1.isZero) return Result(a0)

        var aSign = a0.sign
        var bSign = b1.sign
        var aExp = a0.exp
        var bExp = b1.exp
        var aSig = Ext80.unsignedLong(a0.sig).shiftLeft(3)
        var bSig = Ext80.unsignedLong(b1.sig).shiftLeft(3)
        if (aExp < bExp || (aExp == bExp && aSig < bSig)) {
            val ts = aSign; aSign = bSign; bSign = ts
            val te = aExp; aExp = bExp; bExp = te
            val tv = aSig; aSig = bSig; bSig = tv
        }
        val shift = aExp - bExp
        var sticky = BigInteger.ZERO
        if (shift > 0) {
            if (shift >= 128) {
                sticky = if (bSig.signum() != 0) BigInteger.ONE else BigInteger.ZERO
                bSig = BigInteger.ZERO
            } else {
                val lost = bSig.and(BigInteger.ONE.shiftLeft(shift) - BigInteger.ONE)
                if (lost.signum() != 0) sticky = BigInteger.ONE
                bSig = bSig.shiftRight(shift)
            }
        }

        var resultSign = aSign
        var sum: BigInteger
        if (aSign == bSign) {
            sum = aSig + bSig
        } else {
            sum = aSig - bSig
            if (sum.signum() == 0) {
                resultSign = rc == Ext80.RC_DOWN
            }
        }
        if (sticky.signum() != 0) sum = sum.or(BigInteger.ONE)

        var exp = aExp
        if (sum.signum() == 0) return Result(if (resultSign) Ext80.NEG_ZERO else Ext80.ZERO)
        while (sum.bitLength() > 67) {
            val s = sum.testBit(0)
            sum = sum.shiftRight(1)
            if (s) sum = sum.or(BigInteger.ONE)
            exp++
        }
        while (sum.bitLength() < 67 && exp > 1) {
            sum = sum.shiftLeft(1)
            exp--
        }
        val hi = sum.shiftRight(3)
        val grs = sum.and(BigInteger.valueOf(7))
        return roundPack(resultSign, exp, hi, grs, rc, pc, denormalOperand = a0.isDenormal || b1.isDenormal)
    }

    private fun roundPack(
        sign: Boolean,
        expIn: Int,
        hi: BigInteger,
        lo: BigInteger,
        rc: Int,
        pc: Int,
        denormalOperand: Boolean,
    ): Result {
        var exp = expIn
        var sig = hi.and(MASK64)
        var exceptions = 0
        if (denormalOperand) exceptions = exceptions or DE

        val precisionBits = when (pc) {
            Ext80.PC_SINGLE -> 24
            Ext80.PC_DOUBLE -> 53
            else -> 64
        }
        val discard = 64 - precisionBits
        var c1 = false
        val sticky = lo.signum() != 0

        if (discard > 0) {
            val mask = BigInteger.ONE.shiftLeft(discard) - BigInteger.ONE
            var frac = sig.and(mask)
            if (sticky) frac = frac.or(BigInteger.ONE)
            val truncated = sig.and(mask.not())
            val half = BigInteger.ONE.shiftLeft(discard - 1)
            val roundUp = when (rc) {
                Ext80.RC_NEAR -> frac > half || (frac == half && truncated.testBit(discard))
                Ext80.RC_DOWN -> sign && frac.signum() != 0
                Ext80.RC_UP -> !sign && frac.signum() != 0
                else -> false
            }
            if (frac.signum() != 0) exceptions = exceptions or PE
            sig = truncated
            if (roundUp) {
                c1 = !sign
                sig = sig + BigInteger.ONE.shiftLeft(discard)
                if (sig.bitLength() > 64) {
                    sig = sig.shiftRight(1)
                    exp++
                }
            }
        } else if (sticky) {
            exceptions = exceptions or PE
            val roundUp = when (rc) {
                Ext80.RC_NEAR -> true
                Ext80.RC_DOWN -> sign
                Ext80.RC_UP -> !sign
                else -> false
            }
            val doRound = when (rc) {
                Ext80.RC_NEAR -> false
                else -> roundUp
            }
            if (doRound) {
                c1 = !sign
                sig = sig + BigInteger.ONE
                if (sig.bitLength() > 64) {
                    sig = sig.shiftRight(1)
                    exp++
                }
            }
        }

        if (exp >= Ext80.EXP_INF) {
            return Result(if (sign) Ext80.NEG_INF else Ext80.POS_INF, exceptions or OE, c1)
        }
        if (exp <= 0) {
            return Result(if (sign) Ext80.NEG_ZERO else Ext80.ZERO, exceptions or UE, c1)
        }
        return Result(Ext80(sign, exp, sig.toLong()), exceptions, c1)
    }

    private val MASK64 = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

    const val IE = 0x0001
    const val DE = 0x0002
    const val ZE = 0x0004
    const val OE = 0x0008
    const val UE = 0x0010
    const val PE = 0x0020
}
