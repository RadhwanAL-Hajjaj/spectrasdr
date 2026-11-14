// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.BiquadCascade
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Deemphasis
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Narrow-band FM demodulator (§6.4): discriminator scaled so that ±[deviationHz] is ±1.0, a 300 Hz
 * high-pass that removes CTCSS tones, a 3.5 kHz low-pass and optional de-emphasis. The de-emphasis
 * is the 6 dB/octave slope above 300 Hz that two-way radios pre-emphasise for, with its gain made
 * up so that a 1 kHz tone keeps its level.
 */
class NfmDemod(
    override val inputRate: Double,
    deviationHz: Double = 5_000.0,
    deemphasis: Boolean = true,
) : Demodulator {
    override val outputRate: Double = inputRate
    override val channels: Int = 1

    private val discriminator = FmDiscriminator(inputRate, deviationHz)
    private val highPass = BiquadCascade.highPass(1, 4, 300.0, inputRate)
    private val lowPass = BiquadCascade.lowPass(1, 4, 3_500.0, inputRate)
    private val deemphasisFilter = if (deemphasis) Deemphasis(1, 1 / (2 * PI * CORNER_HZ), inputRate) else null
    private val makeUp = sqrt(1 + (1_000.0 / CORNER_HZ) * (1_000.0 / CORNER_HZ)).toFloat()

    override fun maxOutput(inCount: Int): Int = inCount

    override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        discriminator.process(input, inCount, output)
        highPass.process(output, inCount)
        lowPass.process(output, inCount)
        if (deemphasisFilter != null) {
            deemphasisFilter.process(output, inCount)
            for (i in 0 until inCount) output[i] *= makeUp
        }
        return inCount
    }

    override fun reset() {
        discriminator.reset()
        highPass.reset()
        lowPass.reset()
        deemphasisFilter?.reset()
    }

    private companion object {
        const val CORNER_HZ = 300.0
    }
}
