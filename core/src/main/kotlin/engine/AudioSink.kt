// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

/**
 * Where the receiver's audio goes (§4.2): an AudioTrack on Android, a collector in tests.
 *
 * The receiver calls [fillLevel] and [write] on its DSP thread, one after the other for each
 * piece of audio. The audio has gaps: nothing is written while blocks are discarded after a retune
 * or while the sample rate is too low for the mode. The engine's drift compensation trims the
 * resampling ratio by at most ±500 ppm to hold the buffer at its target level; it does not
 * re-centre the buffer. So after an underrun the sink itself plays silence until its buffer is back
 * at its target level.
 */
interface AudioSink {
    /** The rate the sink plays at, in Hz (48,000 on Android). */
    val sampleRate: Int

    /**
     * Queues [frames] interleaved frames of [channels] channels; [frames] may be 0. Called on the
     * DSP thread and must never block: it returns within a few milliseconds and drops what does
     * not fit. [channels] is 1 or 2 and can change from one call to the next when the mode changes.
     * [samples] is the receiver's own buffer: copy what is kept.
     */
    fun write(samples: FloatArray, frames: Int, channels: Int)

    /**
     * Buffer fill level from 0 (empty) to 1 (full); drives the drift compensation (§6.5). Read on
     * the DSP thread just before each [write].
     */
    fun fillLevel(): Float
}
