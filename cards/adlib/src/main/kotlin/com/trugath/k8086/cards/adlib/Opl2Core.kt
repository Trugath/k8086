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

    /** Write an OPL register (index already latched by the card). */
    fun writeReg(index: Int, value: Int) {
        val i = index and 0xFF
        val v = value and 0xFF
        regs[i] = v
        when {
            i == 0x01 -> waveformSelect = (v and 0x20) != 0
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
        var mix = 0.0
        val melodicEnd = if (rhythm) 6 else 9
        for (c in 0 until melodicEnd) {
            mix += renderChannel(c)
        }
        if (rhythm) {
            for (c in 6..8) {
                if (chans[c].keyOn) mix += renderChannel(c)
            }
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

        val phaseInc = phaseIncrement(ch.fnum, ch.block)

        // Modulator
        val fb = if (ch.feedback > 0) {
            (mod.prevOut + mod.prevOut2) * FEEDBACK_SCALE[ch.feedback]
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
        op.phase += baseInc * mult + phaseMod * 8.0
        // Keep phase in a wide range then wrap via table index
        if (op.phase > 1e6 || op.phase < -1e6) op.phase %= (Math.PI * 2.0)
        while (op.phase >= Math.PI * 2.0) op.phase -= Math.PI * 2.0
        while (op.phase < 0.0) op.phase += Math.PI * 2.0

        val wave = waveform(op.wave, op.phase)
        val env = op.envLevel // 0..1
        val tl = (1.0 - op.tl / 63.0).coerceIn(0.0, 1.0)
        return wave * env * tl
    }

    private fun waveform(wave: Int, phase: Double): Double {
        val w = if (waveformSelect) wave and 3 else 0
        val s = kotlin.math.sin(phase)
        return when (w) {
            1 -> if (s > 0.0) s else 0.0 // half-sine
            2 -> kotlin.math.abs(s) // abs-sine
            3 -> { // pulse / quarter
                val q = phase / (Math.PI * 2.0)
                if (q < 0.25) kotlin.math.abs(s) else 0.0
            }
            else -> s
        }
    }

    private fun tickEnvelope(op: Operator) {
        when (op.env) {
            EnvState.OFF -> Unit
            EnvState.ATTACK -> {
                op.envLevel += ATTACK_RATE[op.attack]
                if (op.envLevel >= 1.0) {
                    op.envLevel = 1.0
                    op.env = EnvState.DECAY
                }
            }
            EnvState.DECAY -> {
                op.envLevel -= DECAY_RATE[op.decay]
                val sustain = 1.0 - op.sustain / 15.0
                if (op.envLevel <= sustain) {
                    op.envLevel = sustain.coerceAtLeast(0.0)
                    op.env = if (op.egType) EnvState.SUSTAIN else EnvState.RELEASE
                }
            }
            EnvState.SUSTAIN -> Unit
            EnvState.RELEASE -> {
                op.envLevel -= RELEASE_RATE[op.release]
                if (op.envLevel <= 0.0) {
                    op.envLevel = 0.0
                    op.env = EnvState.OFF
                }
            }
        }
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
    }

    private fun updateOpKslTl(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].tl = v and 0x3F
        ops[slot].ksl = (v ushr 6) and 3
    }

    private fun updateOpAd(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].attack = (v ushr 4) and 0x0F
        ops[slot].decay = v and 0x0F
    }

    private fun updateOpSr(off: Int, v: Int) {
        val slot = opOffsetToSlot(off) ?: return
        ops[slot].sustain = (v ushr 4) and 0x0F
        ops[slot].release = v and 0x0F
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
        chans[ch].keyOn = true
        // Ensure a default fnum if unset so percussion is audible
        if (chans[ch].fnum == 0) {
            chans[ch].fnum = 0x200
            chans[ch].block = 4
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
        op.env = EnvState.ATTACK
        if (op.attack == 0x0F) {
            op.envLevel = 1.0
            op.env = EnvState.DECAY
        } else {
            op.envLevel = 0.0
        }
    }

    private fun keyOffOp(op: Operator) {
        if (op.env != EnvState.OFF) op.env = EnvState.RELEASE
    }

    private class Operator {
        var phase = 0.0
        var prevOut = 0.0
        var prevOut2 = 0.0
        var env = EnvState.OFF
        var envLevel = 0.0
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

        private val FEEDBACK_SCALE = doubleArrayOf(
            0.0, 0.5 / 16, 0.5 / 8, 0.5 / 4, 0.5 / 2, 0.5, 1.0, 2.0,
        )

        // Per-sample envelope steps (sampleRate ~22 kHz); rate 0 = slowest / silent attack
        private val ATTACK_RATE = DoubleArray(16) { r ->
            if (r == 0) 0.0 else if (r == 15) 1.0 else 0.0008 * (1 shl (r / 2))
        }
        private val DECAY_RATE = DoubleArray(16) { r ->
            if (r == 0) 0.0 else 0.0002 * (1 shl (r / 2))
        }
        private val RELEASE_RATE = DoubleArray(16) { r ->
            if (r == 0) 0.0 else 0.0002 * (1 shl (r / 2))
        }
    }
}
