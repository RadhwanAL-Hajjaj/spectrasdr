// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzer
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.TestStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class ReceiverChainTest {
    private val rate = 960_000

    /** Feeds [iq] to [chain] in blocks of 16,384 samples, as a source would. */
    private fun feed(chain: ReceiverChain, iq: FloatArray) {
        val block = FloatArray(2 * 16_384)
        var done = 0
        val total = iq.size / 2
        while (done < total) {
            val n = minOf(16_384, total - done)
            System.arraycopy(iq, 2 * done, block, 0, 2 * n)
            chain.process(block, n)
            done += n
        }
    }

    private fun addNoise(iq: FloatArray, sigma: Double, seed: Long = 9) {
        val rnd = Random(seed)
        for (i in iq.indices) iq[i] += (sigma * rnd.nextGaussian()).toFloat()
    }

    @Test
    fun broadcastStationInStereoWithRds() {
        val encoder = RdsEncoder(0xD318, "SPECTRA ", "Chain test")
        val iq = TestStations.fmBroadcast(rate.toDouble(), 200_000.0, 4.0, 1_000.0, 0.0, encoder)
        addNoise(iq, 0.001)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.WFM, 200_000, sink)
        chain.offsetHz = 200_000.0
        feed(chain, iq)
        assertEquals(2, sink.channels)
        assertEquals(4 * 48_000.0, sink.frames.toDouble(), 100.0)
        assertTrue(chain.stereo)
        assertEquals(-6.0, chain.channelPowerDb, 0.5)
        val audio = sink.audio()
        val frames = sink.frames
        val left = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0))
        val right = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1))
        assertTrue("left $left dB, right $right dB", right <= left - 25.0)
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        assertTrue("SINAD $sinad dB", sinad >= 40.0)
        val rds = chain.rds
        assertNotNull(rds)
        assertEquals(0xD318, rds!!.state.pi)
        assertEquals("SPECTRA ", rds.state.ps)
        assertEquals("Chain test", rds.state.rt)
    }

    @Test
    fun narrowFmNextToAStrongNeighbour() {
        val n = 2 * rate
        val iq = FloatArray(2 * n)
        FmModulator(rate.toDouble(), 3_000.0, -150_000.0).modulate(Signals.realTone(n, 1_000.0, rate.toDouble()), n, iq, 0.05f)
        FmModulator(rate.toDouble(), 3_000.0, -125_000.0).modulate(Signals.realTone(n, 400.0, rate.toDouble()), n, iq, 0.5f, add = true)
        addNoise(iq, 0.0005)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.NFM, 12_500, sink)
        chain.offsetHz = -150_000.0
        feed(chain, iq)
        assertNull(chain.rds)
        assertFalse(chain.stereo)
        assertEquals(1, sink.channels)
        val audio = sink.audio()
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
        assertEquals(0.6, Signals.toneAmplitude(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size), 0.05)
    }

    @Test
    fun amStation() {
        val n = 2 * rate
        val iq = Signals.complexTone(n, 100_000.0, rate.toDouble(), 0.1)
        for (k in 0 until n) {
            val m = (1 + 0.5 * sin(2 * PI * 1_000.0 * k / rate)).toFloat()
            iq[2 * k] *= m
            iq[2 * k + 1] *= m
        }
        addNoise(iq, 0.0005)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.AM, 8_000, sink)
        chain.offsetHz = 100_000.0
        feed(chain, iq)
        val audio = sink.audio()
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
    }

    private fun toneThrough(mode: Mode, bandwidthHz: Int, toneOffsetHz: Double, tunedOffsetHz: Double): FloatArray {
        val n = 2 * rate
        val iq = Signals.complexTone(n, toneOffsetHz, rate.toDouble(), 0.05)
        addNoise(iq, 0.0002)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, mode, bandwidthHz, sink)
        chain.offsetHz = tunedOffsetHz
        feed(chain, iq)
        return sink.audio()
    }

    @Test
    fun sidebandsAndCwComeOutAtTheRightPitch() {
        var audio = toneThrough(Mode.USB, 2_500, 51_000.0, 50_000.0)
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 1.0)
        audio = toneThrough(Mode.LSB, 2_500, 48_500.0, 50_000.0)
        assertEquals(1_500.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 1.0)
        audio = toneThrough(Mode.CW, 500, 50_000.0, 50_000.0)
        assertEquals(700.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 1.0)
        assertEquals("AGC target", 0.5, Signals.peak(audio, audio.size / 2, audio.size).toDouble(), 0.06)
    }

    @Test
    fun closedSquelchGivesSilence() {
        val n = rate
        val iq = FloatArray(2 * n)
        FmModulator(rate.toDouble(), 3_000.0, 0.0).modulate(Signals.realTone(n, 1_000.0, rate.toDouble()), n, iq, 0.01f)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.NFM, 12_500, sink)
        chain.squelch.thresholdDb = -30.0          // the signal is at −40 dBFS
        feed(chain, iq)
        assertFalse(chain.squelch.open)
        assertEquals(-40.0, chain.channelPowerDb, 0.5)
        val audio = sink.audio()
        assertEquals(0f, Signals.peak(audio, audio.size / 2, audio.size), 0f)
    }

    @Test
    fun automaticSquelchUsesTheNoiseFloorOfTheStream() {
        val analyzer = SpectrumAnalyzer(4_096)
        val chain = ReceiverChain(rate, Mode.NFM, 12_500, CollectingSink())
        chain.squelch.auto = true
        chain.offsetHz = -150_000.0
        val rnd = Random(4)
        val block = FloatArray(2 * 16_384)
        val silence = FloatArray(16_384)
        fun run(blocks: Int, carrier: FmModulator?) = repeat(blocks) {
            for (i in block.indices) block[i] = (0.002 * rnd.nextGaussian()).toFloat()
            carrier?.modulate(silence, 16_384, block, 0.05f, add = true)
            analyzer.process(block, 16_384, rate, 0L)
            chain.noiseDensityDb = analyzer.noiseDensityDb
            chain.process(block, 16_384)
        }
        assertTrue("open until the floor is known", chain.squelch.open)
        run(30, null)
        // Noise of 0.002 per component is −51 dBFS over 960 kHz; the channel filter passes 14.1 kHz of it.
        assertEquals(-69.3, chain.noiseFloorDb, 1.0)
        assertEquals("an empty channel sits at its floor", chain.noiseFloorDb, chain.channelPowerDb, 2.0)
        assertFalse(chain.squelch.open)
        run(30, FmModulator(rate.toDouble(), 0.0, -150_000.0))   // a carrier at −26 dBFS that never stops
        assertTrue(chain.squelch.open)
        assertEquals("the carrier does not raise the floor", -69.3, chain.noiseFloorDb, 1.0)
        assertEquals(-26.0, chain.channelPowerDb, 0.2)
    }

    @Test
    fun automaticSquelchStaysOpenWhenTheChannelFillsTheStream() {
        val chain = ReceiverChain(240_000, Mode.WFM, 200_000, CollectingSink())
        chain.squelch.auto = true
        chain.noiseDensityDb = -100.0
        val iq = FloatArray(2 * 16_384)
        addNoise(iq, 0.001)
        chain.process(iq, 16_384)
        assertEquals(Double.NEGATIVE_INFINITY, chain.noiseFloorDb, 0.0)
        assertTrue(chain.squelch.open)
    }

    @Test
    fun everyModeRunsAtItsLowestSampleRate() {
        for (mode in Mode.values()) {
            val lowest = ReceiverChain.minimumSampleRate(mode)
            assertTrue(ReceiverChain.supports(lowest, mode))
            assertFalse(ReceiverChain.supports(lowest - 1, mode))
            val sink = CollectingSink()
            val chain = ReceiverChain(lowest, mode, mode.defaultBandwidthHz, sink)
            val iq = Signals.complexTone(lowest, 1_000.0, lowest.toDouble(), 0.1)   // one second
            val block = FloatArray(2 * 16_384)
            var done = 0
            while (done < lowest) {
                val n = minOf(16_384, lowest - done)
                System.arraycopy(iq, 2 * done, block, 0, 2 * n)
                chain.process(block, n)
                done += n
            }
            assertEquals("$mode", 48_000.0, sink.frames.toDouble(), 600.0)
            assertTrue("$mode", sink.audio().all { it.isFinite() })
        }
        try {
            ReceiverChain(100_000, Mode.WFM, 200_000, CollectingSink())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("120000"))
        }
    }

    @Test
    fun decimationFactorsKeepEveryStageSmall() {
        assertEquals(50, Channelizer.narrowDecimation(2_400_000.0))
        assertEquals(42, Channelizer.narrowDecimation(2_048_000.0))
        assertEquals(64, Channelizer.narrowDecimation(3_200_000.0))   // 66 would need a stage of 11
        assertEquals(20, Channelizer.narrowDecimation(1_000_000.0))
        assertEquals(5, Channelizer.narrowDecimation(250_000.0))
        assertEquals(2, Channelizer.narrowDecimation(96_000.0))
        assertEquals(1, Channelizer.narrowDecimation(48_000.0))
        assertEquals(10, Channelizer.wideDecimation(2_400_000.0))
        assertEquals(4, Channelizer.wideDecimation(960_000.0))
        assertEquals(12, Channelizer.wideDecimation(3_200_000.0))     // 13 is a prime above 8
        assertEquals(32, Channelizer.wideDecimation(8_000_000.0))     // 33 would need a stage of 11
        assertEquals(1, Channelizer.wideDecimation(250_000.0))
        assertEquals(1, Channelizer.wideDecimation(200_000.0))
    }
}
