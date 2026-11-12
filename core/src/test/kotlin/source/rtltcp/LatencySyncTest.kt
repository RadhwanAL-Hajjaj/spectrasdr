// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencySyncTest {
    @Test
    fun boundaryFallsAfterTheSettleSamples() {
        for (chunk in intArrayOf(2, 1_000, 65_536)) {
            val sync = LatencySync(settleSamples = 10_000)
            sync.onRetuneRequested(1, bytesPerSample = 4, sampleRate = 2_000_000)
            val data = ByteArray(100_000)
            var pos = 0
            var boundary = -1
            while (pos < data.size && boundary < 0) {
                val len = minOf(chunk, data.size - pos)
                val r = sync.scan(data, pos, len)
                if (r >= 0) boundary = pos + r else pos += len
            }
            assertEquals("chunk $chunk", 40_000, boundary)
        }
    }

    @Test
    fun detectorFindsThePowerDrop() {
        val samples = FloatArray(2 * 6_000) { i -> if (i / 2 < 3_000) 1f else 0.1f }
        val d = PowerStepDetector(windowSamples = 512, thresholdDb = 10.0).apply { baseline = 2.0 }
        assertEquals(3_584, d.feed(samples, 6_000))
    }

    @Test
    fun detectorIgnoresSmallChanges() {
        val samples = FloatArray(2 * 4_096) { 0.6f }   // power 0.72 vs baseline 1.0: −1.4 dB
        val d = PowerStepDetector().apply { baseline = 1.0 }
        assertEquals(-1, d.feed(samples, 4_096))
    }
}
