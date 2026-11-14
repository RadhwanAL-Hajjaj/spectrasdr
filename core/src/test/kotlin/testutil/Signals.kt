// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/** Signal generation and measurement helpers shared by the core tests. */
object Signals {
    /** Interleaved complex tone amplitude · exp(j(2π·f·n/rate + phase)). */
    fun complexTone(n: Int, freqHz: Double, rate: Double, amplitude: Double = 1.0, phase: Double = 0.0): FloatArray {
        val x = FloatArray(2 * n)
        for (k in 0 until n) {
            val a = 2 * PI * freqHz * k / rate + phase
            x[2 * k] = (amplitude * cos(a)).toFloat()
            x[2 * k + 1] = (amplitude * sin(a)).toFloat()
        }
        return x
    }

    fun realTone(n: Int, freqHz: Double, rate: Double, amplitude: Double = 1.0): FloatArray =
        FloatArray(n) { (amplitude * sin(2 * PI * freqHz * it / rate)).toFloat() }

    /** Amplitude in dB (re. 1.0) of the complex tone at [freqHz] in [x], by single-bin DFT over [from, to). */
    fun complexToneDb(x: FloatArray, freqHz: Double, rate: Double, from: Int = 0, to: Int = x.size / 2): Double {
        var re = 0.0
        var im = 0.0
        for (k in from until to) {
            val a = -2 * PI * freqHz * k / rate
            val c = cos(a)
            val s = sin(a)
            re += x[2 * k] * c - x[2 * k + 1] * s
            im += x[2 * k] * s + x[2 * k + 1] * c
        }
        return 20 * log10(hypot(re, im) / (to - from) + 1e-15)
    }

    /** Mean power in dB of complex samples [from, to). */
    fun complexPowerDb(x: FloatArray, from: Int = 0, to: Int = x.size / 2): Double {
        var p = 0.0
        for (k in from until to) p += x[2 * k] * x[2 * k] + x[2 * k + 1] * x[2 * k + 1]
        return 10 * log10(p / (to - from) + 1e-30)
    }

    fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var p = 0f
        for (i in from until to) p = maxOf(p, abs(x[i]))
        return p
    }

    /** Frequency of a real tone from interpolated rising zero crossings in frames [from, to). */
    fun zeroCrossingFrequency(x: FloatArray, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        var first = -1.0
        var last = -1.0
        var count = 0
        for (i in from + 1 until to) {
            val a = x[(i - 1) * channels + channel]
            val b = x[i * channels + channel]
            if (a < 0 && b >= 0) {
                val t = (i - 1) + a / (a - b).toDouble()
                if (first < 0) first = t else { last = t; count++ }
            }
        }
        return count / ((last - first) / rate)
    }

    /**
     * Least-squares fit of a·sin + b·cos + dc at [freqHz] to one channel of [x] over frames
     * [from, to). Returns the tone amplitude and the mean power of what the fit leaves over.
     */
    private fun toneFit(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int, channel: Int): DoubleArray {
        var ss = 0.0
        var sc = 0.0
        var cc = 0.0
        var s1 = 0.0
        var c1 = 0.0
        var xs = 0.0
        var xc = 0.0
        var x1 = 0.0
        var xx = 0.0
        val w = 2 * PI * freqHz / rate
        for (k in from until to) {
            val s = sin(w * k)
            val c = cos(w * k)
            val v = x[k * channels + channel].toDouble()
            ss += s * s
            sc += s * c
            cc += c * c
            s1 += s
            c1 += c
            xs += v * s
            xc += v * c
            x1 += v
            xx += v * v
        }
        val n = (to - from).toDouble()
        val det = ss * (cc * n - c1 * c1) - sc * (sc * n - c1 * s1) + s1 * (sc * c1 - cc * s1)
        val a = (xs * (cc * n - c1 * c1) - sc * (xc * n - c1 * x1) + s1 * (xc * c1 - cc * x1)) / det
        val b = (ss * (xc * n - c1 * x1) - xs * (sc * n - c1 * s1) + s1 * (sc * x1 - xc * s1)) / det
        val d = (ss * (cc * x1 - xc * c1) - sc * (sc * x1 - xc * s1) + xs * (sc * c1 - cc * s1)) / det
        val residual = maxOf(0.0, (xx - a * xs - b * xc - d * x1) / n)
        return doubleArrayOf(hypot(a, b), residual)
    }

    /** Amplitude of the real tone at [freqHz] in one channel of [x] over frames [from, to). */
    fun toneAmplitude(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double =
        toneFit(x, freqHz, rate, from, to, channels, channel)[0]

    /** Signal-to-noise-and-distortion ratio in dB of the real tone at [freqHz] over frames [from, to). */
    fun sinadDb(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        val fit = toneFit(x, freqHz, rate, from, to, channels, channel)
        return 10 * log10(fit[0] * fit[0] / 2 / (fit[1] + 1e-30))
    }
}
