// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

/**
 * Complex-baseband FM modulator: a message of ±1.0 deviates the carrier by ±[deviationHz] around
 * [carrierHz] (the offset from the centre of the generated band).
 */
class FmModulator(private val sampleRate: Double, private val deviationHz: Double, var carrierHz: Double = 0.0) {
    private var phase = 0.0   // turns

    /**
     * Writes [count] complex samples for [message] into [out], starting at complex index [outOffset].
     * With [add] the samples are added to what [out] already holds.
     */
    fun modulate(message: FloatArray, count: Int, out: FloatArray, amplitude: Float = 1f, add: Boolean = false, outOffset: Int = 0) {
        val carrierStep = carrierHz / sampleRate
        val k = deviationHz / sampleRate
        var p = phase
        var o = 2 * outOffset
        for (n in 0 until count) {
            p += carrierStep + k * message[n]
            if (p >= 1.0) p -= 1.0 else if (p < 0.0) p += 1.0
            val re = amplitude * SineTable.cos(p)
            val im = amplitude * SineTable.sin(p)
            if (add) {
                out[o] += re
                out[o + 1] += im
            } else {
                out[o] = re
                out[o + 1] = im
            }
            o += 2
        }
        phase = p
    }
}
