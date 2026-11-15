// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import kotlin.math.abs

/**
 * Decides where the tuner sits for a wanted frequency. Moving the channel inside the stream costs
 * nothing (§7.1 step 4), so the tuner moves only when the channel would leave the usable part of
 * the stream or, for the narrow modes, touch its centre: there the DC removal cuts a notch and
 * most tuners leave a spike, which would take the carrier out of an AM signal. Broadcast FM is
 * 200 kHz wide and not harmed by either, and centring it leaves the most room at low rates.
 */
object TuningPolicy {
    /** Distance kept between the edge of a narrow channel and the centre of the stream, in Hz. */
    const val CENTER_GUARD_HZ = 1_000.0

    /** Share of the stream's bandwidth that is flat enough to listen in (§7). */
    fun usableFraction(kind: SourceKind): Double = if (kind == SourceKind.RSP_TCP) 0.85 else 0.75

    /** How far the channel reaches on either side of the tuned frequency, in Hz. */
    fun halfWidthHz(mode: Mode, bandwidthHz: Int, cwPitchHz: Double): Double = when (mode) {
        Mode.WFM, Mode.NFM, Mode.AM -> bandwidthHz / 2.0
        Mode.USB, Mode.LSB -> 3_200.0
        Mode.CW -> cwPitchHz + bandwidthHz
    }

    /** Whether a channel [offsetHz] from the centre of a stream of [sampleRate] is received cleanly. */
    fun fits(offsetHz: Double, sampleRate: Int, mode: Mode, halfWidthHz: Double, usableFraction: Double): Boolean {
        val distance = abs(offsetHz)
        if (distance + halfWidthHz > usableFraction * sampleRate / 2) return false
        return mode == Mode.WFM || distance - halfWidthHz >= CENTER_GUARD_HZ
    }

    /** Where the tuner goes when it has to move: on the station for broadcast FM, a quarter of the rate below it otherwise. */
    fun centerFor(frequencyHz: Long, sampleRate: Int, mode: Mode): Long =
        if (mode == Mode.WFM) frequencyHz else maxOf(0L, frequencyHz - sampleRate / 4)

    /**
     * The tuner frequency to use for [frequencyHz]: [currentCenterHz] while the channel is received
     * cleanly there, otherwise [centerFor]. A null [currentCenterHz] means the tuner was never set.
     */
    fun plan(
        frequencyHz: Long,
        currentCenterHz: Long?,
        sampleRate: Int,
        mode: Mode,
        bandwidthHz: Int,
        cwPitchHz: Double,
        usableFraction: Double,
    ): Long {
        if (currentCenterHz != null) {
            val half = halfWidthHz(mode, bandwidthHz, cwPitchHz)
            if (fits((frequencyHz - currentCenterHz).toDouble(), sampleRate, mode, half, usableFraction)) return currentCenterHz
        }
        return centerFor(frequencyHz, sampleRate, mode)
    }

    /**
     * The offset nearest to [offsetHz] that keeps the whole channel inside a stream of [sampleRate]:
     * what a source that cannot tune (a recording) is limited to.
     */
    fun clampOffset(offsetHz: Double, sampleRate: Int, halfWidthHz: Double): Double {
        val limit = maxOf(0.0, sampleRate / 2.0 - halfWidthHz)
        return offsetHz.coerceIn(-limit, limit)
    }
}
