// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.MpxGenerator
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class WfmDemodTest {
    private val fs = 240_000.0

    /**
     * Demodulates [seconds] of an FM signal carrying [mpx]; returns interleaved stereo audio at 48 kHz.
     * [cnrDb] adds white noise for that carrier-to-noise ratio in the 240 kHz band.
     */
    private fun demodulate(
        mpx: MpxGenerator,
        demod: WfmDemod,
        seconds: Double,
        block: Int = 16_384,
        cnrDb: Double = Double.POSITIVE_INFINITY,
    ): FloatArray {
        val total = (seconds * fs).toInt()
        val sigma = if (cnrDb.isInfinite()) 0f else sqrt(10.0.pow(-cnrDb / 10) / 2).toFloat()
        val rnd = Random(2)
        val modulator = FmModulator(fs, 75_000.0)
        val message = FloatArray(block)
        val iq = FloatArray(2 * block)
        val out = FloatArray(2 * demod.maxOutput(block))
        val audio = FloatArray(2 * (total / 5 + 16))
        var frames = 0
        var done = 0
        while (done < total) {
            val n = minOf(block, total - done)
            mpx.generate(message, n)
            modulator.modulate(message, n, iq)
            if (sigma > 0f) for (i in 0 until 2 * n) iq[i] += sigma * rnd.nextGaussian().toFloat()
            val got = demod.process(iq, n, out)
            System.arraycopy(out, 0, audio, 2 * frames, 2 * got)
            frames += got
            done += n
        }
        return audio.copyOf(2 * frames)
    }

    @Test
    fun monoToneAtFullDeviationIsClean() {
        val demod = WfmDemod(fs)
        val audio = demodulate(MpxGenerator(fs, 1_000.0, 1_000.0, stereo = false), demod, 1.0)
        val frames = audio.size / 2
        assertEquals(48_000.0, demod.outputRate, 0.0)
        assertEquals(2, demod.channels)
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        assertTrue("SINAD $sinad dB", sinad >= 40.0)
        // 50 µs de-emphasis takes 0.41 dB off a 1 kHz tone
        assertEquals(-0.41, 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)), 0.1)
        assertFalse(demod.stereo)
        for (i in frames / 2 until frames) assertEquals(audio[2 * i], audio[2 * i + 1], 1e-6f)
    }

    @Test
    fun leftOnlyToneStaysOutOfTheRightChannel() {
        val demod = WfmDemod(fs)
        val audio = demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 2.0)
        val frames = audio.size / 2
        assertTrue(demod.pilotLocked)
        assertTrue(demod.stereo)
        val left = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0))
        val right = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1))
        assertEquals(20 * log10(0.91) - 0.41, left, 0.3)
        assertTrue("left $left dB, right $right dB", right <= left - 25.0)
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        assertTrue("SINAD $sinad dB", sinad >= 40.0)
    }

    @Test
    fun differentTonesLandInTheirOwnChannels() {
        val demod = WfmDemod(fs)
        val audio = demodulate(MpxGenerator(fs, 400.0, 3_000.0), demod, 2.0)
        val frames = audio.size / 2
        val l400 = Signals.toneAmplitude(audio, 400.0, 48_000.0, frames / 2, frames, 2, 0)
        val l3000 = Signals.toneAmplitude(audio, 3_000.0, 48_000.0, frames / 2, frames, 2, 0)
        val r400 = Signals.toneAmplitude(audio, 400.0, 48_000.0, frames / 2, frames, 2, 1)
        val r3000 = Signals.toneAmplitude(audio, 3_000.0, 48_000.0, frames / 2, frames, 2, 1)
        assertTrue(20 * log10(l3000 / l400) < -25.0)
        assertTrue(20 * log10(r400 / r3000) < -25.0)
    }

    @Test
    fun stereoCanBeSwitchedOff() {
        val demod = WfmDemod(fs)
        demod.stereoEnabled = false
        val audio = demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 1.0)
        val frames = audio.size / 2
        assertFalse(demod.stereo)
        for (i in frames / 2 until frames) assertEquals(audio[2 * i], audio[2 * i + 1], 1e-6f)
    }

    @Test
    fun noisyReceptionBlendsTowardMono() {
        /** Left and right amplitude of a left-only tone received at [cnrDb]. */
        fun receive(cnrDb: Double, demod: WfmDemod): Pair<Double, Double> {
            val audio = demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 3.0, cnrDb = cnrDb)
            val frames = audio.size / 2
            return Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0) to
                Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1)
        }
        val strong = WfmDemod(fs)
        val (strongLeft, strongRight) = receive(40.0, strong)
        assertEquals(1f, strong.stereoBlend, 0f)
        assertTrue(strong.stereoNoiseDb < StereoDecoder.QUIET_DB)
        assertTrue("separation at 40 dB", 20 * log10(strongLeft / strongRight) > 40.0)

        val marginal = WfmDemod(fs)
        val (marginalLeft, marginalRight) = receive(22.0, marginal)
        assertTrue("blend ${marginal.stereoBlend}", marginal.stereoBlend > 0.2f && marginal.stereoBlend < 0.7f)
        val separation = 20 * log10(marginalLeft / marginalRight)
        assertTrue("separation $separation dB at 22 dB", separation > 3.0 && separation < 15.0)

        val weak = WfmDemod(fs)
        val (weakLeft, weakRight) = receive(14.0, weak)
        assertTrue("the pilot itself is still locked", weak.pilotLocked)
        assertEquals(0f, weak.stereoBlend, 0f)
        assertFalse(weak.stereo)
        assertTrue(weak.stereoNoiseDb > StereoDecoder.NOISY_DB)
        assertEquals(weakLeft, weakRight, 0.02)
    }

    @Test
    fun theTapGetsTheMultiplexWithThePilotPhase() {
        val demod = WfmDemod(fs)
        var samples = 0
        var resets = 0
        var inPhase = 0.0
        var locked = false
        demod.tap = object : MultiplexTap {
            override fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int) {
                locked = pilotLocked
                if (!pilotLocked) return
                for (n in 0 until count) inPhase += mpx[n] * pilotCos[n]
                samples += count
            }

            override fun reset() {
                resets++
            }
        }
        demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 2.0)
        assertTrue(locked)
        assertTrue(samples > 240_000)
        assertEquals("the pilot is 0.09·cos θ", 0.09, 2 * inPhase / samples, 0.005)
        demod.reset()
        assertEquals(1, resets)
    }

    @Test
    fun blockSizeDoesNotChangeTheSettledAudio() {
        // The stereo blend starts at a block boundary, so only the settled part must match.
        val a = demodulate(MpxGenerator(fs, 1_000.0, 0.0), WfmDemod(fs), 1.5, block = 16_384)
        val b = demodulate(MpxGenerator(fs, 1_000.0, 0.0), WfmDemod(fs), 1.5, block = 1_000)
        assertEquals(a.size, b.size)
        for (i in a.size * 2 / 3 until a.size) assertEquals(a[i], b[i], 1e-4f)
    }
}
