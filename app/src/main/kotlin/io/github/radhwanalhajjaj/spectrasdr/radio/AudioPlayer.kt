// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRing

/**
 * Plays what the receiver puts into the [AudioRing]: an AudioTrack fed by its own thread in 10 ms
 * pieces. The ring decides between audio and silence, so the track never starves.
 */
class AudioPlayer(private val ring: AudioRing) {
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var paused = false

    /** Frames handed to the device since [start]; for diagnostics and tests. */
    @Volatile
    var framesPlayed = 0L
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        paused = false
        framesPlayed = 0
        thread = Thread({ play() }, "audio-player").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    /** Silences the output without ending the session (another app took the audio focus). */
    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    private fun play() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = ring.sampleRate
        val piece = rate / 100
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, 4 * piece * BYTES_PER_FRAME))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = FloatArray(2 * piece)
        try {
            ring.clear()
            track.play()
            var wasPaused = false
            while (running) {
                if (paused) {
                    if (!wasPaused) {
                        track.pause()
                        track.flush()
                        wasPaused = true
                    }
                    Thread.sleep(20)
                    continue
                }
                if (wasPaused) {
                    ring.clear()
                    track.play()
                    wasPaused = false
                }
                ring.read(buffer, piece)
                val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
                framesPlayed += written / 2
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = 2 * 4   // stereo float
    }
}
