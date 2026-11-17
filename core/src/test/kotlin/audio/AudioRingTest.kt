// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.concurrent.thread

class AudioRingTest {
    private fun ramp(from: Int, frames: Int, channels: Int) =
        FloatArray(frames * channels) { (from + it / channels).toFloat() }

    @Test
    fun playsSilenceUntilTheTargetLevelIsReached() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)   // target 100 frames, capacity 200
        val out = FloatArray(2 * 50)
        ring.write(ramp(1, 99, 2), 99, 2)
        assertEquals(0, ring.read(out, 50))
        assertTrue(out.all { it == 0f })
        ring.write(ramp(100, 1, 2), 1, 2)
        assertEquals(50, ring.read(out, 50))
        assertEquals(1f, out[0])
        assertEquals(50f, out[98])
        assertEquals(0L, ring.underruns)
    }

    @Test
    fun monoIsPlayedOnBothChannels() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 10)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), 10, 1)
        val out = FloatArray(2 * 3)
        assertEquals(3, ring.read(out, 3))
        assertArrayEquals(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f), out, 0f)
    }

    @Test
    fun anUnderrunIsPaddedWithSilenceAndRefillsToTheTarget() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)
        ring.write(ramp(1, 100, 2), 100, 2)
        val out = FloatArray(2 * 80)
        assertEquals(80, ring.read(out, 80))
        assertEquals(20, ring.read(out, 80))            // only 20 left: an underrun
        assertEquals(100f, out[2 * 19])
        assertEquals(0f, out[2 * 20])
        assertEquals(1L, ring.underruns)
        ring.write(ramp(1, 99, 2), 99, 2)
        assertEquals(0, ring.read(out, 80))             // still refilling
        assertEquals(1L, ring.underruns)                // silence while refilling is not another underrun
        ring.write(ramp(100, 1, 2), 1, 2)
        assertEquals(80, ring.read(out, 80))
    }

    @Test
    fun aFullBufferDropsWhatDoesNotFitAndNeverBlocks() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)
        ring.write(ramp(1, 250, 2), 250, 2)
        assertEquals(1f, ring.fillLevel(), 0f)
        assertEquals(50L, ring.droppedFrames)
        val out = FloatArray(2 * 200)
        assertEquals(200, ring.read(out, 200))
        assertEquals(200f, out[2 * 199])               // the first 200 frames were kept
    }

    @Test
    fun theFillLevelIsHalfAtTheTarget() {
        val ring = AudioRing(sampleRate = 48_000, targetMillis = 150)
        assertEquals(7_200, ring.targetFrames)
        ring.write(FloatArray(7_200), 7_200, 1)
        assertEquals(0.5f, ring.fillLevel(), 1e-6f)
    }

    @Test
    fun clearStartsOverFromSilence() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)
        ring.write(ramp(1, 150, 2), 150, 2)
        val out = FloatArray(2 * 10)
        assertEquals(10, ring.read(out, 10))
        ring.clear()
        assertEquals(0f, ring.fillLevel(), 0f)
        assertEquals(0, ring.read(out, 10))
    }

    @Test
    fun oneWriterAndOneReaderLoseNothingAndKeepTheOrder() {
        val ring = AudioRing(sampleRate = 48_000, targetMillis = 20)
        val total = 400_000
        val writer = thread(isDaemon = true) {
            var next = 0
            val buf = FloatArray(2 * 256)
            while (next < total) {
                val room = ((1f - ring.fillLevel()) * ring.capacityFrames).toInt()
                val n = minOf(256, room, total - next)
                if (n == 0) { Thread.yield(); continue }
                for (i in 0 until n) { buf[2 * i] = (next + i).toFloat(); buf[2 * i + 1] = -(next + i).toFloat() }
                ring.write(buf, n, 2)
                next += n
            }
        }
        var expected = 0
        val out = FloatArray(2 * 300)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (expected < total && System.nanoTime() < deadline) {
            val n = ring.read(out, 300)
            for (i in 0 until n) {
                assertEquals((expected + i).toFloat(), out[2 * i], 0f)
                assertEquals(-(expected + i).toFloat(), out[2 * i + 1], 0f)
            }
            expected += n
        }
        writer.join(5_000)
        assertTrue("the writer did not finish", !writer.isAlive)
        assertEquals(0L, ring.droppedFrames)
        assertTrue("read $expected of $total frames", expected >= total - ring.capacityFrames)
    }

    @Test
    fun writeAndReadAllocateNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("this JVM cannot count a thread's allocations", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        val thread = Thread.currentThread().id
        val ring = AudioRing(sampleRate = 48_000, targetMillis = 10)
        val stereo = FloatArray(2 * 64)
        val mono = FloatArray(64)
        val out = FloatArray(2 * 128)
        fun pair() {
            ring.write(stereo, 64, 2)
            ring.write(mono, 64, 1)
            ring.read(out, 128)
        }
        repeat(500) { pair() }
        // The JIT compiler can charge a one-off to this thread, so the quietest window counts.
        var quietest = Long.MAX_VALUE
        repeat(5) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(1_000) { pair() }
            quietest = minOf(quietest, bean.getThreadAllocatedBytes(thread) - before)
        }
        assertEquals("bytes allocated in 1,000 write and read pairs", 0L, quietest)
    }
}
