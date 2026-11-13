// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirDesignTest {
    @Test
    fun meetsPassbandAndStopband() {
        val fs = 240_000.0
        val taps = FirDesign.lowPassKaiser(15_000.0, 19_000.0, fs, 66.0)
        assertTrue(taps.size % 2 == 1)
        for (f in listOf(0.0, 5_000.0, 10_000.0, 15_000.0)) {
            assertEquals("passband at $f Hz", 0.0, FirDesign.responseDb(taps, f, fs), 0.5)
        }
        var f = 19_000.0
        while (f < fs / 2) {
            assertTrue("stopband at $f Hz", FirDesign.responseDb(taps, f, fs) <= -60.0)
            f += 250.0
        }
    }

    @Test
    fun unityDcGainAndSymmetry() {
        val taps = FirDesign.lowPass(31, 1_000.0, 48_000.0, 6.0)
        assertEquals(1.0, taps.sum().toDouble(), 1e-5)
        for (i in 0 until 15) assertEquals(taps[i], taps[30 - i], 1e-7f)
    }

    @Test
    fun kaiserTapCountIsOdd() {
        assertTrue(FirDesign.kaiserTaps(1_000.0, 48_000.0, 60.0) % 2 == 1)
    }

    @Test
    fun besselI0KnownValues() {
        assertEquals(1.0, FirDesign.besselI0(0.0), 1e-12)
        assertEquals(1.2660658777520082, FirDesign.besselI0(1.0), 1e-12)
        assertEquals(27.239871823604442, FirDesign.besselI0(5.0), 1e-9)
    }
}
