// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order PLL that locks to a real sinusoid near [centerHz] (the 19 kHz stereo pilot, §6.4).
 * The input is mixed to baseband and low-passed by a two-pole arm filter, so the phase error is
 * amplitude-independent and signals more than a few kHz away do not disturb the loop. When locked,
 * input ≈ amplitude · cos(θ), where θ is the loop phase reported for every sample.
 * [minLockAmplitude] is the smallest tracked amplitude (same scale as [amplitude]) that can count
 * as lock, so silence and very weak input read as unlocked.
 */
class Pll(
    private val sampleRate: Double,
    private val centerHz: Double,
    loopBandwidthHz: Double = 10.0,
    maxDeviationHz: Double = 20.0,
    armFilterHz: Double = 400.0,
    damping: Double = 0.707,
    minLockAmplitude: Double = 1e-3,
) {
    private val twoPi = 2 * PI
    private val armAlpha = 1 - exp(-twoPi * armFilterHz / sampleRate)
    private val lockAlpha = 1 - exp(-twoPi * 2.0 / sampleRate)
    private val centerStep = twoPi * centerHz / sampleRate
    private val maxOffset = twoPi * maxDeviationHz / sampleRate
    private val minHalfAmplitudeSquared = (minLockAmplitude / 2) * (minLockAmplitude / 2)
    private val kp: Double
    private val ki: Double
    private var phase = 0.0
    private var freqOffset = 0.0
    private var aRe = 0.0
    private var aIm = 0.0
    private var zRe = 0.0
    private var zIm = 0.0
    private var lockMetric = 0.0

    init {
        val wn = twoPi * loopBandwidthHz / sampleRate
        kp = 2 * damping * wn
        ki = wn * wn
    }

    /** True while the loop holds phase lock. */
    val locked: Boolean get() = lockMetric > 0.8

    /** Estimated amplitude of the tracked sinusoid. */
    val amplitude: Double get() = 2 * sqrt(zRe * zRe + zIm * zIm)

    /** Tracked frequency in Hz. */
    val frequencyHz: Double get() = (centerStep + freqOffset) * sampleRate / twoPi

    /** Tracks [count] samples of [input] and writes the loop phase θ, 0 ≤ θ < 2π, for every sample. */
    fun process(input: FloatArray, count: Int, phaseOut: FloatArray) = run(input, count, phaseOut, null, null)

    /** As [process], but writes sin θ and cos θ, which the loop computes anyway, instead of θ. */
    fun processSinCos(input: FloatArray, count: Int, sinOut: FloatArray, cosOut: FloatArray) =
        run(input, count, null, sinOut, cosOut)

    private fun run(input: FloatArray, count: Int, phaseOut: FloatArray?, sinOut: FloatArray?, cosOut: FloatArray?) {
        for (n in 0 until count) {
            val x = input[n].toDouble()
            val c = cos(phase)
            val s = sin(phase)
            // For x = A·cos(θ + φ), mixing with e^(−jθ) leaves (A/2)·e^(jφ) after the arm filter.
            aRe += armAlpha * (x * c - aRe)
            aIm += armAlpha * (-x * s - aIm)
            zRe += armAlpha * (aRe - zRe)
            zIm += armAlpha * (aIm - zIm)
            val err = atan2(zIm, zRe)
            val magnitudeSquared = zRe * zRe + zIm * zIm
            // cos(err) without a second trigonometric call; weak input counts as unlocked.
            val quality = if (magnitudeSquared <= minHalfAmplitudeSquared) 0.0 else zRe / sqrt(magnitudeSquared)
            lockMetric += lockAlpha * (quality - lockMetric)
            freqOffset = (freqOffset + ki * err).coerceIn(-maxOffset, maxOffset)
            if (phaseOut != null) {
                val p = phase.toFloat()
                phaseOut[n] = if (p < TWO_PI_FLOAT) p else 0f   // rounding to float must not reach 2π
            }
            if (sinOut != null && cosOut != null) {
                sinOut[n] = s.toFloat()
                cosOut[n] = c.toFloat()
            }
            phase += centerStep + freqOffset + kp * err
            if (phase >= twoPi) phase -= twoPi else if (phase < 0) phase += twoPi
        }
    }

    /** Drops the lock and all loop state, for use after a retune. */
    fun reset() {
        phase = 0.0
        freqOffset = 0.0
        aRe = 0.0
        aIm = 0.0
        zRe = 0.0
        zIm = 0.0
        lockMetric = 0.0
    }

    private companion object {
        /** The smallest float that is not below 2π. */
        val TWO_PI_FLOAT = (2 * PI).toFloat()
    }
}
