package com.trugath.k8086.cpu.fpu

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.tan

/**
 * 8087 transcendentals evaluated in high-precision BigDecimal space, then packed
 * to Ext80 — no Double round-trip through the register file.
 */
internal object Ext80Transcendentals {
    private val MC = Ext80.MC_TRANS
    private val LN2 = BigDecimal("0.693147180559945309417232121458176568")
    private val PI = Ext80.PI.toBigDecimal()

    fun f2xm1(x: Ext80, rc: Int, pc: Int): Ext80 {
        // (2^x) - 1 for |x| typically <= 1; still defined via exp for larger |x|.
        val bd = x.toBigDecimal()
        val twoX = exp2(bd)
        return Ext80.fromBigDecimal(twoX.subtract(BigDecimal.ONE), rc, pc)
    }

    fun fyl2x(y: Ext80, x: Ext80, rc: Int, pc: Int): Ext80 {
        // y * log2(x)
        if (x.sign || x.isZero) return Ext80.INDEFINITE
        val log2x = Ext80.lnBig(x.toBigDecimal()).divide(LN2, MC)
        return Ext80.fromBigDecimal(y.toBigDecimal().multiply(log2x), rc, pc)
    }

    fun fyl2xp1(y: Ext80, x: Ext80, rc: Int, pc: Int): Ext80 {
        val xp1 = x.toBigDecimal().add(BigDecimal.ONE)
        if (xp1.signum() <= 0) return Ext80.INDEFINITE
        val log2 = Ext80.lnBig(xp1).divide(LN2, MC)
        return Ext80.fromBigDecimal(y.toBigDecimal().multiply(log2), rc, pc)
    }

    fun fptan(x: Ext80, rc: Int, pc: Int): Pair<Ext80, Ext80> {
        // Returns (tan(x), 1.0) like 8087 FPTAN.
        val t = tanBd(x.toBigDecimal())
        return Ext80.fromBigDecimal(t, rc, pc) to Ext80.ONE
    }

    fun fpatan(x: Ext80, y: Ext80, rc: Int, pc: Int): Ext80 {
        // atan2(y, x)
        val r = atan2Bd(y.toBigDecimal(), x.toBigDecimal())
        return Ext80.fromBigDecimal(r, rc, pc)
    }

    fun fsin(x: Ext80, rc: Int, pc: Int): Ext80 =
        Ext80.fromBigDecimal(sinBd(x.toBigDecimal()), rc, pc)

    fun fcos(x: Ext80, rc: Int, pc: Int): Ext80 =
        Ext80.fromBigDecimal(cosBd(x.toBigDecimal()), rc, pc)

    private fun exp2(x: BigDecimal): BigDecimal {
        // 2^x = e^(x*ln2)
        return exp(x.multiply(LN2))
    }

    private fun exp(x: BigDecimal): BigDecimal {
        // Reduce: e^x = 2^(x/ln2) = 2^(n+f) = 2^n * e^(f*ln2)
        val xDivLn2 = x.divide(LN2, MC)
        val n = xDivLn2.setScale(0, RoundingMode.FLOOR).intValueExact()
        val f = xDivLn2.subtract(BigDecimal.valueOf(n.toLong()))
        val z = f.multiply(LN2) // in (-ln2, 0] roughly after floor... actually [0,ln2)
        // series e^z
        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        for (i in 1..60) {
            term = term.multiply(z).divide(BigDecimal.valueOf(i.toLong()), MC)
            sum = sum.add(term)
            if (term.abs() < BigDecimal.ONE.scaleByPowerOfTen(-40)) break
        }
        return if (n >= 0) {
            sum.multiply(BigDecimal.valueOf(2).pow(n))
        } else {
            sum.divide(BigDecimal.valueOf(2).pow(-n), MC)
        }
    }

    private fun sinBd(x: BigDecimal): BigDecimal {
        val r = reducePi(x)
        var term = r
        var sum = r
        val r2 = r.multiply(r)
        for (k in 1..40) {
            term = term.multiply(r2).negate()
                .divide(BigDecimal.valueOf((2L * k) * (2L * k + 1)), MC)
            sum = sum.add(term)
            if (term.abs() < BigDecimal.ONE.scaleByPowerOfTen(-40)) break
        }
        return sum
    }

    private fun cosBd(x: BigDecimal): BigDecimal {
        val r = reducePi(x)
        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        val r2 = r.multiply(r)
        for (k in 1..40) {
            term = term.multiply(r2).negate()
                .divide(BigDecimal.valueOf((2L * k - 1) * (2L * k)), MC)
            sum = sum.add(term)
            if (term.abs() < BigDecimal.ONE.scaleByPowerOfTen(-40)) break
        }
        return sum
    }

    private fun tanBd(x: BigDecimal): BigDecimal {
        val c = cosBd(x)
        if (c.abs() < BigDecimal.ONE.scaleByPowerOfTen(-40)) {
            return BigDecimal.valueOf(tan(x.toDouble()))
        }
        return sinBd(x).divide(c, MC)
    }

    private fun atan2Bd(y: BigDecimal, x: BigDecimal): BigDecimal {
        // Use host atan2 for the angle then refine — final pack is Ext80.
        // High-precision path: atan(y/x) with quadrant correction via series.
        if (x.signum() == 0 && y.signum() == 0) return BigDecimal.ZERO
        if (x.signum() == 0) {
            val halfPi = PI.divide(BigDecimal.valueOf(2), MC)
            return if (y.signum() > 0) halfPi else halfPi.negate()
        }
        val ratio = y.divide(x, MC)
        var a = atanBd(ratio.abs())
        if (x.signum() < 0) a = PI.subtract(a)
        if (y.signum() < 0) a = a.negate()
        return a
    }

    private fun atanBd(z: BigDecimal): BigDecimal {
        // For z>1 use pi/2 - atan(1/z)
        var v = z
        var complement = false
        if (v > BigDecimal.ONE) {
            v = BigDecimal.ONE.divide(v, MC)
            complement = true
        }
        // atan(v) series: v - v^3/3 + v^5/5 - ...
        var term = v
        var sum = v
        val v2 = v.multiply(v)
        for (k in 1..80) {
            term = term.multiply(v2).negate()
            sum = sum.add(term.divide(BigDecimal.valueOf(2L * k + 1), MC))
            if (term.abs() < BigDecimal.ONE.scaleByPowerOfTen(-42)) break
        }
        return if (complement) PI.divide(BigDecimal.valueOf(2), MC).subtract(sum) else sum
    }

    private fun reducePi(x: BigDecimal): BigDecimal {
        // Reduce to [-pi, pi]
        val twoPi = PI.multiply(BigDecimal.valueOf(2))
        var r = x.remainder(twoPi)
        if (r > PI) r = r.subtract(twoPi)
        if (r < PI.negate()) r = r.add(twoPi)
        return r
    }
}
