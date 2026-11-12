// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/**
 * A run of complex samples (interleaved I, Q floats). Blocks come from a [BlockPool] and must be
 * handed back with [release] once consumed.
 */
class IqBlock internal constructor(val capacity: Int, private val pool: BlockPool?) {
    /** Interleaved I, Q; only the first [length] complex samples are valid. */
    val samples = FloatArray(capacity * 2)

    /** Number of valid complex samples. */
    var length = 0

    /** Increases by one per block from the same source. */
    var seq = 0L

    /** Tuner centre frequency of these samples, in Hz. */
    var centerHz = 0L

    var sampleRate = 0

    /** Tune epoch: blocks with epoch ≥ E are at the frequency requested by the retune that returned E. */
    var tuneEpoch = 0

    internal var inPool = false

    /** Returns the block to its pool. Releasing twice is a programming error. */
    fun release() {
        pool?.recycle(this)
    }

    companion object {
        /** A block that belongs to no pool (tests, one-off buffers). */
        fun standalone(capacity: Int) = IqBlock(capacity, null)
    }
}

/** Thread-safe pool of equally sized blocks; allocates a new block when empty. */
class BlockPool(val blockCapacity: Int, preallocate: Int = 0) {
    private val free = ArrayDeque<IqBlock>()
    private val lock = Any()

    @Volatile
    var allocated = 0
        private set

    init {
        repeat(preallocate) {
            free.addLast(IqBlock(blockCapacity, this).also { it.inPool = true })
            allocated++
        }
    }

    fun obtain(): IqBlock {
        val block = synchronized(lock) {
            free.removeLastOrNull() ?: IqBlock(blockCapacity, this).also { allocated++ }
        }
        block.inPool = false
        block.length = 0
        return block
    }

    internal fun recycle(block: IqBlock) {
        synchronized(lock) {
            check(!block.inPool) { "IqBlock released twice" }
            block.inPool = true
            free.addLast(block)
        }
    }

    val freeCount: Int
        get() = synchronized(lock) { free.size }
}
