// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.record.RecordingNames
import io.github.radhwanalhajjaj.spectrasdr.core.record.WavReader
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays a 2-channel IQ WAV (8-bit unsigned or 16-bit signed) as a source (§5.4), paced in real time
 * unless [realtime] is false. The centre frequency comes from the file name or [centerHzOverride].
 */
class WavIqFileSource(
    private val file: File,
    private val realtime: Boolean = true,
    private val loop: Boolean = false,
    centerHzOverride: Long? = null,
    private val blockSize: Int = 16_384,
) : IqSource {
    private val wav = WavReader.read(file)
    private val format = when (wav.bitsPerSample) {
        8 -> SampleFormat.U8
        16 -> SampleFormat.S16LE
        else -> error("${file.name}: unsupported ${wav.bitsPerSample}-bit samples")
    }

    init {
        require(wav.channels == 2) { "${file.name}: IQ files need 2 channels" }
    }

    override val centerFrequencyHz: Long = centerHzOverride ?: RecordingNames.parseCenterHz(file.name) ?: 0L
    override val info = SourceInfo(
        name = file.name,
        kind = SourceKind.IQ_FILE,
        sampleFormat = format,
        canTune = false,
        sampleRates = listOf(wav.sampleRate),
        gainModel = GainModel.None,
        options = emptySet(),
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    private val pool = BlockPool(blockSize, preallocate = 4)

    override fun open(listener: SourceListener) {
        check(!running) { "already open" }
        running = true
        thread = Thread({ play(listener) }, "iq-file").apply {
            isDaemon = true
            start()
        }
    }

    private fun play(listener: SourceListener) {
        try {
            listener.onStarted(info)
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(blockSize * format.bytesPerComplex)
                var seq = 0L
                var emitted = 0L
                val startNs = System.nanoTime()
                do {
                    raf.seek(wav.dataOffset)
                    var remaining = wav.dataBytes - wav.dataBytes % format.bytesPerComplex
                    while (running && remaining > 0) {
                        val want = minOf(bytes.size.toLong(), remaining).toInt()
                        raf.readFully(bytes, 0, want)
                        remaining -= want
                        val b = pool.obtain()
                        b.length = SampleConverter.convert(format, bytes, 0, want, b.samples, 0, b.capacity)
                        b.seq = seq++
                        b.centerHz = centerFrequencyHz
                        b.sampleRate = wav.sampleRate
                        b.tuneEpoch = 0
                        if (realtime) {
                            emitted += b.length
                            val wait = startNs + emitted * 1_000_000_000L / wav.sampleRate - System.nanoTime()
                            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        }
                        listener.onBlock(b)
                    }
                } while (running && loop)
            }
        } catch (e: Exception) {
            if (running) listener.onError(SourceError.Io("${file.name}: ${e.message}"))
        } finally {
            running = false
            listener.onStopped()
        }
    }

    override fun close() {
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    }

    override fun setCenterFrequency(hz: Long): Int = 0

    override fun setSampleRate(sps: Int) {}

    override fun setGain(gain: GainSetting) {}

    override fun setPpm(ppm: Int) {}

    override fun setOption(option: SourceOption) {}
}
