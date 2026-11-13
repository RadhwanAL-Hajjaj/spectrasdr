// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.exp

/** FM de-emphasis: one-pole low-pass with time constant [tauSeconds] (50 µs or 75 µs), per channel. */
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
    }
}
