// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DriftController
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FractionalResampler
import kotlin.math.abs
import kotlin.math.tanh

/**
 * The last stage before the sink (§6.5): resampling to the sink's rate with drift correction that
 * holds the sink's buffer near half full, then volume, mute and a soft limiter. Used from the DSP
 * thread only.
 */
class AudioOutput(private val sink: AudioSink, private val inputRate: Double, private val channels: Int) {
    private val resampler = FractionalResampler(channels, inputRate, sink.sampleRate.toDouble())
    private val drift = DriftController()
    private val gainStep = (1.0 / (0.01 * sink.sampleRate)).toFloat()   // full swing in 10 ms
    private var gain = 0f
    private var out = FloatArray(0)

    /** Linear volume, 0–1. */
    var volume = 1f

    var muted = false

    /** Resamples [frames] interleaved frames of [audio] and hands them to the sink. */
    fun write(audio: FloatArray, frames: Int) {
        if (frames == 0) return
        resampler.correction = drift.update(sink.fillLevel().toDouble(), frames / inputRate)
        val need = channels * (resampler.maxOutput(frames) + 8)
        if (out.size < need) out = FloatArray(need)
        val n = resampler.process(audio, frames, out)
        val target = if (muted) 0f else volume.coerceIn(0f, 1f)
        var g = gain
        var i = 0
        for (f in 0 until n) {
            if (g < target) g = minOf(target, g + gainStep) else if (g > target) g = maxOf(target, g - gainStep)
            for (c in 0 until channels) {
                val x = out[i] * g
                out[i++] = if (abs(x) <= KNEE) x else limit(x)
            }
        }
        gain = g
        sink.write(out, n, channels)
    }

    private companion object {
        const val KNEE = 0.7f

        /** Soft limiter: unity gain up to the knee, then a smooth approach to ±1. */
        fun limit(x: Float): Float {
            if (x != x) return 0f   // not a number: silence instead of whatever a sink makes of it
            val over = (abs(x) - KNEE) / (1 - KNEE)
            val y = KNEE + (1 - KNEE) * tanh(over)
            return if (x < 0) -y else y
        }
    }
}
