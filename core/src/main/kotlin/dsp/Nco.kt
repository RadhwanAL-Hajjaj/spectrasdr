// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Numerically controlled oscillator that shifts interleaved complex samples by multiplying them
 * with exp(j·2π·f·n/fs). Phasor recursion in double precision with periodic renormalisation keeps
 * trigonometry out of the per-sample loop (§4.4).
 */
class Nco(private val sampleRate: Double) {
    private var re = 1.0
    private var im = 0.0
    private var stepRe = 1.0
    private var stepIm = 0.0
    private var sinceRenorm = 0

    var frequencyHz = 0.0
        set(value) {
            field = value
            val w = 2.0 * PI * value / sampleRate
            stepRe = cos(w)
            stepIm = sin(w)
        }

    /** Multiplies [count] complex samples of [data], starting at complex index [offset]. */
    fun mix(data: FloatArray, offset: Int, count: Int) {
        var i = offset * 2
        var r = re
        var m = im
        val sr = stepRe
        val si = stepIm
        for (n in 0 until count) {
            val x = data[i].toDouble()
            val y = data[i + 1].toDouble()
            data[i] = (x * r - y * m).toFloat()
            data[i + 1] = (x * m + y * r).toFloat()
            val nr = r * sr - m * si
            m = r * si + m * sr
            r = nr
            i += 2
            if (++sinceRenorm == 1024) {
                val g = 1.0 / sqrt(r * r + m * m)
                r *= g
                m *= g
                sinceRenorm = 0
            }
        }
        re = r
        im = m
    }

    fun reset() {
        re = 1.0
        im = 0.0
        sinceRenorm = 0
    }
}
