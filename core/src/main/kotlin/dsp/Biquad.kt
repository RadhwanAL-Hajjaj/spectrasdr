// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Even-order Butterworth low-pass or high-pass built from second-order sections (RBJ cookbook
 * coefficients), applied to each interleaved channel. State is kept in double precision so low
 * cutoffs stay stable; denormal and non-finite state is flushed after every block.
 */
class BiquadCascade private constructor(private val channels: Int, private val c: DoubleArray) {
    private val sections = c.size / 5
    private val z = DoubleArray(sections * channels * 2)

    /** Filters [frames] frames of [data] in place. */
    fun process(data: FloatArray, frames: Int) {
        for (ch in 0 until channels) {
            for (s in 0 until sections) {
                val b0 = c[5 * s]
                val b1 = c[5 * s + 1]
                val b2 = c[5 * s + 2]
                val a1 = c[5 * s + 3]
                val a2 = c[5 * s + 4]
                val zi = 2 * (s * channels + ch)
                var z1 = z[zi]
                var z2 = z[zi + 1]
                var i = ch
                for (n in 0 until frames) {
                    val x = data[i].toDouble()
                    val y = b0 * x + z1
                    z1 = b1 * x - a1 * y + z2
                    z2 = b2 * x - a2 * y
                    data[i] = y.toFloat()
                    i += channels
                }
                // Written so that NaN fails the test too: a bad block cannot poison the filter.
                z[zi] = if (abs(z1) >= 1e-30 && abs(z1) < 1e30) z1 else 0.0
                z[zi + 1] = if (abs(z2) >= 1e-30 && abs(z2) < 1e30) z2 else 0.0
            }
        }
    }

    fun reset() = z.fill(0.0)

    companion object {
        /** Butterworth low-pass of even [order] with its −3 dB point at [cutoffHz]. */
        fun lowPass(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double): BiquadCascade =
            design(channels, order, cutoffHz, sampleRate, highPass = false)

        /** Butterworth high-pass of even [order] with its −3 dB point at [cutoffHz]. */
        fun highPass(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double): BiquadCascade =
            design(channels, order, cutoffHz, sampleRate, highPass = true)

        private fun design(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double, highPass: Boolean): BiquadCascade {
            require(channels >= 1) { "channels must be >= 1" }
            require(order >= 2 && order % 2 == 0) { "order must be even and >= 2" }
            require(cutoffHz > 0 && cutoffHz < sampleRate / 2) { "cutoff $cutoffHz Hz is outside 0..${sampleRate / 2} Hz" }
            val w0 = 2 * PI * cutoffHz / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val c = DoubleArray(5 * (order / 2))
            for (k in 0 until order / 2) {
                val q = 1 / (2 * cos(PI * (2 * k + 1) / (2.0 * order)))
                val alpha = sw / (2 * q)
                val a0 = 1 + alpha
                val b1 = if (highPass) -(1 + cw) else 1 - cw
                val b0 = abs(b1) / 2
                c[5 * k] = b0 / a0
                c[5 * k + 1] = b1 / a0
                c[5 * k + 2] = b0 / a0
                c[5 * k + 3] = -2 * cw / a0
                c[5 * k + 4] = (1 - alpha) / a0
            }
            return BiquadCascade(channels, c)
        }
    }
}
