// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polyphase windowed-sinc resampler for interleaved real audio (§6.5). Coefficients are linearly
 * interpolated between [phases] phases; [correction] trims the ratio at run time for drift control.
 * Callers band-limit the input to [passbandHz] beforehand (every demodulator does).
 */
class FractionalResampler(
    val channels: Int,
    val inputRate: Double,
    val outputRate: Double,
    passbandHz: Double = 0.4 * minOf(inputRate, outputRate),
    attenuationDb: Double = 80.0,
    private val phases: Int = 128,
) {
    /** Relative trim of the input step: +0.001 consumes 0.1 % more input per output frame. */
    var correction = 0.0

    private val baseStep = inputRate / outputRate
    private val tapsPerPhase: Int
    private val table: Array<FloatArray>
    private var buf = FloatArray(0)
    private var pos: Double

    init {
        require(channels >= 1 && inputRate > 0 && outputRate > 0)
        val edge = minOf(inputRate, outputRate)
        val stopbandHz = edge - passbandHz
        require(stopbandHz > passbandHz) { "passband too wide for the lower rate" }
        val protoRate = inputRate * phases
        val n = FirDesign.kaiserTaps(stopbandHz - passbandHz, protoRate, attenuationDb)
        tapsPerPhase = (n + phases - 1) / phases
        val len = tapsPerPhase * phases
        val cutoff = (passbandHz + stopbandHz) / 2 / protoRate
        val beta = FirDesign.kaiserBeta(attenuationDb)
        val i0Beta = FirDesign.besselI0(beta)
        val centre = (len - 1) / 2.0
        val proto = DoubleArray(len) { k ->
            val x = k - centre
            val sinc = sin(2 * PI * cutoff * x) / (PI * x)   // x is never 0: len is even
            val r = x / centre
            sinc * FirDesign.besselI0(beta * sqrt(maxOf(0.0, 1 - r * r))) / i0Beta
        }
        val gain = phases / proto.sum()
        table = Array(phases + 1) { p ->
            FloatArray(tapsPerPhase) { j ->
                val idx = j * phases + p
                if (idx < len) (proto[idx] * gain).toFloat() else 0f
            }
        }
        pos = (tapsPerPhase - 1).toDouble()
    }

    /** Upper bound of output frames for [frames] input frames at the current correction. */
    fun maxOutput(frames: Int): Int = (frames / (baseStep * (1 - abs(correction) - 1e-3))).toInt() + 2

    /** Resamples [frames] interleaved frames from [input] into [output]; returns output frames. */
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        val hist = tapsPerPhase - 1
        val need = channels * (hist + frames)
        if (buf.size < need) buf = buf.copyOf(need)
        System.arraycopy(input, 0, buf, channels * hist, channels * frames)
        val step = baseStep * (1 + correction)
        val last = hist + frames - 1
        var out = 0
        var p = pos
        while (true) {   // p = pos + out * step (not p += step) keeps rounding error from piling up
            val n = floor(p + SNAP).toInt()   // snap: accumulated rounding must not depend on block splits
            if (n > last) break
            val ph = maxOf(0.0, p - n) * phases
            val pi = ph.toInt()
            val frac = (ph - pi).toFloat()
            val h0 = table[pi]
            val h1 = table[pi + 1]
            for (c in 0 until channels) {
                var acc = 0f
                var idx = n * channels + c
                for (j in 0 until tapsPerPhase) {
                    acc += (h0[j] + (h1[j] - h0[j]) * frac) * buf[idx]
                    idx -= channels
                }
                output[out * channels + c] = acc
            }
            out++
            p = pos + out * step
        }
        pos = p - frames
        System.arraycopy(buf, channels * frames, buf, 0, channels * hist)
        return out
    }

    private companion object {
        const val SNAP = 1e-9
    }
}
