// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DcBlockerTest {
    @Test
    fun removesDcFromIq() {
        val n = 240_000
        val x = FloatArray(2 * n) { if (it % 2 == 0) 0.3f else -0.2f }
        DcBlocker(2, 1_000.0, 2_400_000.0).process(x, n)
        assertEquals(0f, x[2 * n - 2], 1e-3f)
        assertEquals(0f, x[2 * n - 1], 1e-3f)
    }

    @Test
    fun passesSignalsWellAboveTheCutoff() {
        val fs = 48_000.0
        val x = Signals.realTone(48_000, 1_000.0, fs, 0.5)
        DcBlocker(1, 20.0, fs).process(x, x.size)
        assertEquals(0.5f, Signals.peak(x, 24_000, 48_000), 0.005f)
    }

    @Test
    fun recoversInTheBlockAfterASampleThatIsNotANumber() {
        val fs = 48_000.0
        val blocker = DcBlocker(1, 20.0, fs)
        val bad = Signals.realTone(480, 1_000.0, fs, 0.5)
        bad[10] = Float.NaN
        blocker.process(bad, bad.size)
        val good = Signals.realTone(4_800, 1_000.0, fs, 0.5)
        blocker.process(good, good.size)
        assertTrue(good.all { it.isFinite() })
        assertEquals(0.5f, Signals.peak(good, 2_400, 4_800), 0.01f)
    }

    @Test
    fun silenceLeavesTheStateAtExactlyZero() {
        val fs = 48_000.0
        val blocker = DcBlocker(1, 20.0, fs)
        val x = Signals.realTone(480, 1_000.0, fs, 0.5)
        blocker.process(x, x.size)
        // 60 s of silence in blocks, as a closed squelch produces; the tail must not hover in denormals.
        val silence = FloatArray(4_800)
        repeat(600) {
            silence.fill(0f)
            blocker.process(silence, silence.size)
        }
        assertEquals(0f, silence[4_799], 0f)
        val impulse = floatArrayOf(1e-20f)
        blocker.process(impulse, 1)
        assertEquals("no residue is added", 1e-20f, impulse[0], 0f)
    }

    @Test
    fun resetClearsTheState() {
        val blocker = DcBlocker(1, 20.0, 48_000.0)
        val x = FloatArray(100) { 1f }
        blocker.process(x, x.size)
        blocker.reset()
        val y = floatArrayOf(0f)
        blocker.process(y, 1)
        assertEquals(0f, y[0], 0f)
    }
}
