// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Peak-tracking AGC for real audio (AM, SSB, CW — §6.4): fast attack, hang, then slow decay. The
 * output peak settles at [targetPeak]; gain never exceeds [maxGainDb].
 */
class Agc(
    sampleRate: Double,
    attackMs: Double = 2.0,
    decayMs: Double = 500.0,
    hangMs: Double = 200.0,
    private val targetPeak: Float = 0.5f,
    maxGainDb: Double = 60.0,
) {
    private val attack = (1 - exp(-1000.0 / (attackMs * sampleRate))).toFloat()
    private val decay = (1 - exp(-1000.0 / (decayMs * sampleRate))).toFloat()
    private val hangSamples = (hangMs * sampleRate / 1000).toInt()
    private val minEnvelope = (targetPeak / 10.0.pow(maxGainDb / 20)).toFloat()
    private var envelope = minEnvelope
    private var hang = 0

    fun process(data: FloatArray, count: Int) {
        for (i in 0 until count) {
            val x = data[i]
            val a = abs(x)
            if (a > envelope) {
                envelope += (a - envelope) * attack
                hang = hangSamples
            } else if (hang > 0) {
                hang--
            } else {
                envelope += (a - envelope) * decay
            }
            if (envelope < minEnvelope) envelope = minEnvelope
            data[i] = x * (targetPeak / envelope)
        }
    }
}
