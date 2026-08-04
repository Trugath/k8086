package com.trugath.k8086.cards.adlib

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Opl2CoreTest {
    @Test
    fun detectSequenceLeavesTimersWritable() {
        // Mirror SDL_DetectAdLib register traffic; synthesis core must accept it.
        val opl = Opl2Core()
        opl.writeReg(0x04, 0x60)
        opl.writeReg(0x04, 0x80)
        opl.writeReg(0x02, 0xFF)
        opl.writeReg(0x04, 0x21)
        opl.writeReg(0x04, 0x60)
        opl.writeReg(0x04, 0x80)
        for (i in 0x01..0xF5) opl.writeReg(i, 0)
        opl.writeReg(0x01, 0x20)
        opl.writeReg(0x08, 0x00)
        assertEquals(0x20, opl.regs[0x01])
        assertFalse(opl.anyKeyOn())
    }

    @Test
    fun keyOnProducesAudibleSamplesThenSilenceOnKeyOff() {
        val opl = Opl2Core()
        // Minimal instrument: carrier TL=0, fast attack, long sustain
        opl.writeReg(0x01, 0x20) // waveform select enable
        opl.writeReg(0x20, 0x01) // mod mult=1
        opl.writeReg(0x23, 0x01) // car mult=1
        opl.writeReg(0x40, 0x10) // mod quieter
        opl.writeReg(0x43, 0x00) // car TL=0
        opl.writeReg(0x60, 0xF0) // mod attack max
        opl.writeReg(0x63, 0xF0) // car attack max
        opl.writeReg(0x80, 0x77)
        opl.writeReg(0x83, 0x77)
        opl.writeReg(0xC0, 0x00) // FM connection
        opl.writeReg(0xA0, 0xAE)
        opl.writeReg(0xB0, 0x2B) // key-on, block, fnum hi
        assertTrue(opl.anyKeyOn())

        var peak = 0
        repeat(2000) {
            peak = maxOf(peak, abs(opl.renderSample8()))
        }
        assertTrue(peak > 8, "key-on should produce non-trivial amplitude, peak=$peak")

        opl.writeReg(0xB0, 0x0B) // key-off
        assertFalse(opl.anyKeyOn())
        // Drain release
        repeat(50_000) { opl.renderSample8() }
        var tail = 0
        repeat(256) { tail = maxOf(tail, abs(opl.renderSample8())) }
        assertTrue(tail < 4, "after release, output should be near silence, tail=$tail")
    }

    @Test
    fun waveformSelectHalfSineDiffersFromFullSine() {
        data class WaveStats(val peak: Int, val neg: Int)

        fun statsForWave(wave: Int): WaveStats {
            val opl = Opl2Core()
            opl.writeReg(0x01, 0x20)
            opl.writeReg(0x23, 0x21) // car mult=1, EG-type sustain
            opl.writeReg(0x43, 0x00)
            opl.writeReg(0x63, 0xF0)
            opl.writeReg(0x83, 0x00) // sustain level 0 (= max volume), release 0
            opl.writeReg(0xE3, wave)
            opl.writeReg(0xC0, 0x01) // additive so modulator silent contrib
            opl.writeReg(0x20, 0x01)
            opl.writeReg(0x40, 0x3F) // mute modulator
            opl.writeReg(0x60, 0xF0)
            opl.writeReg(0x80, 0x0F)
            opl.writeReg(0xA0, 0xAE)
            opl.writeReg(0xB0, 0x2B) // key-on + fnum/block
            var peak = 0
            var neg = 0
            repeat(4000) {
                val s = opl.renderSample8()
                peak = maxOf(peak, abs(s))
                if (s < -2) neg++
            }
            return WaveStats(peak, neg)
        }
        val sine = statsForWave(0)
        val half = statsForWave(1)
        assertTrue(sine.peak > 8, "sine should be audible, peak=${sine.peak}")
        assertTrue(half.peak > 8, "half-sine should be audible, peak=${half.peak}")
        assertTrue(sine.neg > 50, "full sine should swing negative, neg=${sine.neg}")
        assertTrue(
            half.neg < sine.neg / 4,
            "half-sine should rarely go negative, neg=${half.neg} vs ${sine.neg}",
        )
    }

    @Test
    fun frequencyChangeAltersPhaseProgress() {
        val low = Opl2Core()
        val high = Opl2Core()
        fun arm(opl: Opl2Core, a0: Int, b0: Int) {
            opl.writeReg(0x23, 0x01)
            opl.writeReg(0x43, 0x00)
            opl.writeReg(0x63, 0xF0)
            opl.writeReg(0x83, 0x77)
            opl.writeReg(0xC0, 0x01)
            opl.writeReg(0x40, 0x3F)
            opl.writeReg(0x20, 0x01)
            opl.writeReg(0x60, 0xF0)
            opl.writeReg(0x80, 0x0F)
            opl.writeReg(0xA0, a0)
            opl.writeReg(0xB0, b0)
        }
        arm(low, 0x40, 0x20) // low fnum, key-on
        arm(high, 0xFF, 0x23) // high fnum + higher block bits
        var zeroCrossLow = 0
        var zeroCrossHigh = 0
        var prevL = 0
        var prevH = 0
        repeat(8000) {
            val l = low.renderSample8()
            val h = high.renderSample8()
            if (prevL < 0 && l >= 0) zeroCrossLow++
            if (prevH < 0 && h >= 0) zeroCrossHigh++
            prevL = l
            prevH = h
        }
        assertTrue(
            zeroCrossHigh > zeroCrossLow,
            "higher fnum/block should cross zero more often ($zeroCrossHigh vs $zeroCrossLow)",
        )
    }
}
