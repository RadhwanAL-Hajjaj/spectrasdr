// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

/**
 * FM broadcast multiplex (ITU-R BS.450 pilot-tone system) for two audio tones:
 * a·[(L+R)/2 + (L−R)/2·sin 2ωt] + pilot·sin ωt + rds·r(t)·sin(3ωt + φ), with ω the 19 kHz pilot.
 * A tone frequency of 0 Hz gives a silent channel. No pre-emphasis is applied.
 */
class MpxGenerator(
    sampleRate: Double,
    leftHz: Double = 1_000.0,
    rightHz: Double = 1_000.0,
    private val leftLevel: Float = 1f,
    private val rightLevel: Float = 1f,
    private val stereo: Boolean = true,
    private val rds: RdsBaseband? = null,
    private val pilotLevel: Float = 0.09f,
    private val rdsLevel: Float = 0.03f,
    rdsPhaseTurns: Double = 0.0,
) {
    private val left = Oscillator(leftHz, sampleRate)
    private val right = Oscillator(rightHz, sampleRate)
    private val pilot = Oscillator(19_000.0, sampleRate)
    private val rdsCos = SineTable.cos(rdsPhaseTurns)
    private val rdsSin = SineTable.sin(rdsPhaseTurns)

    /** Share of the full deviation left for the audio. */
    val audioLevel: Float = 1f - (if (stereo) pilotLevel else 0f) - (if (rds != null) 0.05f else 0f)

    /** Writes [count] multiplex samples (±1.0 = full deviation) to [out]. */
    fun generate(out: FloatArray, count: Int) {
        for (n in 0 until count) {
            val l = leftLevel * left.sine.toFloat()
            val r = rightLevel * right.sine.toFloat()
            val ps = pilot.sine.toFloat()
            val pc = pilot.cosine.toFloat()
            var v = audioLevel * 0.5f * (l + r)
            if (stereo) v += audioLevel * 0.5f * (l - r) * (2f * ps * pc) + pilotLevel * ps
            if (rds != null) {
                val s3 = 3f * ps - 4f * ps * ps * ps
                val c3 = 4f * pc * pc * pc - 3f * pc
                v += rdsLevel * rds.next() * (s3 * rdsCos + c3 * rdsSin)
            }
            out[n] = v
            left.advance()
            right.advance()
            pilot.advance()
        }
    }
}
