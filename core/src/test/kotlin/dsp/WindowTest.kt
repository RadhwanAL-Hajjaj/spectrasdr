// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

class WindowTest {
    @Test
    fun hannIsPeriodic() {
        val w = Windows.create(WindowType.HANN, 8)
        assertEquals(0f, w[0], 1e-7f)
        assertEquals(1f, w[4], 1e-6f)
        assertEquals(w[1], w[7], 1e-6f)
    }

    @Test
    fun blackmanHarrisGainAndPeak() {
        val n = 4096
        val w = Windows.create(WindowType.BLACKMAN_HARRIS, n)
        assertEquals(0.35875, Windows.sum(w) / n, 1e-4)
        assertEquals(1.0, w[n / 2].toDouble(), 1e-4)
    }

    @Test
    fun blackmanHarrisLeakageIsBelowMinus90dB() {
        val n = 1024
        val w = Windows.create(WindowType.BLACKMAN_HARRIS, n)
        val x = FloatArray(2 * n)
        for (t in 0 until n) {
            val a = 2 * PI * 100.5 * t / n
            x[2 * t] = (cos(a) * w[t]).toFloat()
            x[2 * t + 1] = (sin(a) * w[t]).toFloat()
        }
        Fft(n).transform(x)
        fun db(k: Int) = 20 * log10(hypot(x[2 * k].toDouble(), x[2 * k + 1].toDouble()) + 1e-30)
        val peak = max(db(100), db(101))
        assertTrue(db(109) - peak < -90)
        assertTrue(db(92) - peak < -90)
    }
}
