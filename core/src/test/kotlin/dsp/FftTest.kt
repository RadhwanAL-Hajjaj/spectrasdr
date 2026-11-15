// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class FftTest {
    private fun naiveDft(x: FloatArray, n: Int): DoubleArray {
        val out = DoubleArray(2 * n)
        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                re += x[2 * t] * cos(a) - x[2 * t + 1] * sin(a)
                im += x[2 * t] * sin(a) + x[2 * t + 1] * cos(a)
            }
            out[2 * k] = re
            out[2 * k + 1] = im
        }
        return out
    }

    @Test
    fun matchesNaiveDftForAllSizes() {
        val rnd = Random(42)
        var size = 8
        while (size <= 4096) {
            val x = FloatArray(2 * size) { rnd.nextFloat() * 2 - 1 }
            val expected = naiveDft(x, size)
            val y = x.copyOf()
            Fft(size).transform(y)
            var maxErr = 0.0
            var maxMag = 0.0
            for (i in 0 until 2 * size) {
                maxErr = max(maxErr, abs(y[i] - expected[i]))
                maxMag = max(maxMag, abs(expected[i]))
            }
            assertTrue("size $size relative error ${maxErr / maxMag}", maxErr / maxMag < 1e-4)
            size *= 2
        }
    }

    @Test
    fun complexToneLandsInItsBin() {
        val n = 1024
        val x = Signals.complexTone(n, 100.0, n.toDouble())
        Fft(n).transform(x)
        fun mag(k: Int) = hypot(x[2 * k].toDouble(), x[2 * k + 1].toDouble())
        assertEquals(n.toDouble(), mag(100), 1e-2)
        assertTrue(mag(101) < 1e-2)
        assertTrue(mag(n - 100) < 1e-2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSizesThatAreNotPowersOfTwo() {
        Fft(1000)
    }
}
