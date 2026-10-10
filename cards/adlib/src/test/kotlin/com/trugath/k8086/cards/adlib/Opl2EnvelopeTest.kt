package com.trugath.k8086.cards.adlib

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Opl2EnvelopeTest {
    private fun tone(tl: Int = 0, sustain: Int = 0, decay: Int = 0, flags: Int = 0x21): Opl2Core =
        Opl2Core().apply {
            writeReg(0x23, flags)
            writeReg(0x43, tl)
            writeReg(0x63, 0xF0 or decay)
            writeReg(0x83, sustain shl 4)
            writeReg(0xC0, 1) // additive; modulator attack=0 stays silent
            writeReg(0xA0, 0xAE)
            writeReg(0xB0, 0x2B)
        }

    private fun rms(core: Opl2Core, count: Int = 4096): Double {
        var energy = 0.0
        repeat(count) { val s = core.renderSample(); energy += s * s }
        return sqrt(energy / count)
    }

    @Test
    fun totalLevelUsesThreeQuarterDecibelSteps() {
        val loud = rms(tone())
        val quiet = rms(tone(tl = 32)) // 24 dB attenuation
        assertEquals(Math.pow(10.0, -24.0 / 20), quiet / loud, 0.0001)
    }

    @Test
    fun sustainUsesThreeDecibelStepsAndSpecial93DecibelFloor() {
        val loud = tone()
        val sustained = tone(sustain = 4, decay = 15)
        val floor = tone(sustain = 15, decay = 15)
        repeat(1000) { loud.renderSample(); sustained.renderSample(); floor.renderSample() }
        val reference = rms(loud)
        assertEquals(Math.pow(10.0, -12.0 / 20), rms(sustained) / reference, 0.0001)
        assertTrue(rms(floor) / reference < 0.0001)
    }

    @Test
    fun releaseRateSevenHasAudibleTailRatherThanImmediateLinearFade() {
        val core = tone()
        core.writeReg(0x83, 7)
        repeat(100) { core.renderSample() }
        core.writeReg(0xB0, 0x0B)
        repeat(2205) { core.renderSample() } // 100 ms
        assertTrue(rms(core, 256) > 0.001, "rate-7 release should still be audible after 100 ms")
        repeat(22050) { core.renderSample() }
        assertEquals(0.0, rms(core))
    }

    @Test
    fun rewritingUnchangedRhythmBitsDoesNotRestartBassDrum() {
        fun drum() = Opl2Core().apply {
            writeReg(0x30, 0x21); writeReg(0x33, 0x21)
            writeReg(0x70, 0xF0); writeReg(0x73, 0xF0)
            writeReg(0xA6, 0xAE); writeReg(0xB6, 0x0B)
            writeReg(0xBD, 0x30)
        }
        val actual = drum()
        val reference = drum()
        repeat(37) { actual.renderSample(); reference.renderSample() }
        actual.writeReg(0xBD, 0x30)
        repeat(128) { assertEquals(reference.renderSample(), actual.renderSample(), 1e-12) }
    }

    @Test
    fun quarterWaveRepeatsInBothHalvesOfTheCycle() {
        val core = tone()
        core.writeReg(0x01, 0x20)
        core.writeReg(0xE3, 3)
        val increment = (0x3AE * 49716.0 / (1 shl 18)) / 22050.0 * Math.PI * 2
        repeat(200) { i ->
            val phase = (increment * (i + 1)) % (Math.PI * 2)
            val expected = if (phase % Math.PI < Math.PI / 2) abs(kotlin.math.sin(phase)) * 0.12 else 0.0
            assertEquals(expected, core.renderSample(), 1e-10)
        }
    }

    @Test
    fun keyScaleRateSpeedsUpHigherNotes() {
        fun attack(flags: Int) = tone(flags = flags).apply {
            writeReg(0x63, 0x40)
            writeReg(0xA0, 0x20)
            writeReg(0xB0, 0x1C)
            writeReg(0xB0, 0x3C)
        }
        val slow = attack(0x21)
        val scaled = attack(0x31)
        repeat(551) { slow.renderSample(); scaled.renderSample() } // 25 ms into attack
        assertTrue(rms(scaled, 128) > rms(slow, 128) * 5,
            "KSR must affect the attack of high notes")
    }

    @Test
    fun tremoloDepthRegisterControlsAmplitudeModulation() {
        val reference = tone()
        val shallow = tone(flags = 0xA1)
        val deep = tone(flags = 0xA1).apply { writeReg(0xBD, 0x80) }
        repeat(2900) { reference.renderSample(); shallow.renderSample(); deep.renderSample() }
        val loud = rms(reference, 128)
        val shallowLevel = rms(shallow, 128) / loud
        val deepLevel = rms(deep, 128) / loud
        assertTrue(shallowLevel in 0.88..0.92)
        assertTrue(deepLevel in 0.57..0.62)
    }
}
