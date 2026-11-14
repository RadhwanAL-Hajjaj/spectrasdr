// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode

/**
 * The RDS baseband signal for a repeating bit stream: differential encoding, biphase symbols
 * (two chips per bit) and the standard's cosine spectral shaping, generated sample by sample.
 * Each chip contributes the pulse [RdsCode.pulse].
 */
class RdsBaseband(private val bits: ByteArray, sampleRate: Double) {
    private val step = RdsCode.CHIP_RATE / sampleRate
    private val window = FloatArray(2 * SPAN)   // chips base−2 … base+3
    private var frac = 0.0
    private var bitIndex = 0
    private var secondHalf = false
    private var level = 0

    init {
        require(bits.isNotEmpty()) { "bits must not be empty" }
        repeat(SPAN + 1) { push() }
    }

    private fun push() {
        System.arraycopy(window, 1, window, 0, window.size - 1)
        if (!secondHalf) {
            level = level xor bits[bitIndex].toInt()
            bitIndex = (bitIndex + 1) % bits.size
        }
        val first = if (level == 1) 1f else -1f
        window[window.size - 1] = if (secondHalf) -first else first
        secondHalf = !secondHalf
    }

    /** The next baseband sample; chips have unit amplitude, so peaks reach about ±1.4. */
    fun next(): Float {
        var acc = 0f
        for (j in window.indices) {
            val x = (frac - (j - (SPAN - 1)) + SPAN) * RESOLUTION
            val i = x.toInt()
            acc += window[j] * (PULSE[i] + (PULSE[i + 1] - PULSE[i]) * (x - i).toFloat())
        }
        frac += step
        if (frac >= 1.0) {
            frac -= 1.0
            push()
        }
        return acc
    }

    companion object {
        private const val SPAN = 3
        private const val RESOLUTION = 64
        private val PULSE = FloatArray(2 * SPAN * RESOLUTION + 2) { RdsCode.pulse((it.toDouble() / RESOLUTION) - SPAN).toFloat() }
    }
}
