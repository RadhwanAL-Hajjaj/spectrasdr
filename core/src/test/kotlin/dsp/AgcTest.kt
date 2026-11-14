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

    @Test
    fun outputNeverExceedsFullScale() {
        val agc = Agc(fs)
        val silence = FloatArray(4_800)
        agc.process(silence, silence.size)           // the gain rests at its maximum
        val loud = Signals.realTone(4_800, 1_000.0, fs, 0.8)
        agc.process(loud, loud.size)
        assertEquals("the onset clips", 1f, Signals.peak(loud, 0, 48), 0f)
        assertTrue(Signals.peak(loud) <= 1f)
        assertEquals(0.5f, Signals.peak(loud, 2_400, 4_800), 0.05f)
    }

    @Test
    fun samplesThatAreNotNumbersAreMutedAndLeaveNoTrace() {
        val agc = Agc(fs)
        val x = Signals.realTone(9_600, 1_000.0, fs, 0.1)
        val clean = x.copyOf()
        Agc(fs).process(clean, clean.size)
        x[100] = Float.NaN
        x[101] = Float.POSITIVE_INFINITY
        agc.process(x, x.size)
        assertEquals(0f, x[100], 0f)
        assertEquals(0f, x[101], 0f)
        for (i in 4_800 until 9_600) assertEquals(clean[i], x[i], 1e-3f)
    }

    @Test
    fun resetReturnsToFullGain() {
        val agc = Agc(fs, maxGainDb = 40.0)
        val loud = Signals.realTone(4_800, 1_000.0, fs, 0.5)
        agc.process(loud, loud.size)
        agc.reset()
        val quiet = floatArrayOf(1e-4f)
        agc.process(quiet, 1)
        assertEquals("40 dB of gain", 1e-2f, quiet[0], 1e-4f)
    }
}
