// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

/**
 * Squelch on channel power (§6.4). The gate opens when the level reaches the threshold and closes
 * again [hysteresisDb] below it. In automatic mode the threshold is the noise floor plus 6 dB; the
 * engine supplies the floor ([noiseFloorDb]) from the spectrum around the channel, so a station
 * that never stops transmitting does not raise it. Audio is gated with 5 ms ramps. The same state
 * drives the scanner. Used from the DSP thread only.
 */
class Squelch(private val hysteresisDb: Double = 3.0) {
    /** Manual threshold in dBFS. Negative infinity (the default) keeps the squelch open. */
    var thresholdDb = Double.NEGATIVE_INFINITY

    /** When true the threshold follows [noiseFloorDb] and [thresholdDb] is ignored. */
    var auto = false

    /** Noise power expected in the empty channel, in dBFS; negative infinity while unknown. */
    var noiseFloorDb = Double.NEGATIVE_INFINITY

    /** True while the gate is open. */
    var open = true
        private set

    /** The last channel power given to [update], in dBFS. */
    var levelDb = Double.NEGATIVE_INFINITY
        private set

    private var gain = 1f

    /** The threshold in force right now, in dBFS. */
    val effectiveThresholdDb: Double
        get() = if (auto) noiseFloorDb + AUTO_MARGIN_DB else thresholdDb

    /** Takes the channel power of the newest block; returns whether the gate is open. */
    fun update(powerDb: Double): Boolean {
        levelDb = powerDb
        val threshold = effectiveThresholdDb
        if (open) {
            if (powerDb < threshold - hysteresisDb) open = false
        } else if (powerDb >= threshold) {
            open = true
        }
        return open
    }

    /** Fades [frames] interleaved frames of [audio] toward the gate state. */
    fun gate(audio: FloatArray, frames: Int, channels: Int, sampleRate: Double) {
        val target = if (open) 1f else 0f
        if (gain == target) {
            if (target == 0f) audio.fill(0f, 0, frames * channels)
            return
        }
        val step = (1.0 / (RAMP_SECONDS * sampleRate)).toFloat()
        var g = gain
        var i = 0
        for (f in 0 until frames) {
            if (g < target) g = minOf(target, g + step) else if (g > target) g = maxOf(target, g - step)
            for (c in 0 until channels) audio[i++] *= g
        }
        gain = g
    }

    private companion object {
        const val AUTO_MARGIN_DB = 6.0
        const val RAMP_SECONDS = 0.005
    }
}
