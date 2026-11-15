// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun recoversInTheBlockAfterASampleThatIsNotANumber() {
        val filter = Deemphasis(1, 50e-6, 48_000.0)
        val bad = FloatArray(100) { 0.5f }
        bad[10] = Float.NaN
        filter.process(bad, bad.size)
        val good = FloatArray(1_000) { 0.5f }
        filter.process(good, good.size)
        assertTrue(good.all { it.isFinite() })
        assertEquals(0.5f, good[999], 1e-4f)
    }

    @Test
    fun silenceLeavesTheStateAtExactlyZero() {
        val filter = Deemphasis(1, 50e-6, 48_000.0)
        val x = FloatArray(100) { 0.5f }
        filter.process(x, x.size)
        val silence = FloatArray(480)
        repeat(3) {
            silence.fill(0f)
            filter.process(silence, silence.size)
        }
        assertEquals(0f, silence[479], 0f)
        val impulse = floatArrayOf(0f)
        filter.process(impulse, 1)
        assertEquals(0f, impulse[0], 0f)
    }

    @Test
    fun resetClearsTheState() {
        val filter = Deemphasis(2, 50e-6, 48_000.0)
        val x = FloatArray(200) { 1f }
        filter.process(x, 100)
        filter.reset()
        val y = floatArrayOf(0f, 0f)
        filter.process(y, 1)
        assertEquals(0f, y[0], 0f)
        assertEquals(0f, y[1], 0f)
    }
}
