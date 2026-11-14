// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.demod.WfmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.MpxGenerator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsBaseband
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

/** Runs an FM broadcast signal with RDS through [WfmDemod] and an [RdsDemodulator]. */
object RdsTestSignals {
    const val FS = 240_000.0

    /**
     * Demodulates [seconds] of a station sending [encoder]'s groups. [cnrDb] adds white noise for
     * that carrier-to-noise ratio in the 240 kHz band; [rateError] is the relative error of the
     * transmitter's clocks against the receiver's (5e-5 = 50 ppm).
     */
    fun receive(
        encoder: RdsEncoder,
        seconds: Double,
        stereo: Boolean = true,
        rdsPhaseTurns: Double = 0.0,
        cnrDb: Double = Double.POSITIVE_INFINITY,
        rateError: Double = 0.0,
        seed: Long = 1,
    ): RdsDecoder {
        val txRate = FS * (1 + rateError)
        val mpx = MpxGenerator(txRate, 1_000.0, 400.0, stereo = stereo, rds = RdsBaseband(encoder.bits, txRate), rdsPhaseTurns = rdsPhaseTurns)
        val modulator = FmModulator(txRate, 75_000.0)
        val decoder = RdsDecoder()
        val demod = WfmDemod(FS)
        demod.tap = RdsDemodulator(FS, decoder)
        val sigma = if (cnrDb.isInfinite()) 0f else sqrt(10.0.pow(-cnrDb / 10) / 2).toFloat()
        val rnd = Random(seed)
        val block = 16_384
        val message = FloatArray(block)
        val iq = FloatArray(2 * block)
        val audio = FloatArray(2 * demod.maxOutput(block))
        var left = (seconds * FS).toInt()
        while (left > 0) {
            val n = minOf(block, left)
            mpx.generate(message, n)
            modulator.modulate(message, n, iq)
            if (sigma > 0f) for (i in 0 until 2 * n) iq[i] += sigma * rnd.nextGaussian().toFloat()
            demod.process(iq, n, audio)
            left -= n
        }
        return decoder
    }
}
