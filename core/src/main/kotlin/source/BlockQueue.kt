// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded FIFO between a source thread and the DSP thread. When full, the oldest block is released
 * back to its pool and [overruns] increases, so a slow consumer never stalls the source (§4.3).
 */
class BlockQueue(val capacity: Int) {
    private val items = ArrayDeque<IqBlock>(capacity)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    @Volatile
    var overruns = 0L
        private set

    @Volatile
    var offered = 0L
        private set

    fun offer(block: IqBlock) {
        var dropped: IqBlock? = null
        lock.withLock {
            if (items.size >= capacity) {
                dropped = items.removeFirst()
                overruns++
            }
            items.addLast(block)
            offered++
            notEmpty.signal()
        }
        dropped?.release()
    }

    /** Waits up to [timeoutMs] for a block; returns null on timeout. */
    fun take(timeoutMs: Long): IqBlock? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (items.isEmpty()) {
            if (nanos <= 0L) return null
            nanos = notEmpty.awaitNanos(nanos)
        }
        items.removeFirst()
    }

    /** Releases every queued block. */
    fun clear() {
        val drained = lock.withLock { items.toList().also { items.clear() } }
        drained.forEach { it.release() }
    }

    val size: Int
        get() = lock.withLock { items.size }
}
