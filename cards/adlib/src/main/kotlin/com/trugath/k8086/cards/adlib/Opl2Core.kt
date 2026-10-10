package com.trugath.k8086.cards.adlib

/**
 * MIT-clean 2-op OPL2 (YM3812) synthesizer for AdLib register programming.
 *
 * Models melodic channels 0–8 with operator envelopes, FM/AM connection,
 * feedback, and optional waveform select. Rhythm mode (`BD` bit5) treats
 * channels 6–8 as percussion key-ons. Not cycle-accurate silicon; enough for
 * Wolf3D music/SFX timbre.
 */
class Opl2Core(
    private val sampleRate: Double = 22050.0,
) {
    val regs = IntArray(256)

    private val ops = Array(OP_COUNT) { Operator() }
    private val chans = Array(9) { Channel() }
    private var waveformSelect = false
    private var rhythm = false
    private var vibratoPhase = 0.0
    private var tremoloPhase = 0.0
    private var vibratoFactor = 1.0
    private var tremoloDb = 0.0
    private val attackFactors = DoubleArray(64) { rate ->
        val ms = ATTACK_MS[rate]
        if (ms == 0.0) 0.0 else kotlin.math.exp(kotlin.math.ln(0.01 / 96.0) / (ms * sampleRate / 1000.0))
    }
    private val decaySteps = DoubleArray(64) { rate ->
        if (rate < 4) 0.0 else 96.0 / (DECAY_MS[rate] * sampleRate / 1000.0)
    }

    /** Write an OPL register (index already latched by the card). */
    fun writeReg(index: Int, value: Int) {
        val i = index and 0xFF
        val v = value and 0xFF
        regs[i] = v
        when {
            i == 0x01 -> waveformSelect = (v and 0x20) != 0
            i == 0x08 -> for (c in 0..8) refreshChannel(c)
            i == 0xBD -> {
                val was = rhythm
                rhythm = (v and 0x20) != 0
                if (rhythm) {
                    // Percussion key bits: BD=4, SD=3, TT=2, CY=1, HH=0
                    if ((v and 0x10) != 0) keyOnPerc(6) else keyOffPerc(6)
                    if ((v and 0x08) != 0 || (v and 0x01) != 0) keyOnPerc(7) else keyOffPerc(7)
                    if ((v and 0x04) != 0 || (v and 0x02) != 0) keyOnPerc(8) else keyOffPerc(8)
                } else if (was) {
                    for (c in 6..8) keyOffChannel(c)
                }
            }
            i in 0x20..0x35 -> updateOpFlags(i - 0x20, v)
            i in 0x40..0x55 -> updateOpKslTl(i - 0x40, v)
            i in 0x60..0x75 -> updateOpAd(i - 0x60, v)
            i in 0x80..0x95 -> updateOpSr(i - 0x80, v)
            i in 0xE0..0xF5 -> updateOpWave(i - 0xE0, v)
            i in 0xA0..0xA8 -> updateFnumLow(i - 0xA0, v)
            i in 0xB0..0xB8 -> updateBlockKey(i - 0xB0, v)
            i in 0xC0..0xC8 -> {
                val ch = i - 0xC0
                chans[ch].feedback = (v ushr 1) and 7
                chans[ch].additive = (v and 1) != 0
            }
        }
    }

    /** True when any melodic/percussion channel has the hardware key-on bit set. */
    fun anyKeyOn(): Boolean = chans.any { it.keyOn }

    /** One mono sample in roughly -1..1. */
    fun renderSample(): Double {
        vibratoPhase = (vibratoPhase + 6.4 / sampleRate) % 1.0
        tremoloPhase = (tremoloPhase + 3.7 / sampleRate) % 1.0
        val cents = if ((regs[0xBD] and 0x40) != 0) 14.0 else 7.0
        vibratoFactor = Math.pow(2.0, kotlin.math.sin(vibratoPhase * Math.PI * 2) * cents / 1200.0)
        val depth = if ((regs[0xBD] and 0x80) != 0) 4.8 else 1.0
        tremoloDb = (1.0 - kotlin.math.abs(tremoloPhase * 2 - 1.0)) * depth
        var mix = 0.0
        val melodicEnd = if (rhythm) 6 else 9
        for (c in 0 until melodicEnd) {
            mix += renderChannel(c)
        }
        if (rhythm) {
            for (c in 6..8) mix += renderChannel(c)
        }
        return (mix * 0.12).coerceIn(-1.0, 1.0)
    }

    /** Signed 8-bit sample for the AdLib PCM path. */
    fun renderSample8(): Int = (renderSample() * 100.0).toInt().coerceIn(-128, 127)

    private fun renderChannel(c: Int): Double {
        val ch = chans[c]
        val mod = ops[SLOT_MOD[c]]
        val car = ops[SLOT_CAR[c]]
        if (!ch.keyOn && mod.env == EnvState.OFF && car.env == EnvState.OFF) return 0.0

        val phaseInc = ch.phaseInc

        // Modulator
        val fb = if (ch.feedback > 0) {
            (mod.prevOut + mod.prevOut2) * 0.5 * FEEDBACK_SCALE[ch.feedback]
        } else {
            0.0
        }
        val modOut = advanceOp(mod, phaseInc, fb)
        mod.prevOut2 = mod.prevOut
        mod.prevOut = modOut

        return if (ch.additive) {
            val carOut = advanceOp(car, phaseInc, 0.0)
            modOut + carOut
        } else {
            advanceOp(car, phaseInc, modOut)
        }
    }

    private fun advanceOp(op: Operator, baseInc: Double, phaseMod: Double): Double {
        tickEnvelope(op)
        if (op.env == EnvState.OFF) return 0.0
        val mult = MULT_TABLE[op.mult]
        op.phase += baseInc * mult * if (op.vib) vibratoFactor else 1.0
        // Keep phase in a wide range then wrap via table index
        if (op.phase > 1e6 || op.phase < -1e6) op.phase %= (Math.PI * 2.0)
        while (op.phase >= Math.PI * 2.0) op.phase -= Math.PI * 2.0
        while (op.phase < 0.0) op.phase += Math.PI * 2.0

        // FM is a phase offset for this sample, not a change to the oscillator's
        // accumulated phase. Integrating it changes pitch and instrument timbre.
        var outputPhase = (op.phase + phaseMod * 8.0) % (Math.PI * 2.0)
        if (outputPhase < 0.0) outputPhase += Math.PI * 2.0
        val wave = waveform(op.wave, outputPhase)
        val attenuation = op.attenuation + if (op.am) tremoloDb else 0.0
        val env = ENVELOPE_GAIN[(attenuation * 16).toInt().coerceIn(0, ENVELOPE_GAIN.lastIndex)]
        return wave * env * op.levelGain
    }

    private fun waveform(wave: Int, phase: Double): Double {
        val w = if (waveformSelect) wave and 3 else 0
        val s = kotlin.math.sin(phase)
        return when (w) {
            1 -> if (s > 0.0) s else 0.0 // half-sine
            2 -> kotlin.math.abs(s) // abs-sine
            3 -> { // pulse / quarter
                val halfPhase = phase % Math.PI
                if (halfPhase < Math.PI / 2) kotlin.math.abs(s) else 0.0
            }
            else -> s
        }
    }

    private fun tickEnvelope(op: Operator) {
        when (op.env) {
            EnvState.OFF -> Unit
            EnvState.ATTACK -> {
                op.attenuation *= op.attackFactor
                if (op.attenuation <= 0.01) {
                    op.attenuation = 0.0
                    op.env = EnvState.DECAY
                }
            }
            EnvState.DECAY -> {
                op.attenuation += op.decayStep
                if (op.attenuation >= op.sustainDb) {
                    op.attenuation = op.sustainDb
                    op.env = if (op.egType) EnvState.SUSTAIN else EnvState.RELEASE
                }
            }
            EnvState.SUSTAIN -> if (!op.egType) op.env = EnvState.RELEASE
            EnvState.RELEASE -> {
                op.attenuation += op.releaseStep
                if (op.attenuation >= 96.0) {
                    op.attenuation = 96.0
                    op.env = EnvState.OFF
                }
            }
        }
    }

    private fun refreshChannel(c: Int) {
        val ch = chans[c]
        ch.phaseInc = phaseIncrement(ch.fnum, ch.block)
        val noteBit = if ((regs[0x08] and 0x40) != 0) 8 else 9
        val keyCode = (ch.block shl 1) or ((ch.fnum ushr noteBit) and 1)
        val kslBase = (KSL_DB[ch.fnum ushr 6] - 3.0 * (7 - ch.block)).coerceAtLeast(0.0)
        for (slot in intArrayOf(SLOT_MOD[c], SLOT_CAR[c])) {
            val op = ops[slot]
            val rateOffset = if (op.ksr) keyCode else keyCode ushr 2
            fun effectiveRate(rate: Int) = if (rate == 0) 0 else (rate * 4 + rateOffset).coerceAtMost(63)
            op.attackFactor = attackFactors[effectiveRate(op.attack)]
            op.decayStep = decaySteps[effectiveRate(op.decay)]
            op.releaseStep = decaySteps[effectiveRate(op.release)]
            op.sustainDb = if (op.sustain == 15) 93.0 else op.sustain * 3.0
            val scale = when (op.ksl) { 1 -> 1.0; 2 -> 0.5; 3 -> 2.0; else -> 0.0 }
            op.levelGain = Math.pow(10.0, -(op.tl * 0.75 + kslBase * scale) / 20.0)
        }
    }

    private fun refreshOperator(slot: Int) {
        refreshChannel(SLOT_CHANNEL[slot])
    }

    private fun phaseIncrement(fnum: Int, block: Int): Double {
        if (fnum == 0) return 0.0
        // f ≈ fnum * 49716 / 2^(20 - block)
        val hz = fnum * 49716.0 / (1 shl (20 - block))
        return (hz / sampleRate) * Math.PI * 2.0
    }

    private fun updateOpFlags(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        val op = ops[slot]
        op.mult = v and 0x0F
        op.ksr = (v and 0x10) != 0
        op.egType = (v and 0x20) != 0
        op.vib = (v and 0x40) != 0
        op.am = (v and 0x80) != 0
        refreshOperator(slot)
    }

    private fun updateOpKslTl(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].tl = v and 0x3F
        ops[slot].ksl = (v ushr 6) and 3
        refreshOperator(slot)
    }

    private fun updateOpAd(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].attack = (v ushr 4) and 0x0F
        ops[slot].decay = v and 0x0F
        refreshOperator(slot)
    }

    private fun updateOpSr(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].sustain = (v ushr 4) and 0x0F
        ops[slot].release = v and 0x0F
        refreshOperator(slot)
    }

    private fun updateOpWave(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].wave = v and 3
    }

    private fun updateFnumLow(ch: Int, v: Int) {
        val c = chans[ch]
        c.fnum = (c.fnum and 0x300) or (v and 0xFF)
    }

    private fun updateBlockKey(ch: Int, v: Int) {
        val c = chans[ch]
        val prev = c.keyOn
        c.fnum = (c.fnum and 0xFF) or ((v and 0x03) shl 8)
        c.block = (v ushr 2) and 7
        refreshChannel(ch)
        c.keyOn = (v and 0x20) != 0
        if (rhythm && ch >= 6) {
            // Melodic key-on on rhythm channels ignored when percussion active
            // unless BD percussion bits drive them via writeReg BD.
            return
        }
        if (c.keyOn && !prev) keyOnChannel(ch)
        else if (!c.keyOn && prev) keyOffChannel(ch)
    }

    private fun keyOnChannel(ch: Int) {
        chans[ch].keyOn = true
        keyOnOp(ops[SLOT_MOD[ch]])
        keyOnOp(ops[SLOT_CAR[ch]])
    }

    private fun keyOffChannel(ch: Int) {
        chans[ch].keyOn = false
        keyOffOp(ops[SLOT_MOD[ch]])
        keyOffOp(ops[SLOT_CAR[ch]])
    }

    private fun keyOnPerc(ch: Int) {
        if (chans[ch].keyOn) return
        chans[ch].keyOn = true
        // Ensure a default fnum if unset so percussion is audible
        if (chans[ch].fnum == 0) {
            chans[ch].fnum = 0x200
            chans[ch].block = 4
            refreshChannel(ch)
        }
        keyOnOp(ops[SLOT_MOD[ch]])
        keyOnOp(ops[SLOT_CAR[ch]])
    }

    private fun keyOffPerc(ch: Int) {
        chans[ch].keyOn = false
        keyOffOp(ops[SLOT_MOD[ch]])
        keyOffOp(ops[SLOT_CAR[ch]])
    }

    private fun keyOnOp(op: Operator) {
        op.phase = 0.0
        op.prevOut = 0.0
        op.prevOut2 = 0.0
        op.attenuation = if (op.attackFactor == 0.0) 0.0 else 96.0
        op.env = if (op.attackFactor == 0.0) EnvState.DECAY else EnvState.ATTACK
    }

    private fun keyOffOp(op: Operator) {
        if (op.env != EnvState.OFF) op.env = EnvState.RELEASE
    }

    private class Operator {
        var phase = 0.0
        var prevOut = 0.0
        var prevOut2 = 0.0
        var env = EnvState.OFF
        var attenuation = 96.0
        var attackFactor = 1.0
        var decayStep = 0.0
        var releaseStep = 0.0
        var sustainDb = 0.0
        var levelGain = 1.0
        var mult = 1
        var ksr = false
        var egType = false
        var vib = false
        var am = false
        var tl = 0
        var ksl = 0
        var attack = 0
        var decay = 0
        var sustain = 0
        var release = 0
        var wave = 0
    }

    private class Channel {
        var phaseInc = 0.0
        var fnum = 0
        var block = 0
        var keyOn = false
        var feedback = 0
        var additive = false
    }

    private enum class EnvState { OFF, ATTACK, DECAY, SUSTAIN, RELEASE }

    companion object {
        private const val OP_COUNT = 18

        /** Slot index for modulator / carrier of melodic channel 0..8. */
        private val SLOT_MOD = intArrayOf(0, 1, 2, 6, 7, 8, 12, 13, 14)
        private val SLOT_CAR = intArrayOf(3, 4, 5, 9, 10, 11, 15, 16, 17)

        /**
         * Register offset 0..21 → operator slot, or null for holes (6,7,14,15).
         * OPL maps 20+opOff for AM/VIB/EG/KSR/MULT etc.
         */
        private fun opOffsetToSlot(off: Int): Int? {
            if (off !in 0..0x15) return null
            // Physical operator order matching SLOT arrays:
            // offs 0,1,2,3,4,5, 8,9,10,11,12,13, 16,17,18,19,20,21
            val map = intArrayOf(
                0, 1, 2, 3, 4, 5, -1, -1,
                6, 7, 8, 9, 10, 11, -1, -1,
                12, 13, 14, 15, 16, 17,
            )
            val s = map[off]
            return if (s < 0) null else s
        }

        private val MULT_TABLE = doubleArrayOf(
            0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0,
            8.0, 9.0, 10.0, 10.0, 12.0, 12.0, 15.0, 15.0,
        )

        private val SLOT_CHANNEL = intArrayOf(0, 1, 2, 0, 1, 2, 3, 4, 5, 3, 4, 5, 6, 7, 8, 6, 7, 8)
        private val FEEDBACK_SCALE = doubleArrayOf(0.0, Math.PI / 128, Math.PI / 64,
            Math.PI / 32, Math.PI / 16, Math.PI / 8, Math.PI / 4, Math.PI / 2)
        private val KSL_DB = doubleArrayOf(0.0, 9.0, 12.0, 13.875, 15.0, 16.125,
            16.875, 17.625, 18.0, 18.75, 19.125, 19.5, 19.875, 20.25, 20.625, 21.0)
        private val ENVELOPE_GAIN = DoubleArray(1537) {
            if (it == 1536) 0.0 else Math.pow(10.0, -(it / 16.0) / 20.0)
        }
        // Full attack time and 0?96 dB decay time, Yamaha table 3-6.
        private val ATTACK_BASE_MS = doubleArrayOf(Double.POSITIVE_INFINITY,
            2826.24, 1413.12, 706.56, 353.28, 176.64, 88.32, 44.16,
            22.08, 11.04, 5.52, 2.76, 1.40, 0.70, 0.38, 0.0)
        private val RATE_FRACTION = doubleArrayOf(1.0, 0.8, 2.0 / 3, 4.0 / 7)
        private val ATTACK_MS = DoubleArray(64) {
            ATTACK_BASE_MS[it ushr 2] * RATE_FRACTION[it and 3]
        }
        private val DECAY_MS = DoubleArray(64) {
            if (it < 4) Double.POSITIVE_INFINITY else if (it >= 60) 2.4
            else 39280.64 / (1 shl ((it ushr 2) - 1)) * RATE_FRACTION[it and 3]
        }
    }
}
