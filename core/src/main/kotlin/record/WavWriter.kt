// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import java.io.File
import java.io.IOException
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
    private val raf: RandomAccessFile
    private val buffer = ByteArray(64 * 1024)
    private var buffered = 0
    private var pendingHeader = 0
    private var lastHeader = clock()
    private var persisted = 0L
    private var failed = false
    private var closed = false

    /** Bytes accepted so far; the header only ever counts the ones whose flush succeeded. */
    val dataBytes: Long get() = persisted + buffered - pendingHeader

    init {
        require(bitsPerSample == 8 || bitsPerSample == 16) { "8 or 16 bits per sample" }
        raf = RandomAccessFile(file, "rw")
        try {
            raf.setLength(0)
        } catch (_: IOException) {
            // Non-regular files (devices, pipes) cannot be truncated; nothing to discard there.
        }
        // The first header travels with the first buffer flush, so a full disk shows up as a write error.
        val h = header(0)
        h.copyInto(buffer)
        buffered = h.size
        pendingHeader = h.size
    }

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (failed) throw IOException("An earlier write to the WAV file failed")
        try {
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
            if (clock() - lastHeader >= headerIntervalMs) updateHeader()
        } catch (e: IOException) {
            fail()
            throw e
        }
    }

    /** Writes buffered data and rewrites the header so the file is valid up to this point. */
    fun updateHeader() {
        if (failed) throw IOException("An earlier write to the WAV file failed")
        try {
            flushBuffer()
            writeHeader()
        } catch (e: IOException) {
            fail()
            throw e
        }
    }

    /** Idempotent. Always releases the file; after a failed write it never throws. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (failed) {
                bestEffortHeader()
            } else {
                try {
                    flushBuffer()
                    writeHeader()
                } catch (e: IOException) {
                    failed = true
                    bestEffortHeader()
                    throw e
                }
            }
        } finally {
            try {
                raf.close()
            } catch (_: IOException) {
                // nothing more can be done
            }
        }
    }

    private fun fail() {
        failed = true
        bestEffortHeader()
    }

    private fun bestEffortHeader() {
        try {
            writeHeader()
        } catch (_: IOException) {
            // The disk is already failing; the header keeps whatever it last held.
        }
    }

    private fun writeHeader() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(persisted))
        raf.seek(pos)
        lastHeader = clock()
    }

    private fun flushBuffer() {
        if (buffered > 0) {
            raf.write(buffer, 0, buffered)
            persisted += buffered - pendingHeader
            pendingHeader = 0
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
