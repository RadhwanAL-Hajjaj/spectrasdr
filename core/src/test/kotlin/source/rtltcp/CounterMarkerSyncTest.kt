// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

class CounterMarkerSyncTest {
    /** [stale] random bytes, a counter burst of [burst] bytes, then [fresh] bytes that never continue it. */
    private fun stream(stale: Int, burst: Int, fresh: Int, rnd: Random = Random(3)): ByteArray {
        val b = ByteArray(stale + burst + fresh)
        for (i in 0 until stale) b[i] = rnd.nextInt(256).toByte()
        var c = rnd.nextInt(256)
        for (i in stale until stale + burst) {
            b[i] = c.toByte()
            c = (c + 1) and 0xFF
        }
        for (i in stale + burst until b.size) b[i] = (c + 50 + rnd.nextInt(100)).toByte()
        return b
    }

    private fun scanAll(sync: CounterMarkerSync, data: ByteArray, chunk: Int): Int {
        var pos = 0
        while (pos < data.size) {
            val len = minOf(chunk, data.size - pos)
            val r = sync.scan(data, pos, len)
            if (r >= 0) return pos + r
            pos += len
        }
        return -1
    }

    @Test
    fun boundaryIsTheEndOfTheBurstPlusTheGuard() {
        for (chunk in intArrayOf(2, 8, 100, 4_096, 1 shl 20)) {   // sample-aligned chunks, as the client passes
            val sync = CounterMarkerSync(guardMs = 1.0)
            sync.onRetuneRequested(1, bytesPerSample = 2, sampleRate = 1_000_000)   // guard = 1000 samples
            assertEquals("chunk $chunk", 10_000 + 5_000 + 2_000, scanAll(sync, stream(10_000, 5_000, 10_000), chunk))
        }
    }

    @Test
    fun breakOnTheQByteAlignsToTheSampleStart() {
        val sync = CounterMarkerSync(guardMs = 0.0)
        sync.onRetuneRequested(1, 2, 1_000_000)
        val b = ByteArray(1_000) { 0x40 }
        for (i in 0 until 600) b[i] = i.toByte()
        b[600] = 600.toByte()   // first real I byte happens to continue the counter
        b[601] = 0x10           // its Q byte breaks it
        assertEquals(600, sync.scan(b, 0, b.size))
    }

    @Test
    fun waitsForEveryRequestedBurst() {
        val sync = CounterMarkerSync(guardMs = 0.0)
        sync.onRetuneRequested(2, 2, 1_000_000)
        val first = stream(1_000, 3_000, 1_000)
        val data = first + stream(1_000, 3_000, 1_000, Random(9))
        assertEquals(first.size + 4_000, scanAll(sync, data, 512))
        assertEquals(2L, sync.bursts)
        assertEquals(0, sync.timeouts)
    }

    @Test
    fun neverFiresOnRandomOrNoiseLikeData() {
        val sync = CounterMarkerSync(timeoutMs = 1e9)
        sync.onRetuneRequested(1, 2, 2_400_000)
        val rnd = Random(11)
        val uniform = ByteArray(5_000_000) { rnd.nextInt(256).toByte() }
        assertEquals(-1, sync.scan(uniform, 0, uniform.size))
        val gaussian = ByteArray(5_000_000) { (127.5 + 3 * rnd.nextGaussian()).roundToInt().coerceIn(0, 255).toByte() }
        assertEquals(-1, sync.scan(gaussian, 0, gaussian.size))
    }

    @Test
    fun timesOutWhenTestModeIsIgnored() {
        var now = 0L
        val sync = CounterMarkerSync(timeoutMs = 100.0, nanoTime = { now })
        sync.onRetuneRequested(1, 2, 1_000_000)   // 100 000 samples = 200 000 bytes
        now = 100_000_000L
        val rnd = Random(5)
        val data = ByteArray(300_000) { rnd.nextInt(256).toByte() }
        assertEquals(200_000, sync.scan(data, 0, data.size))
        assertTrue(sync.timedOut)
        assertEquals(1, sync.timeouts)
        assertEquals(0L, sync.bursts)
    }

    @Test
    fun aBacklogAloneDoesNotTimeOut() {
        var now = 0L
        val sync = CounterMarkerSync(timeoutMs = 100.0, nanoTime = { now })
        sync.onRetuneRequested(1, 2, 1_000_000)   // timeoutBytes = 200 000
        val rnd = Random(5)
        val data = ByteArray(500_000) { rnd.nextInt(256).toByte() }
        assertEquals(-1, sync.scan(data, 0, data.size))
        assertEquals(0, sync.timeouts)
        now = 100_000_000L
        assertEquals(1, sync.scan(data, 0, data.size).let { if (it >= 0) 1 else 0 })
        assertEquals(1, sync.timeouts)
    }

    @Test
    fun wrapAddsATestModePulseAfterTheRetuneCommands() {
        assertEquals(
            listOf(TimedCommand(0x01, 5), TimedCommand(0x07, 1), TimedCommand(0x07, 0, 5)),
            CounterMarkerSync().wrap(listOf(TimedCommand(0x01, 5))),
        )
    }
}
