// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.audio

import io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioSink

/**
 * The buffer between the receiver's DSP thread and the device's audio thread (§6.5, §11): an
 * [AudioSink] that never blocks its writer, and a [read] side that always has something to play.
 *
 * It holds stereo frames (mono audio is copied to both channels, so a change of mode needs no
 * change on the playing side). The target level is half the capacity, which is where the
 * receiver's drift compensation holds it. When the reader finds too little, it plays silence until
 * the buffer is back at the target: this happens at the start, after a retune, and after any
 * underrun.
 *
 * One thread may write and one may read; [fillLevel] and the counters may be read from any thread.
 */
class AudioRing(override val sampleRate: Int = 48_000, targetMillis: Int = 150) : AudioSink {
    init {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(targetMillis in 10..2_000) { "target $targetMillis ms must be between 10 and 2000" }
    }

    /** Frames the reader waits for before it plays; the buffer holds twice as many. */
    val targetFrames = (sampleRate.toLong() * targetMillis / 1000).toInt()
    val capacityFrames = 2 * targetFrames

    private val data = FloatArray(2 * capacityFrames)

    // Frames written and read since the start; each is changed by one thread only.
    @Volatile private var written = 0L
    @Volatile private var taken = 0L

    private var refilling = true   // reader's state

    /** Times the reader ran out of audio while playing. */
    @Volatile
    var underruns = 0L
        private set

    /** Frames the writer had to drop because the buffer was full. */
    @Volatile
    var droppedFrames = 0L
        private set

    override fun write(samples: FloatArray, frames: Int, channels: Int) {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        val w = written
        val free = capacityFrames - (w - taken).toInt()
        val n = minOf(frames, free)
        var at = (w % capacityFrames).toInt() * 2
        if (channels == 2) {
            for (f in 0 until n) {
                val i = 2 * f
                data[at] = samples[i]
                data[at + 1] = samples[i + 1]
                at += 2
                if (at == data.size) at = 0
            }
        } else {
            for (i in 0 until n) {
                data[at] = samples[i]
                data[at + 1] = samples[i]
                at += 2
                if (at == data.size) at = 0
            }
        }
        written = w + n
        if (n < frames) droppedFrames += frames - n
    }

    override fun fillLevel(): Float = (written - taken).toFloat() / capacityFrames

    /**
     * Fills [dst] with [frames] stereo frames to play: audio when there is enough, silence while
     * the buffer refills. Returns how many of the frames are audio.
     */
    fun read(dst: FloatArray, frames: Int): Int {
        require(dst.size >= 2 * frames) { "destination too small" }
        val t = taken
        val available = (written - t).toInt()
        if (refilling && available >= targetFrames) refilling = false
        val n = if (refilling) 0 else minOf(frames, available)
        var at = (t % capacityFrames).toInt() * 2
        for (f in 0 until n) {
            val i = 2 * f
            dst[i] = data[at]
            dst[i + 1] = data[at + 1]
            at += 2
            if (at == data.size) at = 0
        }
        dst.fill(0f, 2 * n, 2 * frames)
        taken = t + n
        if (!refilling && n < frames) {
            underruns++
            refilling = true
        }
        return n
    }

    /** Throws away what is buffered and waits for the target level again. Reader's thread only. */
    fun clear() {
        taken = written
        refilling = true
    }
}
