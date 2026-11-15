// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs

/**
 * Decimating FIR for interleaved complex samples with real, odd-length, symmetric taps. Only the
 * kept outputs are computed, and symmetric taps are folded to halve the multiplies (§4.4).
 */
class FirDecimator(taps: FloatArray, val factor: Int) {
    private val n = taps.size
    private val mid = (n - 1) / 2
    private val h: FloatArray
    private var buf: FloatArray
    private var phase = 0

    init {
        require(factor >= 1) { "factor must be >= 1" }
        require(n % 2 == 1) { "taps must have odd length" }
        val scale = taps.maxOf { abs(it) }
        for (i in 0 until mid) {
            require(abs(taps[i] - taps[n - 1 - i]) <= 1e-6f * scale) { "taps must be symmetric" }
        }
        h = FloatArray(n) { (taps[it] + taps[n - 1 - it]) / 2 }
        buf = FloatArray(2 * (n - 1 + 4096))
    }

    /** Upper bound of the outputs produced for [inCount] inputs. */
    fun maxOutput(inCount: Int) = inCount / factor + 1

    /** Filters and decimates [inCount] complex samples from [input] into [output]; returns the output count. */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        val hist = n - 1
        val need = 2 * (hist + inCount)
        if (buf.size < need) buf = buf.copyOf(need)
        val b = buf
        System.arraycopy(input, 0, b, 2 * hist, 2 * inCount)
        var out = 0
        var i = phase
        while (i < inCount) {
            var re = 0f
            var im = 0f
            var lo = 2 * i
            var hi = 2 * (i + n - 1)
            for (t in 0 until mid) {
                val c = h[t]
                re += c * (b[lo] + b[hi])
                im += c * (b[lo + 1] + b[hi + 1])
                lo += 2
                hi -= 2
            }
            val c = h[mid]
            re += c * b[lo]
            im += c * b[lo + 1]
            output[2 * out] = re
            output[2 * out + 1] = im
            out++
            i += factor
        }
        phase = i - inCount
        System.arraycopy(b, 2 * inCount, b, 0, 2 * hist)
        return out
    }

    fun reset() {
        buf.fill(0f)
        phase = 0
    }
}
