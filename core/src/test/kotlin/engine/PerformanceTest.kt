// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBand
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzer
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.TestStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** The performance rules of §4.4, checked on the JVM (§12). */
class PerformanceTest {
    /** A sink that keeps nothing, so that it neither allocates nor costs time. */
    private class CountingSink : AudioSink {
        override val sampleRate = 48_000
        var frames = 0L

        override fun write(samples: FloatArray, frames: Int, channels: Int) {
            this.frames += frames
        }

        override fun fillLevel() = 0.5f
    }

    @Test
    fun wfmStereoWithRdsAndSpectrumRunsThreeTimesFasterThanRealTime() {
        val rate = 2_400_000
        val seconds = 1.0
        val iq = TestStations.fmBroadcast(rate.toDouble(), 300_000.0, seconds, 1_000.0, 400.0, RdsEncoder(0xD318, "SPECTRA ", "Throughput"))
        val sink = CountingSink()
        val chain = ReceiverChain(rate, Mode.WFM, 200_000, sink)
        chain.offsetHz = 300_000.0
        val analyzer = SpectrumAnalyzer(4_096)
        val block = FloatArray(2 * 16_384)
        val blocks = iq.size / 2 / 16_384
        var best = 0.0
        repeat(8) { round ->                  // the first rounds warm the JIT compiler up
            val began = System.nanoTime()
            for (b in 0 until blocks) {
                System.arraycopy(iq, 2 * b * 16_384, block, 0, 2 * 16_384)
                analyzer.process(block, 16_384, rate, 0L)
                chain.process(block, 16_384)
            }
            val elapsed = (System.nanoTime() - began) / 1e9
            if (round >= 2) best = maxOf(best, blocks * 16_384 / rate.toDouble() / elapsed)
        }
        println("WFM stereo + RDS + 4,096-point spectrum at 2.4 MS/s: %.1f times real time".format(best))
        assertTrue(chain.stereo)
        assertEquals("SPECTRA ", chain.rds!!.state.ps)
        assertEquals("audio came out at 48 kHz", 8 * blocks * 16_384 / 50.0, sink.frames.toDouble(), 500.0)
        assertTrue("only %.1f times real time".format(best), best >= 3.0)
    }

    @Test
    fun theSignalPathAllocatesNothingOnceItRuns() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("this JVM cannot count a thread's allocations", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        val thread = Thread.currentThread().id
        val rate = 960_000
        val station = SimStation.FmBroadcast(100_100_000, "ALLOC FM", "No garbage")
        for (mode in Mode.values()) {
            val band = SimBand(listOf(station), rate)
            val sink = CountingSink()
            val chain = ReceiverChain(rate, mode, mode.defaultBandwidthHz, sink)
            chain.offsetHz = 100_000.0
            val analyzer = SpectrumAnalyzer(4_096)
            val block = FloatArray(2 * 16_384)
            fun step() {
                block.fill(0f)
                band.render(block, 16_384, 100_000_000L)
                analyzer.process(block, 16_384, rate, 100_000_000L)
                chain.noiseDensityDb = analyzer.noiseDensityDb
                chain.process(block, 16_384)
            }
            repeat(200) { step() }            // 3.4 s: every buffer has its size and the RDS text is complete
            // Garbage made per block shows in every window. The JIT compiler, though, can charge a
            // one-off to this thread when it swaps running code, so the quietest window counts.
            // The checks below are made outside the measured windows; the windows only read counters.
            val framesBefore = sink.frames
            val stereoBefore = chain.stereo
            val psBefore = chain.rds?.state?.ps
            var quietest = Long.MAX_VALUE
            repeat(5) {
                val before = bean.getThreadAllocatedBytes(thread)
                repeat(50) { step() }
                quietest = minOf(quietest, bean.getThreadAllocatedBytes(thread) - before)
            }
            // The measured path must have produced audio, or the figure above would be vacuous:
            // 250 blocks of 16,384 samples at 960 kS/s are 4.27 s, i.e. 204,800 frames at 48 kHz.
            val produced = sink.frames - framesBefore
            assertTrue("$mode produced only $produced frames in the measured windows", produced >= 0.9 * 250 * 16_384 / rate * 48_000)
            if (mode == Mode.WFM) {
                assertTrue("WFM was not in stereo after the warm-up", stereoBefore)
                assertEquals("ALLOC FM", psBefore)
            }
            assertTrue("$mode allocated $quietest bytes in 50 blocks", quietest < 500)
        }
    }
}
