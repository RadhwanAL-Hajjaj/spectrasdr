// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class FirDecimatorTest {
    @Test
    fun impulseResponseEqualsTheTaps() {
        val taps = floatArrayOf(0.1f, 0.2f, 0.4f, 0.2f, 0.1f)
        val d = FirDecimator(taps, 1)
        val x = FloatArray(16)
        x[0] = 1f
        val y = FloatArray(16)
        assertEquals(8, d.process(x, 8, y))
        for (k in 0 until 5) assertEquals(taps[k], y[2 * k], 1e-7f)
        for (k in 5 until 8) assertEquals(0f, y[2 * k], 1e-7f)
    }

    @Test
    fun keepsEveryFactorthOutput() {
        val delayTwo = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        val d = FirDecimator(delayTwo, 3)
        val x = FloatArray(18) { if (it % 2 == 0) (it / 2).toFloat() else 0f }   // I = 0..8
        val y = FloatArray(8)
        assertEquals(3, d.process(x, 9, y))
        assertEquals(listOf(0f, 1f, 4f), listOf(y[0], y[2], y[4]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAsymmetricTaps() {
        FirDecimator(floatArrayOf(1f, 2f, 3f), 2)
    }
}
