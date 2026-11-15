// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.BiquadCascade
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Pll
import kotlin.math.exp
import kotlin.math.log10

/**
 * Pilot-tone stereo decoder front half (§6.4): a PLL locks to the 19 kHz pilot and the multiplex
 * is multiplied by the regenerated 38 kHz subcarrier. The transmitted pilot is sin ωt and the
 * subcarrier sin 2ωt (ITU-R BS.450); the PLL reports θ with pilot = cos θ, so the subcarrier is
 * −sin 2θ. The output pairs (M, S·blend) still need the 15 kHz low-pass; L = M + S, R = M − S.
 *
 * The blend goes to mono without a locked pilot and when the difference channel is noisy. That
 * noise is measured where the transmitter puts nothing: in quadrature to the subcarrier (cos 2θ).
 */
class StereoDecoder(private val mpxRate: Double) {
    private val pll = Pll(mpxRate, 19_000.0)
    private val blendStep = (1.0 / (0.05 * mpxRate)).toFloat()   // full swing in 50 ms
    private val noiseFilter = BiquadCascade.lowPass(1, 4, NOISE_BAND_HZ, mpxRate)
    private var quadrature = FloatArray(0)
    private var noisePower = 1.0   // 0 dB: as noisy as it gets, until measured
    private var haveNoise = false

    /** sin θ of the pilot phase for each sample of the last block. */
    var pilotSin = FloatArray(0)
        private set

    /** cos θ of the pilot phase for each sample of the last block. */
    var pilotCos = FloatArray(0)
        private set

    /** Set to false to force mono. */
    var enabled = true

    /** 0 = mono … 1 = full stereo; ramps so that switching never clicks. */
    var blend = 0f
        private set

    val pilotLocked: Boolean get() = pll.locked

    /** Pilot amplitude in multiplex units (0.09 nominal). */
    val pilotLevel: Double get() = pll.amplitude

    /**
     * Noise of the difference channel within [NOISE_BAND_HZ], in dB relative to full deviation.
     * Stereo is full at [QUIET_DB] and below, and mono from [NOISY_DB].
     */
    val noiseDb: Double get() = 10 * log10(noisePower + 1e-30)

    /** Writes [count] interleaved (M, S) pairs for the multiplex samples in [mpx]. */
    fun process(mpx: FloatArray, count: Int, ms: FloatArray) {
        if (pilotSin.size < count) {
            pilotSin = FloatArray(count)
            pilotCos = FloatArray(count)
            quadrature = FloatArray(count)
        }
        val ps = pilotSin
        val pc = pilotCos
        pll.processSinCos(mpx, count, ps, pc)
        measureNoise(mpx, count)
        val quality = ((NOISY_DB - noiseDb) / (NOISY_DB - QUIET_DB)).coerceIn(0.0, 1.0).toFloat()
        val target = if (enabled && pll.locked) quality else 0f
        var b = blend
        for (n in 0 until count) {
            if (b < target) b = minOf(target, b + blendStep) else if (b > target) b = maxOf(target, b - blendStep)
            val x = mpx[n]
            ms[2 * n] = x
            ms[2 * n + 1] = x * (-4f * ps[n] * pc[n]) * b   // 2·(−sin 2θ) = −4·sin θ·cos θ
        }
        blend = b
    }

    /**
     * Demodulates with 2·cos 2θ, low-passes and averages the power over about 50 ms. Until the pilot
     * is locked the phase is meaningless and the programme itself would be measured, so the average
     * starts afresh at lock.
     */
    private fun measureNoise(mpx: FloatArray, count: Int) {
        if (count == 0) return
        val q = quadrature
        val ps = pilotSin
        val pc = pilotCos
        for (n in 0 until count) q[n] = mpx[n] * 2f * (pc[n] * pc[n] - ps[n] * ps[n])
        noiseFilter.process(q, count)
        if (!pll.locked) {
            haveNoise = false
            noisePower = 1.0
            return
        }
        var sum = 0.0
        for (n in 0 until count) sum += q[n] * q[n]
        val mean = sum / count
        if (haveNoise) {
            noisePower += (1 - exp(-count / (NOISE_SECONDS * mpxRate))) * (mean - noisePower)
        } else {
            noisePower = mean
            haveNoise = true
        }
    }

    fun reset() {
        pll.reset()
        noiseFilter.reset()
        haveNoise = false
        noisePower = 1.0
        blend = 0f
    }

    companion object {
        /** Width of the band the noise is measured in, in Hz. */
        const val NOISE_BAND_HZ = 2_000.0

        /** Noise up to which stereo is full: a carrier-to-noise ratio of about 28 dB in 240 kHz. */
        const val QUIET_DB = -52.0

        /** Noise from which the output is mono: a carrier-to-noise ratio of about 16 dB in 240 kHz. */
        const val NOISY_DB = -40.0
        private const val NOISE_SECONDS = 0.05
    }
}
