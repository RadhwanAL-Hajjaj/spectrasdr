// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputTest {
    private fun play(output: AudioOutput, audio: FloatArray, channels: Int = 1, block: Int = 1_024) {
        val chunk = FloatArray(block * channels)
        var done = 0
        val frames = audio.size / channels
        while (done < frames) {
            val n = minOf(block, frames - done)
            System.arraycopy(audio, done * channels, chunk, 0, n * channels)
            output.write(chunk, n)
            done += n
        }
    }

    @Test
    fun resamplesToTheSinkRate() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 12_000.0, 1)
        play(output, Signals.realTone(24_000, 1_000.0, 12_000.0, 0.5))
        assertEquals(96_000.0, sink.frames.toDouble(), 100.0)
        val audio = sink.audio()
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 0.5)
        assertEquals(0.5, Signals.toneAmplitude(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size), 0.005)
    }

    @Test
    fun volumeAndMuteRampWithoutSteps() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 48_000.0, 1)
        output.volume = 0.5f
        play(output, FloatArray(4_800) { 0.4f })
        output.muted = true
        play(output, FloatArray(4_800) { 0.4f })
        val audio = sink.audio()
        assertEquals(0.2f, audio[4_000], 1e-3f)
        assertEquals(0f, audio[audio.size - 1], 0f)
        // Once the constant input has settled, the only changes are the gain ramps: 0.4 / 480 per frame.
        var worstStep = 0f
        for (i in 2_400 until audio.size) worstStep = maxOf(worstStep, kotlin.math.abs(audio[i] - audio[i - 1]))
        assertTrue("largest step $worstStep", worstStep < 0.002f)
    }

    @Test
    fun softLimiterKeepsPeaksBelowFullScale() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 48_000.0, 1)
        play(output, Signals.realTone(9_600, 1_000.0, 48_000.0, 3.0))
        play(output, Signals.realTone(9_600, 1_000.0, 48_000.0, 0.5))
        val audio = sink.audio()
        val loud = Signals.peak(audio, 4_800, 9_600)
        assertTrue("limited peak $loud", loud > 0.9f && loud < 1.0f)
        assertEquals("quiet audio is untouched", 0.5, Signals.toneAmplitude(audio, 1_000.0, 48_000.0, 14_400, 19_200), 0.005)
    }

    @Test
    fun samplesThatAreNotNumbersBecomeSilence() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 48_000.0, 1)
        val audio = Signals.realTone(4_800, 1_000.0, 48_000.0, 0.5)
        audio[2_000] = Float.NaN
        play(output, audio)
        val heard = sink.audio()
        assertTrue(heard.all { it.isFinite() })
        assertEquals("the tone is back afterwards", 0.5f, Signals.peak(heard, heard.size - 480, heard.size), 0.01f)
    }

    @Test
    fun aFullSinkSlowsTheOutputAndAnEmptyOneSpeedsItUp() {
        fun framesFor(fill: Float): Int {
            val sink = CollectingSink()
            sink.fill = fill
            val output = AudioOutput(sink, 48_000.0, 2)
            play(output, FloatArray(2 * 480_000), channels = 2)
            return sink.frames
        }
        val nominal = framesFor(0.5f)
        assertEquals(480_000.0, nominal.toDouble(), 64.0)
        // The correction is limited to ±500 ppm: 240 frames in 10 s
        assertEquals(-240.0, (framesFor(1.0f) - nominal).toDouble(), 30.0)
        assertEquals(240.0, (framesFor(0.0f) - nominal).toDouble(), 30.0)
    }
}
