// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import kotlin.math.PI
import kotlin.math.atan2

/** Polar FM discriminator arg(x[n]·x*[n−1]), scaled so that ±[deviationHz] gives ±1.0 (§6.4). */
class FmDiscriminator(sampleRate: Double, deviationHz: Double) {
    private val gain = (sampleRate / (2 * PI * deviationHz)).toFloat()
    private var prevRe = 1f
    private var prevIm = 0f

    /** Writes one real output sample per complex input sample. */
    fun process(input: FloatArray, count: Int, output: FloatArray) {
        var pr = prevRe
        var pi = prevIm
        for (n in 0 until count) {
            val re = input[2 * n]
            val im = input[2 * n + 1]
            output[n] = atan2(im * pr - re * pi, re * pr + im * pi) * gain
            pr = re
            pi = im
        }
        prevRe = pr
        prevIm = pi
    }

    fun reset() {
        prevRe = 1f
        prevIm = 0f
    }
}
