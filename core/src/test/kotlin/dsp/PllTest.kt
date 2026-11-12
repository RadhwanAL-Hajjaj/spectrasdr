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
}
