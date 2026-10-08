package com.trugath.k8086.video

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * NTSC composite artifact-color decoder for IBM CGA (New CGA circuit).
 *
 * Port of reenigne's chroma-multiplexer model (as used in DOSBox / DOSBox-X):
 * given the current color-select / mode bits, builds 16-entry LUTs that map
 * 4-bit pixel patterns within one NTSC color clock to RGB.
 *
 * Mode 6 (640×200 1bpp): each nibble is one color clock → one artifact color.
 * Mode 4/5 (320×200 2bpp): each pair of 2-bit pixels is one color clock.
 */
object CgaComposite {
    enum class Mode { AUTO, ON, OFF }

    private const val TAU = 6.28318531
    private const val NS = 567.0 / 440.0 // degrees of hue shift per nanosecond
    private const val GAMMA = 2.2

    /** Rebuild LUTs for New CGA. [hueOffsetDeg] matches a monitor tint dial. */
    fun rebuild(
        colorSelect: Int,
        modeControl: Int,
        hueOffsetDeg: Double = 0.0,
    ): Luts {
        val bw = (modeControl and 0x04) != 0 // color-burst disabled → B&W composite
        val colorSel = (colorSelect and 0x20) != 0
        val backgroundI = (colorSelect and 0x10) != 0
        val overscan = colorSelect and 0x0F // FG colour in 1bpp; bg in 2bpp

        val tvSaturation = 0.7
        val chromaCoefficient = 0.29
        val bCoefficient = 0.07
        val gCoefficient = 0.22
        val rCoefficient = 0.1
        val iCoefficient = 0.32

        val rgbiCoefficients = DoubleArray(16) { c ->
            var v = 0.0
            if ((c and 1) != 0) v += bCoefficient
            if ((c and 2) != 0) v += gCoefficient
            if ((c and 4) != 0) v += rCoefficient
            if ((c and 8) != 0) v += iCoefficient
            v
        }

        val rgbiPixelDelay = 15.5 * NS
        val chromaPixelDelays = doubleArrayOf(
            0.0, 35 * NS, 44.5 * NS, 39.5 * NS, 44.5 * NS, 39.5 * NS, 44.5 * NS, 39.5 * NS,
        )
        val o = if (overscan == 0) 15 else overscan
        val pixelClockDelay = if (overscan == 8) {
            rgbiPixelDelay
        } else {
            val d = rgbiCoefficients[o]
            (chromaPixelDelays[o and 7] * chromaCoefficient + rgbiPixelDelay * d) /
                (chromaCoefficient + d)
        } - 21.5 * NS

        val hueAdjust = (-(90 - 33) - hueOffsetDeg + pixelClockDelay) * TAU / 360.0

        val chromaSignals = Array(8) { DoubleArray(4) }
        val phases = doubleArrayOf(
            270 - 21.5 * NS, // blue
            135 - 29.5 * NS, // green
            180 - 21.5 * NS, // cyan
            0 - 21.5 * NS,   // red
            315 - 29.5 * NS, // magenta
            90 - 21.5 * NS,  // yellow / burst
        )
        val duty = 0.5 - 2 * NS / 360.0
        val a = duty
        val b = 2.0 * (1.0 - cos(duty * TAU)) / TAU
        val c = 2.0 * sin(duty * TAU) / TAU
        val d = 2.0 * (1.0 - cos(duty * 2 * TAU)) / (2 * TAU)
        for (i in 0 until 4) {
            chromaSignals[0][i] = 0.0
            chromaSignals[7][i] = 1.0
            for (j in 0 until 6) {
                val x = (phases[j] + 21.5 * NS + pixelClockDelay) / 360.0 + i / 4.0
                chromaSignals[j + 1][i] =
                    a + b * sin(x * TAU) + c * cos(x * TAU) + d * sin(x * 2 * TAU)
            }
        }

        val cgaPal = intArrayOf(
            overscan,
            2 + (if (colorSel || bw) 1 else 0) + (if (backgroundI) 8 else 0),
            4 + (if (colorSel && !bw) 1 else 0) + (if (backgroundI) 8 else 0),
            6 + (if (colorSel || bw) 1 else 0) + (if (backgroundI) 8 else 0),
        )

        fun rgbForBits(bits: Int, bpp1Mode: Boolean): Int {
            var Y = 0.0
            var I = 0.0
            var Q = 0.0
            val xPhase = 0 // color-clock-aligned nibbles / pixel pairs
            for (p in 0 until 4) {
                val rgbi = if (bpp1Mode) {
                    if (((bits shr (3 - p)) and 1) != 0) overscan else 0
                } else {
                    cgaPal[(bits shr (2 - (p and 2))) and 3]
                }
                var chromaIdx = rgbi and 7
                if (bw && chromaIdx != 0) chromaIdx = 7
                val chroma = chromaSignals[chromaIdx][(p + xPhase) and 3] * chromaCoefficient
                val composite = chroma + rgbiCoefficients[rgbi]
                Y += composite
                if (!bw) {
                    I += composite * 2 * cos(hueAdjust + (p + xPhase) * TAU / 4.0)
                    Q += composite * 2 * sin(hueAdjust + (p + xPhase) * TAU / 4.0)
                }
            }
            Y = (Y / 4.0).coerceIn(0.0, 1.0)
            I = ((I / 4.0) * tvSaturation).coerceIn(-0.5957, 0.5957)
            Q = ((Q / 4.0) * tvSaturation).coerceIn(-0.5226, 0.5226)

            var R = (Y + 0.9563 * I + 0.6210 * Q).let { ((it - 0.075) / 0.925).coerceIn(0.0, 1.0) }
            var G = (Y - 0.2721 * I - 0.6474 * Q).let { ((it - 0.075) / 0.925).coerceIn(0.0, 1.0) }
            var B = (Y - 1.1069 * I + 1.7046 * Q).let { ((it - 0.075) / 0.925).coerceIn(0.0, 1.0) }
            R = R.pow(GAMMA)
            G = G.pow(GAMMA)
            B = B.pow(GAMMA)

            fun ch(v: Double): Int {
                val t = v.coerceAtLeast(0.0).pow(1.0 / GAMMA)
                return (255.0 * t).toInt().coerceIn(0, 255)
            }
            val r = ch(1.5073 * R - 0.3725 * G - 0.0832 * B)
            val g = ch(-0.0275 * R + 0.9350 * G + 0.0670 * B)
            val b = ch(-0.0272 * R - 0.0401 * G + 1.1677 * B)
            return (r shl 16) or (g shl 8) or b
        }

        val hiRes = IntArray(16) { rgbForBits(it, bpp1Mode = true) }
        val loRes = IntArray(16) { rgbForBits(it, bpp1Mode = false) }
        return Luts(hiRes, loRes)
    }

    data class Luts(val mode6: IntArray, val mode4: IntArray)
}
