// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class PllTest {
    private val fs = 240_000.0

    @Test
    fun locksToAnOffsetPilotAmongOtherSignals() {
        val n = 240_000
        val rnd = Random(1)
        val x = FloatArray(n) { i ->
            val t = i / fs
            (0.09 * cos(2 * PI * 19_003.0 * t + 1.0) +                      // pilot, 3 Hz off nominal
                0.4 * sin(2 * PI * 1_000.0 * t) +                          // audio
                0.2 * sin(2 * PI * 38_000.0 * t) * sin(2 * PI * 2_000.0 * t) + // stereo sidebands
                0.02 * rnd.nextGaussian()).toFloat()
        }
        val pll = Pll(fs, 19_000.0)
        val phase = FloatArray(n)
        pll.process(x, n, phase)
        assertTrue(pll.locked)
        assertEquals(19_003.0, pll.frequencyHz, 0.5)
        assertEquals(0.09, pll.amplitude, 0.01)
        var worst = 0.0
        for (i in n - 24_000 until n) {
            val truth = (2 * PI * 19_003.0 * i / fs + 1.0) % (2 * PI)
            val d = phase[i] - truth
            worst = maxOf(worst, abs(atan2(sin(d), cos(d))))
        }
        assertTrue("phase error $worst rad", worst < 0.1)
    }

    @Test
    fun doesNotLockOnNoise() {
        val n = 240_000
        val rnd = Random(2)
        val x = FloatArray(n) { (0.3 * rnd.nextGaussian()).toFloat() }
        val pll = Pll(fs, 19_000.0)
        pll.process(x, n, FloatArray(n))
        assertFalse(pll.locked)
    }

    @Test
    fun doesNotLockOnSilence() {
        val n = 240_000
        val pll = Pll(fs, 19_000.0)
        pll.process(FloatArray(n), n, FloatArray(n))
        assertFalse(pll.locked)
        assertTrue("amplitude ${pll.amplitude}", pll.amplitude < 1e-6)
    }

    private fun pilot(n: Int) = FloatArray(n) { (0.09 * cos(2 * PI * 19_000.0 * it / fs)).toFloat() }

    @Test
    fun phaseStaysBelowTwoPi() {
        val n = 480_000
        val phase = FloatArray(n)
        Pll(fs, 19_000.0).process(pilot(n), n, phase)
        for (i in 0 until n) assertTrue("phase[$i] = ${phase[i]}", phase[i] >= 0f && phase[i].toDouble() < 2 * PI)
    }

    @Test
    fun sineAndCosineOutputsMatchThePhase() {
        val n = 48_000
        val x = pilot(n)
        val phase = FloatArray(n)
        val sine = FloatArray(n)
        val cosine = FloatArray(n)
        Pll(fs, 19_000.0).process(x, n, phase)
        val pll = Pll(fs, 19_000.0)
        pll.processSinCos(x, n, sine, cosine)
        assertTrue(pll.locked)
        for (i in 0 until n) {
            assertEquals(sin(phase[i].toDouble()), sine[i].toDouble(), 1e-5)
            assertEquals(cos(phase[i].toDouble()), cosine[i].toDouble(), 1e-5)
        }
    }

    @Test
    fun resetDropsTheLock() {
        val n = 240_000
        val pll = Pll(fs, 19_000.0)
        pll.process(pilot(n), n, FloatArray(n))
        assertTrue(pll.locked)
        pll.reset()
        assertFalse(pll.locked)
        assertEquals(0.0, pll.amplitude, 0.0)
        assertEquals(19_000.0, pll.frequencyHz, 1e-9)
    }
}
