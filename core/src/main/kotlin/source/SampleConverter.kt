// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/** Converts wire samples to interleaved float I/Q in [-1, 1]. */
object SampleConverter {
    private val u8Table = FloatArray(256) { (it - 127.5f) / 127.5f }

    /**
     * Converts whole complex samples from [src] (starting at [srcOffset], at most [byteCount] bytes)
     * into [dst] starting at complex index [dstComplexOffset], writing at most [maxComplex] samples.
     * Returns the number written. Trailing bytes of an incomplete sample are left for the caller.
     */
    fun convert(
        format: SampleFormat,
        src: ByteArray,
        srcOffset: Int,
        byteCount: Int,
        dst: FloatArray,
        dstComplexOffset: Int,
        maxComplex: Int,
    ): Int {
        val n = minOf(byteCount / format.bytesPerComplex, maxComplex)
        var s = srcOffset
        var d = dstComplexOffset * 2
        when (format) {
            SampleFormat.U8 -> repeat(n) {
                dst[d] = u8Table[src[s].toInt() and 0xFF]
                dst[d + 1] = u8Table[src[s + 1].toInt() and 0xFF]
                s += 2
                d += 2
            }
            SampleFormat.S16LE -> repeat(n) {
                val i = (src[s].toInt() and 0xFF) or (src[s + 1].toInt() shl 8)
                val q = (src[s + 2].toInt() and 0xFF) or (src[s + 3].toInt() shl 8)
                dst[d] = i.toShort() / 32768f
                dst[d + 1] = q.toShort() / 32768f
                s += 4
                d += 2
            }
        }
        return n
    }
}
