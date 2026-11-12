// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10

class DeemphasisTest {
    private fun gainDb(freqHz: Double, fs: Double, tau: Double): Double {
        val n = (fs / 4).toInt()
        val x = Signals.realTone(n, freqHz, fs)
        Deemphasis(1, tau, fs).process(x, n)
        return 20 * log10(Signals.peak(x, n / 2, n).toDouble())
    }

    @Test
    fun fiftyMicrosecondResponse() {
        val fs = 240_000.0
        val tau = 50e-6
        assertEquals(0.0, gainDb(100.0, fs, tau), 0.1)
        assertEquals(-3.0, gainDb(1 / (2 * PI * tau), fs, tau), 0.3)
        assertEquals(-10.4, gainDb(10_000.0, fs, tau), 0.5)
    }

    @Test
    fun channelsAreIndependent() {
        val x = FloatArray(2_000) { if (it % 2 == 0) 1f else 0f }
        Deemphasis(2, 75e-6, 240_000.0).process(x, 1_000)
        assertEquals(1f, x[1_998], 1e-3f)
        assertEquals(0f, x[1_999], 0f)
    }
}
