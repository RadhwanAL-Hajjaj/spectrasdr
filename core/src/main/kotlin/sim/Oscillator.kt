// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sine and cosine of a fixed frequency by phasor recursion with periodic renormalisation, so test
 * signals and the simulator need no trigonometry per sample.
 */
class Oscillator(frequencyHz: Double, sampleRate: Double, phase: Double = 0.0) {
    private val stepCos = cos(2 * PI * frequencyHz / sampleRate)
    private val stepSin = sin(2 * PI * frequencyHz / sampleRate)
    private var sinceRenorm = 0

    /** cos of the current phase. */
    var cosine = cos(phase)
        private set

    /** sin of the current phase. */
    var sine = sin(phase)
        private set

    /** Advances the phase by one sample. */
    fun advance() {
        val c = cosine * stepCos - sine * stepSin
        val s = cosine * stepSin + sine * stepCos
        if (++sinceRenorm >= 1024) {
            sinceRenorm = 0
            val g = 1.0 / sqrt(c * c + s * s)
            cosine = c * g
            sine = s * g
        } else {
            cosine = c
            sine = s
        }
    }
}
