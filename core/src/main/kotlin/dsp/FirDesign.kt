// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Kaiser-window FIR design. Filters are odd-length, linear phase and normalised to unity DC gain. */
object FirDesign {
    /** Kaiser β for a stopband attenuation of [attenuationDb]. */
    fun kaiserBeta(attenuationDb: Double): Double = when {
        attenuationDb > 50 -> 0.1102 * (attenuationDb - 8.7)
        attenuationDb >= 21 -> 0.5842 * (attenuationDb - 21).pow(0.4) + 0.07886 * (attenuationDb - 21)
        else -> 0.0
    }

    /** Odd tap count for a transition band of [transitionHz] at [sampleRate] and [attenuationDb]. */
    fun kaiserTaps(transitionHz: Double, sampleRate: Double, attenuationDb: Double): Int {
        val dw = 2 * PI * transitionHz / sampleRate
        var n = ceil((attenuationDb - 8) / (2.285 * dw)).toInt() + 1
        if (n % 2 == 0) n++
        return maxOf(n, 3)
    }

    /** Windowed-sinc low-pass; [cutoffHz] is the −6 dB point. */
    fun lowPass(numTaps: Int, cutoffHz: Double, sampleRate: Double, beta: Double): FloatArray {
        require(numTaps >= 3 && numTaps % 2 == 1) { "numTaps must be odd and >= 3" }
        val fc = cutoffHz / sampleRate
        val m = (numTaps - 1) / 2
        val i0Beta = besselI0(beta)
        val h = DoubleArray(numTaps) { n ->
            val k = n - m
            val sinc = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val r = k.toDouble() / m
            sinc * besselI0(beta * sqrt(maxOf(0.0, 1 - r * r))) / i0Beta
        }
        val sum = h.sum()
        return FloatArray(numTaps) { (h[it] / sum).toFloat() }
    }

    /** Low-pass flat to [passbandHz] with at least [attenuationDb] rejection from [stopbandHz]. */
    fun lowPassKaiser(passbandHz: Double, stopbandHz: Double, sampleRate: Double, attenuationDb: Double): FloatArray {
        require(stopbandHz > passbandHz) { "stopband must lie above the passband" }
        val n = kaiserTaps(stopbandHz - passbandHz, sampleRate, attenuationDb)
        return lowPass(n, (passbandHz + stopbandHz) / 2, sampleRate, kaiserBeta(attenuationDb))
    }

    /** Magnitude response in dB of real [taps] at [freqHz]. */
    fun responseDb(taps: FloatArray, freqHz: Double, sampleRate: Double): Double {
        val w = 2 * PI * freqHz / sampleRate
        var re = 0.0
        var im = 0.0
        for (n in taps.indices) {
            re += taps[n] * cos(w * n)
            im -= taps[n] * sin(w * n)
        }
        return 20 * log10(hypot(re, im) + 1e-300)
    }

    /** Zeroth-order modified Bessel function of the first kind (power series). */
    fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-14 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}
