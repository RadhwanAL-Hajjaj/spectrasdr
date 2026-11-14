// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

/** Where the receiver's audio goes (§4.2): an AudioTrack on Android, a collector in tests. */
interface AudioSink {
    /** The rate the sink plays at, in Hz (48,000 on Android). */
    val sampleRate: Int

    /** Queues [frames] interleaved frames of [channels] channels. Must return within a few milliseconds. */
    fun write(samples: FloatArray, frames: Int, channels: Int)

    /** Buffer fill level from 0 (empty) to 1 (full); drives the drift compensation (§6.5). */
    fun fillLevel(): Float
}
