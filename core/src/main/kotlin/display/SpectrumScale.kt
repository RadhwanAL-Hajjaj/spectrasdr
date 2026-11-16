// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.display

/**
 * Maps a spectrum frame onto a drawing area: bins and frequencies to a horizontal fraction
 * (0 = left edge, 1 = right edge), levels to a vertical fraction (0 = top, 1 = bottom).
 *
 * The frame spans [sampleRate] around [centerHz]; bin 0 is the lowest frequency.
 */
class SpectrumScale(val centerHz: Long, val sampleRate: Int, val topDb: Float = 0f, val bottomDb: Float = -120f) {
    init {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(topDb > bottomDb) { "the top level must be above the bottom level" }
    }

    val lowHz: Double get() = centerHz - sampleRate / 2.0

    /** Horizontal position of [hz]; outside 0–1 when the frequency is outside the frame. */
    fun xOf(hz: Double): Float = ((hz - lowHz) / sampleRate).toFloat()

    /** Horizontal position of the centre of [bin] out of [bins]. */
    fun xOfBin(bin: Int, bins: Int): Float = (bin + 0.5f) / bins

    /** The frequency at horizontal position [x]. */
    fun hzAt(x: Float): Long = Math.round(lowHz + x.toDouble() * sampleRate)

    /** Vertical position of [db], limited to the drawing area. */
    fun yOf(db: Float): Float = ((topDb - db) / (topDb - bottomDb)).coerceIn(0f, 1f)

    companion object {
        /**
         * Reduces [bins] levels to [out].size columns by taking the strongest bin in each column,
         * so narrow signals stay visible on a screen narrower than the FFT.
         */
        fun columns(db: FloatArray, bins: Int, out: FloatArray) {
            val n = out.size
            require(n in 1..bins) { "columns must be 1..bins" }
            for (c in 0 until n) {
                val from = (c.toLong() * bins / n).toInt()
                val to = maxOf(from + 1, ((c + 1).toLong() * bins / n).toInt())
                var m = db[from]
                for (i in from + 1 until to) if (db[i] > m) m = db[i]
                out[c] = m
            }
        }
    }
}
