// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
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
}
