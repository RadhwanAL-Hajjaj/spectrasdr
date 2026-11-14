// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class NarrowDemodTest {
    private val fs = 48_000.0

    /** Runs [iq] through [demod] in blocks; returns the mono audio. */
    private fun run(demod: Demodulator, iq: FloatArray, block: Int = 4_096): FloatArray {
        val total = iq.size / 2
        val audio = FloatArray(demod.maxOutput(total) + 16)
        val chunk = FloatArray(2 * block)
        val out = FloatArray(demod.maxOutput(block))
        var frames = 0
        var done = 0
        while (done < total) {
            val n = minOf(block, total - done)
            System.arraycopy(iq, 2 * done, chunk, 0, 2 * n)
            val got = demod.process(chunk, n, out)
            System.arraycopy(out, 0, audio, frames, got)
            frames += got
            done += n
        }
        return audio.copyOf(frames)
    }

    private fun nfmSignal(toneHz: Double, deviationHz: Double, seconds: Double = 1.0, noise: Double = 0.0): FloatArray {
        val n = (seconds * fs).toInt()
        val message = Signals.realTone(n, toneHz, fs)
        val iq = FloatArray(2 * n)
        FmModulator(fs, deviationHz).modulate(message, n, iq)
        if (noise > 0) {
            val rnd = Random(2)
            for (i in iq.indices) iq[i] += (noise * rnd.nextGaussian()).toFloat()
        }
        return iq
    }

    @Test
    fun nfmRecoversAToneAtSixtyPercentDeviation() {
        val audio = run(NfmDemod(fs), nfmSignal(1_000.0, 3_000.0, noise = 0.01))
        val n = audio.size
        assertEquals(0.6, Signals.toneAmplitude(audio, 1_000.0, fs, n / 2, n), 0.03)
        val sinad = Signals.sinadDb(audio, 1_000.0, fs, n / 2, n)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
    }

    @Test
    fun nfmRemovesSubAudibleTones() {
        val audio = run(NfmDemod(fs), nfmSignal(100.0, 500.0))
        val n = audio.size
        val level = 20 * log10(Signals.toneAmplitude(audio, 100.0, fs, n / 2, n))
        assertTrue("100 Hz at $level dBFS", level < -40.0)
    }

    @Test
    fun nfmReducesAnUpperCtcssToneByAtLeastFifteenDecibelsWithDeemphasis() {
        val audio = run(NfmDemod(fs), nfmSignal(203.5, 500.0))
        val n = audio.size
        val flat = 500.0 / 5_000.0
        val level = 20 * log10(Signals.toneAmplitude(audio, 203.5, fs, n / 2, n) / flat)
        assertTrue("203.5 Hz at $level dB relative to a flat demodulator", level <= -15.0)
    }

    @Test
    fun nfmWithoutDeemphasisIsFlat() {
        val demod = NfmDemod(fs, deviationHz = 2_500.0, deemphasis = false)
        val low = run(demod, nfmSignal(600.0, 1_250.0))
        val high = run(NfmDemod(fs, deviationHz = 2_500.0, deemphasis = false), nfmSignal(2_400.0, 1_250.0))
        assertEquals(0.5, Signals.toneAmplitude(low, 600.0, fs, low.size / 2, low.size), 0.03)
        assertEquals(0.5, Signals.toneAmplitude(high, 2_400.0, fs, high.size / 2, high.size), 0.04)
    }

    private fun amSignal(toneHz: Double, depth: Double, carrier: Double, seconds: Double = 2.0, offsetHz: Double = 0.0): FloatArray {
        val n = (seconds * fs).toInt()
        val iq = Signals.complexTone(n, offsetHz, fs, carrier)
        val rnd = Random(4)
        for (k in 0 until n) {
            val m = (1 + depth * sin(2 * PI * toneHz * k / fs)).toFloat()
            iq[2 * k] = iq[2 * k] * m + (0.001 * rnd.nextGaussian()).toFloat()
            iq[2 * k + 1] = iq[2 * k + 1] * m + (0.001 * rnd.nextGaussian()).toFloat()
        }
        return iq
    }

    @Test
    fun amRecoversAToneAndLevelsIt() {
        val audio = run(AmDemod(fs), amSignal(1_000.0, 0.5, 0.2, offsetHz = 150.0))
        val n = audio.size
        val sinad = Signals.sinadDb(audio, 1_000.0, fs, n / 2, n)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
        assertEquals("AGC brings the peak to its target", 0.5, Signals.peak(audio, n / 2, n).toDouble(), 0.05)
    }

    @Test
    fun amWithoutAgcFollowsTheModulationDepth() {
        val audio = run(AmDemod(fs, agc = false), amSignal(1_000.0, 0.5, 0.2))
        val n = audio.size
        assertEquals(0.1, Signals.toneAmplitude(audio, 1_000.0, fs, n / 2, n), 0.005)
    }

    private fun ssbLevelDb(demod: Demodulator, toneOffsetHz: Double, audioHz: Double): Double {
        val n = 96_000
        val audio = run(demod, Signals.complexTone(n, toneOffsetHz, fs, 0.1))
        val m = audio.size
        return 20 * log10(Signals.toneAmplitude(audio, audioHz, demod.outputRate, m / 2, m) + 1e-12)
    }

    @Test
    fun usbPassesItsSidebandAndRejectsTheOther() {
        val wanted = ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), 1_000.0, 1_000.0)
        val unwanted = ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), -1_000.0, 1_000.0)
        assertEquals(-20.0, wanted, 0.5)
        assertTrue("wanted $wanted dB, unwanted $unwanted dB", unwanted <= wanted - 40.0)
    }

    @Test
    fun lsbPassesItsSidebandAndRejectsTheOther() {
        val wanted = ssbLevelDb(SsbDemod(fs, Sideband.LSB, agc = false), -1_500.0, 1_500.0)
        val unwanted = ssbLevelDb(SsbDemod(fs, Sideband.LSB, agc = false), 1_500.0, 1_500.0)
        assertEquals(-20.0, wanted, 0.5)
        assertTrue("wanted $wanted dB, unwanted $unwanted dB", unwanted <= wanted - 40.0)
    }

    @Test
    fun ssbPassbandIsFlatFromThreeHundredToTwentyEightHundredHertz() {
        for (hz in listOf(400.0, 1_500.0, 2_700.0)) {
            assertEquals("at $hz Hz", -20.0, ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), hz, hz), 1.0)
        }
        assertTrue(ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), 3_300.0, 3_300.0) < -60.0)
    }

    @Test
    fun ssbAgcLevelsTheAudio() {
        val demod = SsbDemod(fs, Sideband.USB)
        val audio = run(demod, Signals.complexTone(96_000, 1_000.0, fs, 0.01))
        assertEquals(0.5, Signals.peak(audio, audio.size / 2, audio.size).toDouble(), 0.05)
        assertEquals(12_000.0, demod.outputRate, 0.0)
    }

    @Test
    fun cwPutsTheCarrierAtThePitchAndRejectsNeighbours() {
        val demod = CwDemod(fs, agc = false)
        val n = 96_000
        val audio = run(demod, Signals.complexTone(n, 700.0, fs, 0.1))
        val m = audio.size
        assertEquals(700.0, Signals.zeroCrossingFrequency(audio, demod.outputRate, m / 2, m), 0.5)
        assertEquals(-20.0, 20 * log10(Signals.toneAmplitude(audio, 700.0, demod.outputRate, m / 2, m)), 0.5)
        val neighbour = ssbLevelDb(CwDemod(fs, agc = false), 1_300.0, 1_300.0)
        val image = ssbLevelDb(CwDemod(fs, agc = false), -700.0, 700.0)
        assertTrue("neighbour at $neighbour dB", neighbour < -60.0)
        assertTrue("image at $image dB", image < -60.0)
    }

    @Test
    fun demodulatorsAcceptOddChannelRates() {
        // 2.048 MS/s decimated by 42 gives 48,761.9 Hz
        val rate = 2_048_000.0 / 42
        val n = 97_524
        val demod = SsbDemod(rate, Sideband.USB, agc = false)
        val audio = run(demod, Signals.complexTone(n, 1_000.0, rate, 0.1))
        val m = audio.size
        assertEquals(rate / 4, demod.outputRate, 1e-6)
        assertEquals(-20.0, 20 * log10(Signals.toneAmplitude(audio, 1_000.0, demod.outputRate, m / 2, m)), 0.5)
    }
}
