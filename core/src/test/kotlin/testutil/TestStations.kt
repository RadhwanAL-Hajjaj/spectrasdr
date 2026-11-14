// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.MpxGenerator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsBaseband
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder

/** Wide-band test signals for the engine tests. */
object TestStations {
    /**
     * [seconds] of a stereo FM broadcast station [offsetHz] from the centre of a stream sampled at
     * [rate]: a tone of [leftHz] on the left, [rightHz] on the right (0 = silent), optional RDS.
     */
    fun fmBroadcast(
        rate: Double,
        offsetHz: Double,
        seconds: Double,
        leftHz: Double = 1_000.0,
        rightHz: Double = 0.0,
        rds: RdsEncoder? = null,
        amplitude: Float = 0.5f,
    ): FloatArray {
        val n = (seconds * rate).toInt()
        val mpx = MpxGenerator(rate, leftHz, rightHz, rds = rds?.let { RdsBaseband(it.bits, rate) })
        val modulator = FmModulator(rate, 75_000.0, offsetHz)
        val message = FloatArray(16_384)
        val block = FloatArray(2 * 16_384)
        val iq = FloatArray(2 * n)
        var done = 0
        while (done < n) {
            val m = minOf(16_384, n - done)
            mpx.generate(message, m)
            modulator.modulate(message, m, block, amplitude)
            System.arraycopy(block, 0, iq, 2 * done, 2 * m)
            done += m
        }
        return iq
    }
}
