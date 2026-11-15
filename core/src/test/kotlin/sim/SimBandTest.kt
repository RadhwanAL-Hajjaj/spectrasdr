// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverChain
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimBandTest {
    private val center = 100_000_000L

    /** [seconds] of [band] around [centerHz], rendered in blocks as the simulator does. */
    private fun render(band: SimBand, rate: Int, seconds: Double, centerHz: Long = center): FloatArray {
        val total = (seconds * rate).toInt()
        val iq = FloatArray(2 * total)
        val block = FloatArray(2 * 16_384)
        var done = 0
        while (done < total) {
            val n = minOf(16_384, total - done)
            block.fill(0f, 0, 2 * n)
            band.render(block, n, centerHz)
            System.arraycopy(block, 0, iq, 2 * done, 2 * n)
            done += n
        }
        return iq
    }

    @Test
    fun stationsAppearAtTheirOffsetsWithTheirLevels() {
        val rate = 960_000
        val band = SimBand(
            listOf(SimStation.Am(100_100_000, depth = 0.0, level = 0.1f), SimStation.Am(99_800_000, depth = 0.0, level = 0.05f)),
            rate,
        )
        val iq = render(band, rate, 0.1)
        assertEquals(-20.0, Signals.complexToneDb(iq, 100_000.0, rate.toDouble()), 0.05)
        assertEquals(-26.0, Signals.complexToneDb(iq, -200_000.0, rate.toDouble()), 0.05)
        val moved = render(band, rate, 0.1, centerHz = 100_050_000)
        assertEquals(-20.0, Signals.complexToneDb(moved, 50_000.0, rate.toDouble()), 0.05)
    }

    @Test
    fun aStationThatDoesNotFitIntoTheStreamIsLeftOut() {
        val rate = 960_000
        val station = SimStation.FmBroadcast(100_390_000, "EDGE FM ")
        // 390 + 100 kHz would pass the edge of the stream at 480 kHz; from 10 kHz higher it fits
        assertEquals(0f, Signals.peak(render(SimBand(listOf(station), rate), rate, 0.05)), 0f)
        val inside = render(SimBand(listOf(station), rate), rate, 0.05, centerHz = 100_010_000)
        assertEquals(-14.0, Signals.complexPowerDb(inside), 0.1)
    }

    @Test
    fun broadcastStationCarriesStereoAudioAndItsName() {
        val rate = 960_000
        val band = SimBand(listOf(SimStation.FmBroadcast(100_200_000, "SIM FM  ", "Simulated text", 0xD3B2, 1_000.0, 0.0)), rate)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.WFM, 200_000, sink)
        chain.offsetHz = 200_000.0
        val block = FloatArray(2 * 16_384)
        repeat(4 * rate / 16_384) {
            block.fill(0f)
            band.render(block, 16_384, center)
            chain.process(block, 16_384)
        }
        assertTrue(chain.stereo)
        assertEquals(0xD3B2, chain.rds!!.state.pi)
        assertEquals("SIM FM  ", chain.rds!!.state.ps)
        assertEquals("Simulated text", chain.rds!!.state.rt)
        val audio = sink.audio()
        val frames = sink.frames
        val left = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        val right = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1)
        assertTrue("left $left, right $right", left > 0.3 && right < left / 10)
    }

    @Test
    fun narrowFmComesInBurstsThatKeepTimeWhileOutOfView() {
        val rate = 240_000
        val band = SimBand(listOf(SimStation.NarrowFm(100_050_000, burstSeconds = 0.05, pauseSeconds = 0.05, level = 0.1f)), rate)
        val iq = render(band, rate, 0.1)
        assertEquals("transmitting", -20.0, Signals.complexPowerDb(iq, 0, 12_000), 0.1)
        assertEquals("pausing", 0f, Signals.peak(iq, 2 * 12_000, 2 * 24_000), 0f)
        // 50 ms tuned elsewhere is the next burst, missed; back in view the station pauses again
        render(band, rate, 0.05, centerHz = 200_000_000)
        val back = render(band, rate, 0.1)
        assertEquals(0f, Signals.peak(back, 0, 2 * 12_000), 0f)
        assertEquals(-20.0, Signals.complexPowerDb(back, 12_000, 24_000), 0.1)
    }

    @Test
    fun amCarrierIsModulatedToItsDepth() {
        val rate = 240_000
        val iq = render(SimBand(listOf(SimStation.Am(100_020_000, toneHz = 1_000.0, depth = 0.5, level = 0.1f)), rate), rate, 0.5)
        assertEquals(-20.0, Signals.complexToneDb(iq, 20_000.0, rate.toDouble()), 0.05)
        // Each sideband is depth / 2 of the carrier: −12 dB
        assertEquals(-32.0, Signals.complexToneDb(iq, 21_000.0, rate.toDouble()), 0.1)
        assertEquals(-32.0, Signals.complexToneDb(iq, 19_000.0, rate.toDouble()), 0.1)
    }

    @Test
    fun sidebandTonesSitOnTheirSideOfTheCarrier() {
        val rate = 240_000
        val stations = listOf(SimStation.Ssb(100_030_000, upper = true, level = 0.1f), SimStation.Ssb(99_970_000, upper = false, level = 0.1f))
        val iq = render(SimBand(stations, rate), rate, 0.5)
        val fs = rate.toDouble()
        assertEquals(-26.0, Signals.complexToneDb(iq, 30_700.0, fs), 0.1)
        assertEquals(-26.0, Signals.complexToneDb(iq, 31_900.0, fs), 0.1)
        assertTrue(Signals.complexToneDb(iq, 29_300.0, fs) < -80.0)
        assertEquals(-26.0, Signals.complexToneDb(iq, -30_700.0, fs), 0.1)
        assertEquals(-26.0, Signals.complexToneDb(iq, -31_900.0, fs), 0.1)
        assertTrue(Signals.complexToneDb(iq, -29_300.0, fs) < -80.0)
    }

    @Test
    fun cwIsKeyedInDots() {
        val rate = 240_000
        val iq = render(SimBand(listOf(SimStation.Cw(100_010_000, dotSeconds = 0.05, level = 0.1f)), rate), rate, 0.2)
        // Dots of 12,000 samples: on, off, on, off; the key takes 5 ms (1,200 samples) to rise and fall
        assertEquals(-20.0, Signals.complexPowerDb(iq, 2_000, 12_000), 0.05)
        assertEquals(0f, Signals.peak(iq, 2 * 14_000, 2 * 24_000), 0f)
        assertEquals(-20.0, Signals.complexPowerDb(iq, 26_000, 36_000), 0.05)
        assertTrue("the key is shaped", Signals.complexPowerDb(iq, 0, 600) < -24.0)
    }

    @Test
    fun theDefaultBandHasEveryKindOfSignal() {
        val kinds = SimBand.defaultStations().map { it::class }.toSet()
        assertEquals(
            setOf(SimStation.FmBroadcast::class, SimStation.NarrowFm::class, SimStation.Am::class, SimStation.Ssb::class, SimStation.Cw::class),
            kinds,
        )
    }
}
