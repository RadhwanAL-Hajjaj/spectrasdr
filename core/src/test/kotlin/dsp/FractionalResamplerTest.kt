// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class FractionalResamplerTest {
    @Test
    fun preservesToneFrequencyAndAmplitude() {
        val r = FractionalResampler(1, 64_000.0, 48_000.0)
        val input = Signals.realTone(64_000, 1_000.0, 64_000.0, 0.5)
        val out = FloatArray(r.maxOutput(64_000))
        val m = r.process(input, 64_000, out)
        assertEquals(48_000.0, m.toDouble(), 2.0)
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(out, 48_000.0, m / 2, m), 0.05)
        assertEquals(0.5f, Signals.peak(out, m / 2, m), 0.005f)
    }

    @Test
    fun rejectsContentThatWouldAlias() {
        val r = FractionalResampler(1, 96_000.0, 48_000.0)
        val input = Signals.realTone(96_000, 36_000.0, 96_000.0, 0.5)   // would alias to 12 kHz
        val out = FloatArray(r.maxOutput(96_000))
        val m = r.process(input, 96_000, out)
        var p = 0.0
        for (i in m / 2 until m) p += out[i] * out[i]
        val levelDb = 10 * log10(p / (m - m / 2) / 0.125)   // relative to the input tone power
        assertTrue("alias level $levelDb dB", levelDb < -70)
    }

    @Test
    fun stereoChannelsStayIndependent() {
        val frames = 50_000
        val input = FloatArray(2 * frames)
        for (f in 0 until frames) {
            input[2 * f] = (0.5 * sin(2 * PI * 1_000.0 * f / 50_000.0)).toFloat()
            input[2 * f + 1] = (0.5 * sin(2 * PI * 3_000.0 * f / 50_000.0)).toFloat()
        }
        val r = FractionalResampler(2, 50_000.0, 48_000.0)
        val out = FloatArray(2 * r.maxOutput(frames))
        val m = r.process(input, frames, out)
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(out, 48_000.0, m / 2, m, 2, 0), 0.05)
        assertEquals(3_000.0, Signals.zeroCrossingFrequency(out, 48_000.0, m / 2, m, 2, 1), 0.1)
    }

    @Test
    fun correctionChangesTheOutputCount() {
        val input = Signals.realTone(480_000, 500.0, 48_000.0)
        val plain = FractionalResampler(1, 48_000.0, 48_000.0)
        val trimmed = FractionalResampler(1, 48_000.0, 48_000.0).apply { correction = 1e-3 }
        val mPlain = plain.process(input, 480_000, FloatArray(plain.maxOutput(480_000)))
        val mTrimmed = trimmed.process(input, 480_000, FloatArray(trimmed.maxOutput(480_000)))
        assertEquals(480_000.0, mPlain.toDouble(), 2.0)
        assertEquals(480_000.0 / 1.001, mTrimmed.toDouble(), 2.0)
    }

    @Test
    fun blockSplittingIsSeamless() {
        val input = Signals.realTone(20_000, 2_345.0, 64_000.0)
        val whole = FractionalResampler(1, 64_000.0, 48_000.0)
        val outWhole = FloatArray(whole.maxOutput(20_000))
        val mWhole = whole.process(input, 20_000, outWhole)
        val split = FractionalResampler(1, 64_000.0, 48_000.0)
        val outSplit = FloatArray(whole.maxOutput(20_000))
        val tmp = FloatArray(split.maxOutput(20_000))
        var mSplit = 0
        var offset = 0
        for (len in intArrayOf(1, 333, 7_000, 12_666)) {
            val k = split.process(input.copyOfRange(offset, offset + len), len, tmp)
            System.arraycopy(tmp, 0, outSplit, mSplit, k)
            mSplit += k
            offset += len
        }
        assertEquals(mWhole, mSplit)
        for (i in 0 until mWhole) assertEquals(outWhole[i], outSplit[i], 1e-6f)
    }
}
