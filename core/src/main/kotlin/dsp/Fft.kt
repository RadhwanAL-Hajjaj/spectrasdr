// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT on interleaved I, Q arrays. Forward transform, unscaled. */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two >= 2" }
    }

    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { (-sin(2.0 * PI * it / size)).toFloat() }
    private val bitReverse = IntArray(size).also { rev ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Transforms data[0 until 2 * size] in place. */
    fun transform(data: FloatArray) {
        require(data.size >= 2 * size) { "data holds fewer than $size complex samples" }
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val re = data[2 * i]
                val im = data[2 * i + 1]
                data[2 * i] = data[2 * j]
                data[2 * i + 1] = data[2 * j + 1]
                data[2 * j] = re
                data[2 * j + 1] = im
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val a = 2 * (start + j)
                    val b = 2 * (start + j + half)
                    val br = data[b]
                    val bi = data[b + 1]
                    val tr = br * wr - bi * wi
                    val ti = br * wi + bi * wr
                    data[b] = data[a] - tr
                    data[b + 1] = data[a + 1] - ti
                    data[a] += tr
                    data[a + 1] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
