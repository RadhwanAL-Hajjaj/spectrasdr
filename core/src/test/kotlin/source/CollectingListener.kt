// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/** Test listener: copies blocks (up to [keepPerEpoch] samples per tune epoch) and records events. */
class CollectingListener(private val keepPerEpoch: Long = 1_000_000) : SourceListener {
    class Captured(val seq: Long, val epoch: Int, val centerHz: Long, val sampleRate: Int, val samples: FloatArray)

    val started = CountDownLatch(1)
    val stopped = CountDownLatch(1)

    @Volatile
    var info: SourceInfo? = null

    val errors = CopyOnWriteArrayList<SourceError>()
    val blocks = CopyOnWriteArrayList<Captured>()
    val totalComplex = AtomicLong()
    private val kept = ConcurrentHashMap<Int, Long>()

    override fun onStarted(info: SourceInfo) {
        this.info = info
        started.countDown()
    }

    override fun onBlock(block: IqBlock) {
        val already = kept.getOrDefault(block.tuneEpoch, 0L)
        if (already < keepPerEpoch) {
            blocks += Captured(block.seq, block.tuneEpoch, block.centerHz, block.sampleRate, block.samples.copyOf(2 * block.length))
            kept[block.tuneEpoch] = already + block.length
        }
        totalComplex.addAndGet(block.length.toLong())
        block.release()
    }

    override fun onError(error: SourceError) {
        errors += error
    }

    override fun onStopped() {
        stopped.countDown()
    }

    fun blocksOf(epoch: Int): List<Captured> = blocks.filter { it.epoch == epoch }

    /** Waits for [complex] samples of [epoch]; returns them concatenated (interleaved I, Q). */
    fun awaitEpochSamples(epoch: Int, complex: Int, timeoutMs: Long = 20_000): FloatArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val got = blocksOf(epoch)
            if (got.sumOf { it.samples.size / 2 } >= complex) {
                val out = FloatArray(2 * complex)
                var pos = 0
                for (c in got) {
                    val n = minOf(c.samples.size, out.size - pos)
                    System.arraycopy(c.samples, 0, out, pos, n)
                    pos += n
                    if (pos == out.size) break
                }
                return out
            }
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $complex samples of epoch $epoch; errors=${errors.map { it.message }}"
            }
            Thread.sleep(5)
        }
    }
}
