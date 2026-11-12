// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import java.io.File
import java.io.RandomAccessFile

/**
 * PCM WAV writer that keeps the file playable at all times (§9): the RIFF and data sizes are
 * rewritten every [headerIntervalMs] and on [close], so a crash loses at most that much audio.
 */
class WavWriter(
    file: File,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    private val headerIntervalMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteArray(64 * 1024)
    private var buffered = 0
    private var lastHeader = clock()

    var dataBytes = 0L
        private set

    init {
        require(bitsPerSample == 8 || bitsPerSample == 16) { "8 or 16 bits per sample" }
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        var o = off
        var l = len
        while (l > 0) {
            val n = minOf(l, buffer.size - buffered)
            System.arraycopy(bytes, o, buffer, buffered, n)
            buffered += n
            o += n
            l -= n
            if (buffered == buffer.size) flushBuffer()
        }
        dataBytes += len
        if (clock() - lastHeader >= headerIntervalMs) updateHeader()
    }

    /** Writes buffered data and rewrites the header so the file is valid up to this point. */
    fun updateHeader() {
        flushBuffer()
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(pos)
        lastHeader = clock()
    }

    override fun close() {
        updateHeader()
        raf.close()
    }

    private fun flushBuffer() {
        if (buffered > 0) {
            raf.write(buffer, 0, buffered)
            buffered = 0
        }
    }

    private fun header(data: Long): ByteArray {
        val h = ByteArray(44)
        fun text(o: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, o)
        fun u32(o: Int, v: Long) {
            h[o] = v.toByte()
            h[o + 1] = (v shr 8).toByte()
            h[o + 2] = (v shr 16).toByte()
            h[o + 3] = (v shr 24).toByte()
        }
        fun u16(o: Int, v: Int) {
            h[o] = v.toByte()
            h[o + 1] = (v shr 8).toByte()
        }
        val blockAlign = channels * bitsPerSample / 8
        text(0, "RIFF")
        u32(4, minOf(36 + data, 0xFFFFFFFFL))
        text(8, "WAVE")
        text(12, "fmt ")
        u32(16, 16)
        u16(20, 1)
        u16(22, channels)
        u32(24, sampleRate.toLong())
        u32(28, sampleRate.toLong() * blockAlign)
        u16(32, blockAlign)
        u16(34, bitsPerSample)
        text(36, "data")
        u32(40, minOf(data, 0xFFFFFFFFL - 36))
        return h
    }
}
