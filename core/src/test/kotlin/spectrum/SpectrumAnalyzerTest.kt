// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.spectrum

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowType
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class SpectrumAnalyzerTest {
    // One 1,024-point frame per 1,024-sample block
    private val rate = 102_400
    private fun analyzer(window: WindowType = WindowType.BLACKMAN_HARRIS) =
        SpectrumAnalyzer(1_024, window, framesPerSecond = 100.0, maxFftsPerFrame = 1)

    private fun tone(bin: Int, amplitude: Double = 1.0) = Signals.complexTone(1_024, bin * 100.0, rate.toDouble(), amplitude)

    @Test
    fun fullScaleToneReadsZeroDbfsAtItsFrequency() {
        for (window in WindowType.values()) {
            val a = analyzer(window)
            val frame = SpectrumFrame(1_024)
            a.process(tone(128), 1_024, rate, 100_000_000L)
            assertTrue(a.latest(frame))
            assertEquals("$window", 0f, frame.levelsDb[512 + 128], 0.01f)
            assertTrue(frame.levelsDb[512 - 128] < -90f)
            assertEquals(100_000_000L, frame.centerHz)
            assertEquals(rate, frame.sampleRate)
            assertFalse("nothing new", a.latest(frame))
        }
    }

    @Test
    fun negativeFrequenciesSitBelowTheCentre() {
        val a = analyzer()
        val frame = SpectrumFrame(1_024)
        a.process(tone(-256, 0.1), 1_024, rate, 0L)
        a.latest(frame)
        assertEquals(-20f, frame.levelsDb[512 - 256], 0.01f)
        assertTrue(frame.levelsDb[512 + 256] < -90f)
    }

    @Test
    fun averagesLinearPower() {
        val a = analyzer()
        a.averaging = 0.5
        val frame = SpectrumFrame(1_024)
        repeat(3) { a.process(tone(100), 1_024, rate, 0L) }
        a.process(tone(100, 0.1), 1_024, rate, 0L)
        a.latest(frame)
        // 0.5·0.01 + 0.5·1 = 0.505 → −2.97 dB
        assertEquals(-2.97f, frame.levelsDb[612], 0.02f)
    }

    @Test
    fun peakHoldDecaysAtTheConfiguredRate() {
        val a = analyzer()
        a.averaging = 1.0
        a.peakDecayDbPerSecond = 20.0
        val frame = SpectrumFrame(1_024)
        a.process(tone(100), 1_024, rate, 0L)
        repeat(10) { a.process(tone(100, 0.001), 1_024, rate, 0L) }
        a.latest(frame)
        assertEquals(-60f, frame.levelsDb[612], 0.05f)
        assertEquals("ten frames of 10 ms at 20 dB/s", -2f, frame.peakDb[612], 0.05f)
    }

    @Test
    fun framesComeAtTheDisplayRateAndAverageSeveralFfts() {
        val a = SpectrumAnalyzer(4_096, framesPerSecond = 30.0)
        val frame = SpectrumFrame(4_096)
        val block = Signals.complexTone(16_384, 300_000.0, 2_400_000.0, 0.5)
        var frames = 0
        repeat(147) {                      // about one second at 2.4 MS/s
            a.process(block, 16_384, 2_400_000, 0L)
            if (a.latest(frame)) frames++
        }
        assertTrue("$frames frames", frames in 29..31)
        assertEquals(4, a.fftsInLastFrame)
        assertEquals(-6.02f, frame.levelsDb[2_048 + 512], 0.05f)
    }

    @Test
    fun resetForgetsHistory() {
        val a = analyzer()
        a.averaging = 0.1
        val frame = SpectrumFrame(1_024)
        repeat(3) { a.process(tone(100), 1_024, rate, 0L) }
        a.reset()
        a.process(tone(100, 0.01), 1_024, rate, 0L)
        a.latest(frame)
        assertEquals(-40f, frame.levelsDb[612], 0.05f)
        assertEquals(-40f, frame.peakDb[612], 0.05f)
    }

    @Test
    fun displayColumnsKeepTheLargestBin() {
        val levels = FloatArray(1_024) { -100f }
        levels[300] = -10f
        levels[301] = -30f
        val out = FloatArray(128)
        SpectrumDisplay.reduce(levels, 0.0, 1_024.0, out, 128)
        assertEquals(-10f, out[37], 0f)       // bins 296–303
        assertEquals(-100f, out[36], 0f)
        // Zoomed in to 16 bins across 64 columns: each bin fills four columns
        SpectrumDisplay.reduce(levels, 296.0, 312.0, out, 64)
        assertEquals(-100f, out[15], 0f)
        for (c in 16..19) assertEquals(-10f, out[c], 0f)
        for (c in 20..23) assertEquals(-30f, out[c], 0f)
    }

    @Test
    fun estimatesTheNoiseFloorWhileSignalsFillPartOfTheBand() {
        val fs = 2_400_000
        val a = SpectrumAnalyzer(4_096)
        assertEquals(Double.NEGATIVE_INFINITY, a.noiseDensityDb, 0.0)
        // 191 carriers on every 8th bin of the upper half, each 36 dB above the noise in its bin.
        // Their sum repeats every 512 samples.
        val carriers = FloatArray(2 * 512)
        for (t in 1..191) {
            for (k in 0 until 512) {
                val phase = 2 * PI * (8 * t) * k / 4_096
                carriers[2 * k] += (0.002 * cos(phase)).toFloat()
                carriers[2 * k + 1] += (0.002 * sin(phase)).toFloat()
            }
        }
        val sigma = 0.001
        val truth = 10 * log10(2 * sigma * sigma / fs)       // −120.8 dBFS per hertz
        val rnd = Random(3)
        val block = FloatArray(2 * 16_384)
        var first = Double.NaN
        repeat(150) {                                        // about one second
            for (i in block.indices) block[i] = (sigma * rnd.nextGaussian()).toFloat() + carriers[i % 1_024]
            a.process(block, 16_384, fs, 0L)
            if (first.isNaN() && a.noiseDensityDb > Double.NEGATIVE_INFINITY) first = a.noiseDensityDb
        }
        assertTrue("first estimate $first against $truth", first > truth - 3.5 && first < truth + 0.5)
        assertEquals(truth, a.noiseDensityDb, 1.0)
        a.reset()
        assertEquals(Double.NEGATIVE_INFINITY, a.noiseDensityDb, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSizesThatAreNotPowersOfTwo() {
        SpectrumAnalyzer(3_000)
    }
}
