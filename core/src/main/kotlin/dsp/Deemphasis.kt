// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs
import kotlin.math.exp

/**
 * FM de-emphasis: one-pole low-pass with time constant [tauSeconds] (50 µs or 75 µs), per channel.
 * After every block, state that is not a number or has decayed to almost nothing is cleared.
 */
class Deemphasis(private val channels: Int, tauSeconds: Double, sampleRate: Double) {
    private val alpha = (1 - exp(-1 / (sampleRate * tauSeconds))).toFloat()
    private val state = FloatArray(channels)

    fun process(data: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                state[c] += alpha * (data[i] - state[c])
                data[i] = state[c]
                i++
            }
        }
        for (c in 0 until channels) {
            val s = abs(state[c])
            if (!(s >= TINY && s <= Float.MAX_VALUE)) state[c] = 0f   // also clears NaN
        }
    }

    fun reset() = state.fill(0f)

    private companion object {
        const val TINY = 1e-30f
    }
}
