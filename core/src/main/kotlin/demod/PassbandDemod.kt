// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Agc
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChain
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Nco
import kotlin.math.abs
import kotlin.math.floor

/**
 * Shared core of the SSB and CW demodulators (§6.4): the slice [centerHz] ± [halfWidthHz] of the
 * channel is moved to 0 Hz, isolated by a complex low-pass, moved back and its real part taken, so
 * every signal in the slice comes out at its own audio frequency and everything else is rejected.
 * The work is done at about 12 kHz to keep the steep filter short.
 */
open class PassbandDemod(
    final override val inputRate: Double,
    centerHz: Double,
    halfWidthHz: Double,
    transitionHz: Double,
    agc: Boolean,
) : Demodulator {
    private val decimation = maxOf(1, floor(inputRate / 12_000.0).toInt())
    final override val outputRate: Double = inputRate / decimation
    final override val channels: Int = 1

    private val chain = DecimatorChain.design(inputRate, decimation, abs(centerHz) + halfWidthHz + transitionHz)
    private val down = Nco(outputRate).apply { frequencyHz = -centerHz }
    private val up = Nco(outputRate).apply { frequencyHz = centerHz }
    private val filter = FirDecimator(FirDesign.lowPassKaiser(halfWidthHz, halfWidthHz + transitionHz, outputRate, 60.0), 1)
    private val agc = if (agc) Agc(outputRate) else null
    private var low = FloatArray(0)
    private var filtered = FloatArray(0)

    final override fun maxOutput(inCount: Int): Int = chain.maxOutput(inCount)

    final override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        val need = 2 * chain.maxOutput(inCount)
        if (low.size < need) {
            low = FloatArray(need)
            filtered = FloatArray(need)
        }
        val n = chain.process(input, inCount, low)
        down.mix(low, 0, n)
        filter.process(low, n, filtered)
        up.mix(filtered, 0, n)
        for (i in 0 until n) output[i] = filtered[2 * i]
        agc?.process(output, n)
        return n
    }

    final override fun reset() {
        chain.reset()
        filter.reset()
        agc?.reset()
    }
}

enum class Sideband { USB, LSB }

/** Single-sideband demodulator for the 300–2,800 Hz voice passband above (USB) or below (LSB) the carrier. */
class SsbDemod(inputRate: Double, sideband: Sideband, agc: Boolean = true) :
    PassbandDemod(inputRate, if (sideband == Sideband.USB) 1_550.0 else -1_550.0, 1_250.0, 300.0, agc)

/**
 * CW demodulator: a carrier placed [pitchHz] above the channel centre is heard as a tone of that
 * pitch through a filter [bandwidthHz] wide. The receiver tunes the channel [pitchHz] below the carrier.
 */
class CwDemod(inputRate: Double, pitchHz: Double = 700.0, bandwidthHz: Double = 500.0, agc: Boolean = true) :
    PassbandDemod(inputRate, pitchHz, bandwidthHz / 2, maxOf(100.0, bandwidthHz / 4), agc)
