package com.trugath.k8086.cpu.fpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ext80Test {
    @Test
    fun fromDoubleRoundTripCommonValues() {
        for (v in listOf(0.0, 1.0, -1.0, 1.5, 2.0, Math.PI, 1e-10, 1e10)) {
            assertEquals(v, Ext80.fromDouble(v).toDouble(), absTol(v))
        }
    }

    @Test
    fun addMulDivSqrtGoldenVectors() {
        val rc = Ext80.RC_NEAR
        val pc = Ext80.PC_EXTENDED
        val a = Ext80.fromDouble(1.5)
        val b = Ext80.fromLong(4)
        assertEquals(6.0, Ext80Math.mul(a, b, rc, pc).value.toDouble(), 1e-15)
        assertEquals(2.0, Ext80Math.div(Ext80.fromDouble(6.0), Ext80.fromDouble(3.0), rc, pc).value.toDouble(), 1e-15)
        assertEquals(2.0, Ext80Math.add(Ext80.ONE, Ext80.ONE, rc, pc).value.toDouble(), 1e-15)
        assertEquals(kotlin.math.sqrt(2.0), Ext80Math.sqrt(Ext80.fromDouble(2.0), rc, pc).value.toDouble(), 1e-12)
    }

    @Test
    fun divideByZeroSetsZeAndInfinity() {
        val r = Ext80Math.div(Ext80.ONE, Ext80.ZERO, Ext80.RC_NEAR, Ext80.PC_EXTENDED)
        assertTrue(r.value.isInfinity)
        assertEquals(Ext80Math.ZE, r.exceptions and Ext80Math.ZE)
    }

    @Test
    fun m80IdentityPreservesSignificandBits() {
        val original = Ext80(false, Ext80.BIAS, 0xC000_0000_0000_0000uL.toLong()) // 1.5
        val bytes = ByteArray(10)
        FpuFormats.writeFloat80(0, original, { addr, v -> bytes[addr] = v.toByte() })
        val loaded = FpuFormats.readFloat80(0) { bytes[it].toInt() and 0xFF }
        assertEquals(original, loaded)
        assertEquals(1.5, loaded.toDouble(), 0.0)
    }

    @Test
    fun divOneByThree() {
        val r = Ext80Math.div(Ext80.ONE, Ext80.fromLong(3), Ext80.RC_NEAR, Ext80.PC_EXTENDED)
        assertEquals(1.0 / 3.0, r.value.toDouble(), 1e-15)
    }

    private fun absTol(v: Double): Double = if (kotlin.math.abs(v) < 1e-6) 1e-15 else kotlin.math.abs(v) * 1e-12
}
// placeholder
