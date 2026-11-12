// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Second-order PLL that locks to a real sinusoid near [centerHz] (the 19 kHz stereo pilot, §6.4).
 * The input is mixed to baseband and low-passed by a two-pole arm filter, so the phase error is
 * amplitude-independent and signals more than a few kHz away do not disturb the loop. When locked,
 * input ≈ amplitude · cos(θ), where θ is written to phaseOut for every sample.
 */
class Pll(
    private val sampleRate: Double,
    private val centerHz: Double,
    loopBandwidthHz: Double = 10.0,
    maxDeviationHz: Double = 20.0,
    armFilterHz: Double = 400.0,
    damping: Double = 0.707,
) {
    private val twoPi = 2 * PI
    private val armAlpha = 1 - exp(-twoPi * armFilterHz / sampleRate)
    private val lockAlpha = 1 - exp(-twoPi * 2.0 / sampleRate)
    private val centerStep = twoPi * centerHz / sampleRate
    private val maxOffset = twoPi * maxDeviationHz / sampleRate
    private val kp: Double
    private val ki: Double
    private var phase = 0.0
    private var freqOffset = 0.0
    private var aRe = 0.0
    private var aIm = 0.0
    private var zRe = 0.0
    private var zIm = 0.0
    private var lockMetric = 0.0
    private var halfAmplitude = 0.0

    init {
        val wn = twoPi * loopBandwidthHz / sampleRate
        kp = 2 * damping * wn
        ki = wn * wn
    }

    /** True while the loop holds phase lock. */
    val locked: Boolean get() = lockMetric > 0.8

    /** Estimated amplitude of the tracked sinusoid. */
    val amplitude: Double get() = 2 * halfAmplitude

    /** Tracked frequency in Hz. */
    val frequencyHz: Double get() = (centerStep + freqOffset) * sampleRate / twoPi

    fun process(input: FloatArray, count: Int, phaseOut: FloatArray) {
        for (n in 0 until count) {
            val x = input[n].toDouble()
            // For x = A·cos(θ + φ), mixing with e^(−jθ) leaves (A/2)·e^(jφ) after the arm filter.
            aRe += armAlpha * (x * cos(phase) - aRe)
            aIm += armAlpha * (-x * sin(phase) - aIm)
            zRe += armAlpha * (aRe - zRe)
            zIm += armAlpha * (aIm - zIm)
            val err = atan2(zIm, zRe)
            halfAmplitude = hypot(zRe, zIm)
            lockMetric += lockAlpha * (cos(err) - lockMetric)
            freqOffset = (freqOffset + ki * err).coerceIn(-maxOffset, maxOffset)
            phaseOut[n] = phase.toFloat()
            phase += centerStep + freqOffset + kp * err
            if (phase >= twoPi) phase -= twoPi else if (phase < 0) phase += twoPi
        }
    }
}
