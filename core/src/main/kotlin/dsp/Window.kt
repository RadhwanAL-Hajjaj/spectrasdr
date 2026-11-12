// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.cos

enum class WindowType { HANN, BLACKMAN_HARRIS }

object Windows {
    /** Periodic (DFT-even) window of length [n] for spectral analysis (§6.2). */
    fun create(type: WindowType, n: Int): FloatArray = FloatArray(n) { i ->
        val x = 2.0 * PI * i / n
        when (type) {
            WindowType.HANN -> 0.5 - 0.5 * cos(x)
            WindowType.BLACKMAN_HARRIS ->
                0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
        }.toFloat()
    }

    /** Sum of the window values; dBFS = 10·log10(|X|²) − 20·log10(sum) (§6.2). */
    fun sum(window: FloatArray): Double {
        var s = 0.0
        for (v in window) s += v
        return s
    }
}
