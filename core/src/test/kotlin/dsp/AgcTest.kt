// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgcTest {
    private val fs = 48_000.0

    @Test
    fun bringsAWeakSignalToTheTarget() {
        val agc = Agc(fs, targetPeak = 0.5f)
        val x = Signals.realTone(48_000, 1_000.0, fs, 0.01)
        agc.process(x, x.size)
        assertEquals(0.5f, Signals.peak(x, 24_000, 48_000), 0.05f)
    }

    @Test
    fun settlesQuicklyAfterALoudStep() {
        val agc = Agc(fs, targetPeak = 0.5f)
        val quiet = Signals.realTone(48_000, 1_000.0, fs, 0.01)
        agc.process(quiet, quiet.size)
        val loud = Signals.realTone(48_000, 1_000.0, fs, 0.1)
        agc.process(loud, loud.size)
        assertTrue(Signals.peak(loud, 960, 2_400) < 0.75f)   // 20–50 ms after the step
        assertEquals(0.5f, Signals.peak(loud, 24_000, 48_000), 0.05f)
    }

    @Test
    fun gainIsCapped() {
        val agc = Agc(fs, targetPeak = 0.5f, maxGainDb = 40.0)
        val x = Signals.realTone(48_000, 1_000.0, fs, 1e-5)
        agc.process(x, x.size)
        assertTrue(Signals.peak(x, 24_000, 48_000) <= 1e-3f * 1.01f)
    }
}
