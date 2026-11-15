// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Deemphasis
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import kotlin.math.floor

/**
 * Broadcast FM demodulator (§6.4): discriminator, pilot-tone stereo decoder, 15 kHz low-pass and
 * de-emphasis. M and S travel as the real and imaginary part of one complex stream through one
 * decimating filter, so their delays match exactly. The output is always two channels; without a
 * locked pilot both carry the mono signal.
 */
class WfmDemod(
    override val inputRate: Double,
    deemphasisSeconds: Double = 50e-6,
) : Demodulator {
    init {
        require(inputRate >= 120_000.0) { "WFM needs at least 120 kHz of input rate, got $inputRate Hz" }
    }

    private val decimation = maxOf(1, floor(inputRate / 48_000.0).toInt())
    override val outputRate: Double = inputRate / decimation
    override val channels: Int = 2

    private val discriminator = FmDiscriminator(inputRate, 75_000.0)
    private val stereoDecoder = StereoDecoder(inputRate)
    private val audioFilter = FirDecimator(FirDesign.lowPassKaiser(15_000.0, 19_000.0, inputRate, 60.0), decimation)
    private val deemphasis = Deemphasis(2, deemphasisSeconds, outputRate)
    private var mpx = FloatArray(0)
    private var ms = FloatArray(0)

    /** When set, the multiplex is also handed to this tap (the RDS demodulator). */
    var tap: MultiplexTap? = null

    /** False forces mono even when a pilot is present. */
    var stereoEnabled: Boolean
        get() = stereoDecoder.enabled
        set(value) {
            stereoDecoder.enabled = value
        }

    /** True while the output is (mostly) stereo. */
    val stereo: Boolean get() = stereoDecoder.blend >= 0.5f

    val pilotLocked: Boolean get() = stereoDecoder.pilotLocked

    /** 0 = mono … 1 = full stereo. */
    val stereoBlend: Float get() = stereoDecoder.blend

    /** Noise of the stereo difference channel in dB relative to full deviation (see [StereoDecoder.noiseDb]). */
    val stereoNoiseDb: Double get() = stereoDecoder.noiseDb

    override fun maxOutput(inCount: Int): Int = audioFilter.maxOutput(inCount)

    override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        if (mpx.size < inCount) {
            mpx = FloatArray(inCount)
            ms = FloatArray(2 * inCount)
        }
        discriminator.process(input, inCount, mpx)
        stereoDecoder.process(mpx, inCount, ms)
        tap?.process(mpx, stereoDecoder.pilotSin, stereoDecoder.pilotCos, stereoDecoder.pilotLocked, inCount)
        val frames = audioFilter.process(ms, inCount, output)
        for (i in 0 until frames) {
            val m = output[2 * i]
            val s = output[2 * i + 1]
            output[2 * i] = m + s
            output[2 * i + 1] = m - s
        }
        deemphasis.process(output, frames)
        return frames
    }

    override fun reset() {
        discriminator.reset()
        stereoDecoder.reset()
        audioFilter.reset()
        deemphasis.reset()
        tap?.reset()
    }
}
