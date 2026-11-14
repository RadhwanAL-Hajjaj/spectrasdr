// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningPolicyTest {
    private val rate = 2_400_000   // with an RTL-SDR the usable band reaches ±900 kHz

    private fun plan(frequencyHz: Long, currentCenterHz: Long?, mode: Mode = Mode.WFM, bandwidthHz: Int = mode.defaultBandwidthHz) =
        TuningPolicy.plan(frequencyHz, currentCenterHz, rate, mode, bandwidthHz, 700.0, 0.75)

    @Test
    fun broadcastFmIsCentredOnTheStation() {
        assertEquals(100_000_000L, TuningPolicy.centerFor(100_000_000, rate, Mode.WFM))
        assertEquals(100_000_000L, plan(100_000_000, null))
    }

    @Test
    fun narrowModesSitAQuarterOfTheRateAboveTheCentre() {
        assertEquals(144_900_000L, TuningPolicy.centerFor(145_500_000, rate, Mode.NFM))
        assertEquals(144_900_000L, plan(145_500_000, null, Mode.NFM))
        assertEquals("never below 0 Hz", 0L, TuningPolicy.centerFor(100_000, rate, Mode.AM))
    }

    @Test
    fun theTunerStaysWhileTheChannelIsInsideTheUsableBand() {
        assertEquals(100_000_000L, plan(100_700_000, 100_000_000))
        assertEquals("700 + 100 kHz reaches the edge exactly", 100_000_000L, plan(100_800_000, 100_000_000))
        assertEquals(100_800_001L, plan(100_800_001, 100_000_000))
        assertEquals(99_100_000L, plan(99_100_000, 100_000_000))
    }

    @Test
    fun narrowChannelsKeepClearOfTheCentre() {
        // 12.5 kHz NFM reaches 6.25 kHz to either side and stays 1 kHz away from the centre
        assertTrue(TuningPolicy.fits(7_250.0, rate, Mode.NFM, 6_250.0, 0.75))
        assertFalse(TuningPolicy.fits(7_249.0, rate, Mode.NFM, 6_250.0, 0.75))
        assertFalse(TuningPolicy.fits(-5_000.0, rate, Mode.NFM, 6_250.0, 0.75))
        assertTrue("broadcast FM may sit on the centre", TuningPolicy.fits(0.0, rate, Mode.WFM, 100_000.0, 0.75))
        assertEquals(99_405_000L, plan(100_005_000, 100_000_000, Mode.NFM))
        assertEquals(100_000_000L, plan(100_300_000, 100_000_000, Mode.NFM))
        assertEquals("switching to AM on a centred station moves the tuner", 99_400_000L, plan(100_000_000, 100_000_000, Mode.AM))
    }

    @Test
    fun aWideChannelInANarrowStreamIsSimplyCentred() {
        // 200 kHz does not fit into the usable part of 240 kHz at all
        assertFalse(TuningPolicy.fits(0.0, 240_000, Mode.WFM, 100_000.0, 0.75))
        assertEquals(100_000_000L, TuningPolicy.plan(100_000_000, 100_000_000, 240_000, Mode.WFM, 200_000, 700.0, 0.75))
        assertEquals(100_010_000L, TuningPolicy.plan(100_010_000, 100_000_000, 240_000, Mode.WFM, 200_000, 700.0, 0.75))
    }

    @Test
    fun usableFractionAndChannelWidthsFollowTheSourceAndTheMode() {
        assertEquals(0.85, TuningPolicy.usableFraction(SourceKind.RSP_TCP), 0.0)
        assertEquals(0.75, TuningPolicy.usableFraction(SourceKind.RTL_TCP), 0.0)
        assertEquals(0.75, TuningPolicy.usableFraction(SourceKind.SIMULATOR), 0.0)
        assertEquals(100_000.0, TuningPolicy.halfWidthHz(Mode.WFM, 200_000, 700.0), 0.0)
        assertEquals(4_000.0, TuningPolicy.halfWidthHz(Mode.AM, 8_000, 700.0), 0.0)
        assertEquals(3_200.0, TuningPolicy.halfWidthHz(Mode.LSB, 2_500, 700.0), 0.0)
        assertEquals(1_200.0, TuningPolicy.halfWidthHz(Mode.CW, 500, 700.0), 0.0)
    }

    @Test
    fun aRecordingIsLimitedToItsOwnBand() {
        assertEquals(10_000.0, TuningPolicy.clampOffset(10_000.0, 240_000, 6_250.0), 0.0)
        assertEquals(113_750.0, TuningPolicy.clampOffset(500_000.0, 240_000, 6_250.0), 0.0)
        assertEquals(-113_750.0, TuningPolicy.clampOffset(-500_000.0, 240_000, 6_250.0), 0.0)
        assertEquals("a channel wider than the stream stays in the middle", 0.0, TuningPolicy.clampOffset(50_000.0, 240_000, 150_000.0), 0.0)
    }
}
