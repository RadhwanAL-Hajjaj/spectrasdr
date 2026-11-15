// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioSink

/** An [AudioSink] that keeps everything it is given and reports a settable fill level. */
class CollectingSink(override val sampleRate: Int = 48_000) : AudioSink {
    private var data = FloatArray(1 shl 16)
    private var used = 0

    @Volatile
    var channels = 0
        private set

    @Volatile
    var fill = 0.5f

    /** Frames written so far. */
    val frames: Int
        @Synchronized get() = if (channels == 0) 0 else used / channels

    @Synchronized
    override fun write(samples: FloatArray, frames: Int, channels: Int) {
        this.channels = channels
        val n = frames * channels
        if (used + n > data.size) data = data.copyOf(maxOf(2 * data.size, used + n))
        System.arraycopy(samples, 0, data, used, n)
        used += n
    }

    override fun fillLevel(): Float = fill

    /** A copy of all interleaved audio written so far. */
    @Synchronized
    fun audio(): FloatArray = data.copyOf(used)
}
