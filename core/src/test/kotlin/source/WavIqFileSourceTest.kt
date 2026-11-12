// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.record.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit.SECONDS

class WavIqFileSourceTest {
    private fun iqFile(samples: Int, rate: Int = 96_000): File {
        val f = Files.createTempDirectory("iq").toFile().resolve("SpectraSDR_20261112_120000Z_145500000Hz_IQ.wav")
        f.deleteOnExit()
        val bytes = ByteArray(2 * samples)
        for (k in 0 until samples) {
            bytes[2 * k] = k.toByte()
            bytes[2 * k + 1] = (255 - (k and 0xFF)).toByte()
        }
        WavWriter(f, rate, 2, 8).use { it.write(bytes) }
        return f
    }

    @Test
    fun playsEverySampleAndStops() {
        val src = WavIqFileSource(iqFile(10_000), realtime = false)
        assertEquals(145_500_000L, src.centerFrequencyHz)
        assertEquals(SourceKind.IQ_FILE, src.info.kind)
        assertFalse(src.info.canTune)
        val l = CollectingListener()
        src.open(l)
        assertTrue(l.stopped.await(5, SECONDS))
        assertTrue(l.errors.isEmpty())
        assertEquals(10_000L, l.totalComplex.get())
        val x = l.awaitEpochSamples(epoch = 0, complex = 10_000)
        for (k in intArrayOf(0, 1, 300, 9_999)) {
            assertEquals(((k and 0xFF) - 127.5f) / 127.5f, x[2 * k], 1e-6f)
            assertEquals((255 - (k and 0xFF) - 127.5f) / 127.5f, x[2 * k + 1], 1e-6f)
        }
        assertEquals(96_000, l.blocks.first().sampleRate)
    }

    @Test
    fun loopsUntilClosed() {
        val src = WavIqFileSource(iqFile(5_000), realtime = false, loop = true)
        val l = CollectingListener(keepPerEpoch = 1)
        src.open(l)
        val deadline = System.currentTimeMillis() + 5_000
        while (l.totalComplex.get() < 25_000) {
            check(System.currentTimeMillis() < deadline) { "file did not loop" }
            Thread.sleep(5)
        }
        src.close()
        assertTrue(l.stopped.await(5, SECONDS))
    }

    @Test
    fun realtimePlaybackIsPaced() {
        val src = WavIqFileSource(iqFile(48_000, rate = 96_000), realtime = true)
        val l = CollectingListener()
        val start = System.nanoTime()
        src.open(l)
        assertTrue(l.stopped.await(5, SECONDS))
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue("took $seconds s", seconds >= 0.45)
    }
}
