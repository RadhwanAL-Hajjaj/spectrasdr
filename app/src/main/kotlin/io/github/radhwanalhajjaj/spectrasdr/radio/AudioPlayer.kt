// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRing
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays what the receiver puts into the [AudioRing]: an AudioTrack fed by its own thread in 10 ms
 * pieces. The ring decides between audio and silence, so the track never starves.
 */
class AudioPlayer(private val ring: AudioRing, private val onError: (String) -> Unit = {}) {
    private var thread: Thread? = null
    private var token: AtomicBoolean? = null   // the running flag of the current thread; guarded by this

    @Volatile private var paused = false

    /** Frames handed to the device since [start]; for diagnostics and tests. */
    @Volatile
    var framesPlayed = 0L
        private set

    @Synchronized
    fun start() {
        paused = false
        val old = thread
        if (old != null && old.isAlive) {
            if (token?.get() == true) return
            old.join(1_000)   // a thread that was told to stop: let it finish before a new one uses the ring
        }
        val running = AtomicBoolean(true)
        token = running
        framesPlayed = 0
        thread = Thread({ play(running) }, "audio-player").apply { start() }
    }

    @Synchronized
    fun stop() {
        token?.set(false)
        thread?.let {
            it.join(1_000)
            if (!it.isAlive) thread = null
        }
    }

    /** Silences the output without ending the session (another app took the audio focus). */
    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    private fun play(running: AtomicBoolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = ring.sampleRate
        val piece = rate / 100
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
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
        } catch (e: RuntimeException) {
            if (running.get()) report(e)
            return
        }
        val buffer = FloatArray(2 * piece)
        try {
            ring.clear()
            track.play()
            var wasPaused = false
            while (running.get()) {
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
        } catch (e: RuntimeException) {
            if (running.get()) report(e)
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    /** Reports from another thread: the report stops this player, which must not wait for itself. */
    private fun report(e: RuntimeException) {
        val text = e.message ?: e.javaClass.simpleName
        Thread({ onError(text) }, "audio-player-error").start()
    }

    private companion object {
        const val BYTES_PER_FRAME = 2 * 4   // stereo float
    }
}
