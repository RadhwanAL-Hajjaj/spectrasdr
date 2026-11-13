// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI

/**
 * PI controller that trims [FractionalResampler.correction] so the audio sink's buffer stays near
 * [target] while the SDR and phone clocks drift (§6.5). Positive output consumes input faster.
 * Gains place the closed loop at [bandwidthHz] for a sink holding [bufferSeconds] of audio.
 */
class DriftController(
    bufferSeconds: Double = 0.15,
    bandwidthHz: Double = 0.005,
    damping: Double = 0.8,
    private val maxCorrection: Double = 500e-6,
    private val target: Double = 0.5,
) {
    private val kp: Double
    private val ki: Double
    private var integral = 0.0

    init {
        val w = 2 * PI * bandwidthHz
        kp = 2 * damping * w * bufferSeconds
        ki = w * w * bufferSeconds
    }

    /** Takes the sink fill level (0..1) measured [dtSeconds] after the last call; returns the correction. */
    fun update(fillLevel: Double, dtSeconds: Double): Double {
        val error = fillLevel - target
        integral = (integral + error * dtSeconds).coerceIn(-maxCorrection / ki, maxCorrection / ki)
        return (kp * error + ki * integral).coerceIn(-maxCorrection, maxCorrection)
    }

    fun reset() {
        integral = 0.0
    }
}
