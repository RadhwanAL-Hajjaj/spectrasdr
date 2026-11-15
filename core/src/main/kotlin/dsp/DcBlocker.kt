// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * One-pole DC blocker y[n] = x[n] − x[n−1] + a·y[n−1], per interleaved channel (§6.1). After every
 * block, state that is not a number or has decayed to almost nothing is cleared, so one bad sample
 * cannot poison the filter and silence does not leave it grinding through denormal numbers.
 */
class DcBlocker(private val channels: Int, cutoffHz: Double, sampleRate: Double) {
    private val a = exp(-2 * PI * cutoffHz / sampleRate).toFloat()
    private val prevX = FloatArray(channels)
    private val prevY = FloatArray(channels)

    /** Filters [frames] frames of [data] in place. */
    fun process(data: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = data[i]
                val y = x - prevX[c] + a * prevY[c]
                prevX[c] = x
                prevY[c] = y
                data[i] = y
                i++
            }
        }
        for (c in 0 until channels) {
            // Written so that NaN fails the tests too.
            if (!(abs(prevX[c]) <= Float.MAX_VALUE)) prevX[c] = 0f
            val y = abs(prevY[c])
            if (!(y >= TINY && y <= Float.MAX_VALUE)) prevY[c] = 0f
        }
    }

    fun reset() {
        prevX.fill(0f)
        prevY.fill(0f)
    }

    private companion object {
        const val TINY = 1e-30f
    }
}
