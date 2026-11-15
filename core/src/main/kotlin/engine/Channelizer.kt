// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChain
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Nco
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

/**
 * Selects one channel from the IQ stream (§6.3): an NCO moves the wanted signal to 0 Hz, a
 * multistage decimator brings the rate down and a channel filter of ±[passbandHz] isolates it.
 * The channel power after the filter feeds the squelch and the S-meter.
 */
class Channelizer(inputRate: Double, decimation: Int, passbandHz: Double, stopbandHz: Double) {
    private val nco = Nco(inputRate)
    private val chain: DecimatorChain
    private val filter: FirDecimator
    private var scratch = FloatArray(0)

    /** Sample rate of the channel in Hz. */
    val outputRate: Double = inputRate / decimation

    /** Width of white noise that passes the channel filter, in Hz (both sides together). */
    val noiseBandwidthHz: Double

    init {
        // Leave room below Nyquist when the channel is as wide as the stream allows.
        val pass = minOf(passbandHz, 0.40 * outputRate)
        val stop = minOf(maxOf(stopbandHz, pass * 1.05), 0.49 * outputRate)
        chain = DecimatorChain.design(inputRate, decimation, pass)
        filter = FirDecimator(FirDesign.lowPassKaiser(pass, stop, outputRate, 60.0), 1)
        noiseBandwidthHz = pass + stop   // the filter is half way down midway between its two edges
    }

    /** Offset of the wanted signal from the centre of the IQ stream, in Hz. */
    var offsetHz = 0.0
        set(value) {
            field = value
            nco.frequencyHz = -value
        }

    /** Mean power of the last block of the channel in dBFS (a full-scale tone is 0). */
    var powerDb = Double.NEGATIVE_INFINITY
        private set

    fun maxOutput(inCount: Int): Int = chain.maxOutput(inCount)

    /** Channelizes [inCount] complex samples from [input], which is overwritten; returns the output count. */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        val need = 2 * chain.maxOutput(inCount)
        if (scratch.size < need) scratch = FloatArray(need)
        nco.mix(input, 0, inCount)
        val n = chain.process(input, inCount, scratch)
        filter.process(scratch, n, output)
        var sum = 0.0
        for (i in 0 until 2 * n) sum += output[i] * output[i]
        powerDb = if (n > 0) 10 * log10(sum / n + 1e-30) else Double.NEGATIVE_INFINITY
        return n
    }

    fun reset() {
        chain.reset()
        filter.reset()
    }

    companion object {
        /** The channelizer for [mode] with a channel [bandwidthHz] wide. */
        fun forMode(inputRate: Double, mode: Mode, bandwidthHz: Int, cwPitchHz: Double = 700.0): Channelizer = when (mode) {
            Mode.WFM -> Channelizer(inputRate, wideDecimation(inputRate), bandwidthHz / 2.0, bandwidthHz * 0.6)
            Mode.NFM, Mode.AM -> Channelizer(inputRate, narrowDecimation(inputRate), bandwidthHz / 2.0, bandwidthHz * 0.625)
            Mode.USB, Mode.LSB -> Channelizer(inputRate, narrowDecimation(inputRate), 3_200.0, 4_000.0)
            Mode.CW -> Channelizer(inputRate, narrowDecimation(inputRate), cwPitchHz + bandwidthHz, cwPitchHz + bandwidthHz + 800.0)
        }

        /** Broadcast FM runs at 240–480 kHz; a stream below 240 kHz is not decimated. */
        fun wideDecimation(inputRate: Double): Int = decimationFor(inputRate, 240_000.0, 480_000.0)

        /** Narrow modes run at 48–96 kHz. */
        fun narrowDecimation(inputRate: Double): Int = decimationFor(inputRate, 48_000.0, 96_000.0)

        /**
         * The decimation that brings [inputRate] into [minRate]..[maxRate]: the largest factor that
         * splits into stages of at most 8, so that no stage needs an oversized filter, or simply the
         * largest factor when none does. 1 when the stream is already below twice [minRate].
         */
        fun decimationFor(inputRate: Double, minRate: Double, maxRate: Double): Int {
            val largest = floor(inputRate / minRate).toInt()
            if (largest <= 1) return 1
            val smallest = maxOf(1, ceil(inputRate / maxRate).toInt())
            for (d in largest downTo smallest) {
                if (DecimatorChain.planStages(d).all { it <= 8 }) return d
            }
            return largest
        }
    }
}
