// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class BiquadTest {
    private val fs = 48_000.0

    /** Gain in dB of a filter for a tone at [hz], measured after the transient. */
    private fun gainDb(filter: BiquadCascade, hz: Double): Double {
        val n = 48_000
        val x = Signals.realTone(n, hz, fs)
        filter.reset()
        filter.process(x, n)
        return 20 * log10(Signals.toneAmplitude(x, hz, fs, n / 2, n))
    }

    @Test
    fun lowPassFollowsTheButterworthCurve() {
        val lp = BiquadCascade.lowPass(1, 4, 3_500.0, fs)
        assertEquals(0.0, gainDb(lp, 500.0), 0.1)
        assertEquals(-3.0, gainDb(lp, 3_500.0), 0.2)
        assertEquals(-24.1, gainDb(lp, 7_000.0), 2.0)
        assertTrue(gainDb(lp, 14_000.0) < -45.0)
    }

    @Test
    fun highPassFollowsTheButterworthCurve() {
        val hp = BiquadCascade.highPass(1, 4, 300.0, fs)
        assertEquals(0.0, gainDb(hp, 3_000.0), 0.1)
        assertEquals(-3.0, gainDb(hp, 300.0), 0.2)
        assertEquals(-24.1, gainDb(hp, 150.0), 1.0)
        assertTrue(gainDb(hp, 100.0) < -36.0)
    }

    @Test
    fun channelsAreIndependent() {
        val lp = BiquadCascade.lowPass(2, 2, 1_000.0, fs)
        val n = 4_800
        val x = FloatArray(2 * n) { if (it % 2 == 0) 1f else 0f }
        lp.process(x, n)
        assertEquals(1f, x[2 * (n - 1)], 1e-3f)
        for (i in 0 until n) assertEquals(0f, x[2 * i + 1], 0f)
    }

    @Test
    fun recoversAfterANonFiniteBlock() {
        val lp = BiquadCascade.lowPass(1, 4, 3_500.0, fs)
        val bad = FloatArray(64) { Float.NaN }
        lp.process(bad, bad.size)
        val x = FloatArray(4_800) { 1f }
        lp.process(x, x.size)
        assertEquals(1f, x[x.size - 1], 1e-3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddOrders() {
        BiquadCascade.lowPass(1, 3, 1_000.0, fs)
    }
}
