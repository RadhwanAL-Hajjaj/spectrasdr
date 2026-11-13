// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import java.io.File
import java.io.RandomAccessFile

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
)

/**
 * Reads the format of PCM WAV files, tolerating unfinalised or crash-truncated headers.
 * Malformed headers raise [IllegalArgumentException] "<name> is not a valid WAV file: <reason>".
 */
object WavReader {
    fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
        fun bad(reason: String): Nothing = throw IllegalArgumentException("${file.name} is not a valid WAV file: $reason")
        val length = raf.length()
        if (length < 12) bad("the file is too short")
        val riff = ByteArray(12)
        raf.readFully(riff)
        if (text(riff, 0) != "RIFF" || text(riff, 8) != "WAVE") bad("missing RIFF/WAVE header")
        var format: IntArray? = null   // [tag, channels, rate, bits]
        val chunk = ByteArray(8)
        while (raf.filePointer + 8 <= length) {
            raf.readFully(chunk)
            val id = text(chunk, 0)
            val size = u32(chunk, 4)
            when (id) {
                "fmt " -> {
                    if (size < 16 || size > 1024) bad("unusable fmt chunk size $size")
                    if (raf.filePointer + size > length) bad("truncated fmt chunk")
                    val f = ByteArray(size.toInt())
                    raf.readFully(f)
                    val rate = u32(f, 4).toInt()
                    if (rate <= 0) bad("invalid sample rate $rate")
                    format = intArrayOf(u16(f, 0), u16(f, 2), rate, u16(f, 14))
                    if (size % 2 == 1L && raf.filePointer < length) raf.skipBytes(1)
                }
                "data" -> {
                    val f = format ?: bad("data chunk before fmt chunk")
                    if (f[0] != 1) bad("only PCM WAV is supported")
                    val start = raf.filePointer
                    val available = length - start
                    val bytes = if (size == 0L || size > available) available else size
                    return WavInfo(f[2], f[1], f[3], start, bytes)
                }
                else -> raf.seek(raf.filePointer + size + (size and 1L))
            }
        }
        bad(if (format == null) "no fmt chunk" else "no data chunk")
    }

    private fun text(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)

    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24))
}
