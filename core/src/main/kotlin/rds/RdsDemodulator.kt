// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.demod.MultiplexTap
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChain
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Nco
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recovers the RDS bit stream from the FM multiplex (§6.4) and feeds it to [decoder].
 *
 * The 57 kHz subcarrier is mixed to 0 Hz with three times the pilot phase while the pilot is
 * locked (the standard locks the subcarrier to the pilot's third harmonic), otherwise with a
 * free-running oscillator. After decimation, a channel filter and the matched filter, a Gardner
 * loop times the biphase chips, a decision-directed Costas loop removes the remaining carrier
 * phase, chip pairs become bits and differential decoding undoes the transmitter's encoding.
 */
class RdsDemodulator(mpxRate: Double, private val decoder: RdsDecoder) : MultiplexTap {
    init {
        require(mpxRate >= 125_000.0) { "RDS needs at least 125 kHz of multiplex rate, got $mpxRate Hz" }
    }

    private val decimation = maxOf(1, floor(mpxRate / 19_000.0).toInt())
    private val rate = mpxRate / decimation
    private val chain = DecimatorChain.design(mpxRate, decimation, 2_400.0, 60.0)
    private val channelFilter = FirDecimator(FirDesign.lowPassKaiser(2_400.0, 4_000.0, rate, 60.0), 1)
    private val matchedFilter = FirDecimator(matchedTaps(rate), 1)
    private val freeRunning = Nco(mpxRate).apply { frequencyHz = -57_000.0 }
    private var baseband = FloatArray(0)
    private var work = FloatArray(0)

    // Chip timing (Gardner)
    private val chipStep = RdsCode.CHIP_RATE / rate
    private var clock = 0.0
    private var prevRe = 0f
    private var prevIm = 0f
    private var midRe = 0f
    private var midIm = 0f
    private var lastRe = 0f
    private var lastIm = 0f
    private var power = 0f

    // Carrier phase (Costas, one update per chip)
    private var phi = 0.0
    private var freq = 0.0

    // Biphase pairing and differential decoding
    private var prevChip = 0f
    private var parity = 0
    private var pairParity = 0
    private val pairEnergy = FloatArray(2)
    private var prevBit = 0

    /** Mean power of the chips, a rough signal-strength indication for diagnostics. */
    val chipPower: Float get() = power

    /**
     * Processes [count] multiplex samples. [pilotSin] and [pilotCos] hold sin θ and cos θ of the
     * pilot phase for each sample (pilot = cos θ); they are used only while [pilotLocked].
     */
    override fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int) {
        if (baseband.size < 2 * count) {
            baseband = FloatArray(2 * count)
            work = FloatArray(2 * count)
        }
        val bb = baseband
        if (pilotLocked) {
            for (n in 0 until count) {
                val s = pilotSin[n]
                val c = pilotCos[n]
                val x = mpx[n]
                bb[2 * n] = x * (4f * c * c * c - 3f * c)      // x·cos 3θ
                bb[2 * n + 1] = -x * (3f * s - 4f * s * s * s)  // −x·sin 3θ
            }
        } else {
            for (n in 0 until count) {
                bb[2 * n] = mpx[n]
                bb[2 * n + 1] = 0f
            }
            freeRunning.mix(bb, 0, count)
        }
        var m = chain.process(bb, count, work)
        m = channelFilter.process(work, m, bb)
        m = matchedFilter.process(bb, m, work)
        for (k in 0 until m) sample(work[2 * k], work[2 * k + 1])
    }

    /** Clears all loop state, for use after a retune. */
    override fun reset() {
        chain.reset()
        channelFilter.reset()
        matchedFilter.reset()
        clock = 0.0
        prevRe = 0f
        prevIm = 0f
        midRe = 0f
        midIm = 0f
        lastRe = 0f
        lastIm = 0f
        power = 0f
        phi = 0.0
        freq = 0.0
        prevChip = 0f
        parity = 0
        pairParity = 0
        pairEnergy.fill(0f)
        prevBit = 0
    }

    private fun sample(re: Float, im: Float) {
        val before = clock
        clock += chipStep
        if (before < 0.5 && clock >= 0.5) {
            val f = ((0.5 - before) / chipStep).toFloat()
            midRe = prevRe + f * (re - prevRe)
            midIm = prevIm + f * (im - prevIm)
        }
        if (clock >= 1.0) {
            val f = ((1.0 - before) / chipStep).toFloat()
            clock -= 1.0
            chip(prevRe + f * (re - prevRe), prevIm + f * (im - prevIm))
        }
        prevRe = re
        prevIm = im
    }

    private fun chip(re: Float, im: Float) {
        power += POWER_ALPHA * (re * re + im * im - power)
        val scale = power + 1e-20f

        // Gardner: positive error = sampling late, so the next chip instant is brought forward.
        val timing = ((re - lastRe) * midRe + (im - lastIm) * midIm) / scale
        clock += (TIMING_GAIN * timing).coerceIn(-0.1f, 0.1f)
        lastRe = re
        lastIm = im

        // Costas: rotate by −φ, steer φ so the data sits on the real axis.
        val c = cos(phi).toFloat()
        val s = sin(phi).toFloat()
        val dRe = re * c + im * s
        val dIm = im * c - re * s
        val error = (if (dRe >= 0) dIm else -dIm) / sqrt(scale)
        freq = (freq + CARRIER_KI * error).coerceIn(-MAX_CARRIER_STEP, MAX_CARRIER_STEP)
        phi += freq + CARRIER_KP * error
        if (phi > PI) phi -= 2 * PI else if (phi < -PI) phi += 2 * PI

        // Biphase: the two chips of a bit are always opposite, while chips on either side of a bit
        // boundary are opposite only when the next data bit is 0. The right pairing therefore
        // carries more energy, by a factor of 1/P(bit = 0); a 20 % margin over a 500-chip average
        // decides without flapping on runs of zeros.
        val diff = prevChip - dRe
        pairEnergy[parity] += ENERGY_ALPHA * (diff * diff - pairEnergy[parity])
        if (parity == pairParity) {
            val bit = if (diff > 0) 1 else 0
            decoder.push(bit xor prevBit)
            prevBit = bit
        } else if (pairEnergy[parity] > PAIRING_MARGIN * pairEnergy[pairParity]) {
            pairParity = parity
        }
        prevChip = dRe
        parity = parity xor 1
    }

    private companion object {
        const val POWER_ALPHA = 0.01f
        const val ENERGY_ALPHA = 0.002f
        const val PAIRING_MARGIN = 1.2f
        const val TIMING_GAIN = 0.03f
        // 20 Hz loop at the chip rate: ωn = 2π·20/2375, damping 0.707
        const val CARRIER_KP = 0.0748
        const val CARRIER_KI = 0.0028
        // The loop never needs to follow more than ±30 Hz (transmitter tolerance plus receiver clock error)
        const val MAX_CARRIER_STEP = 2 * PI * 30.0 / RdsCode.CHIP_RATE

        /** The chip pulse sampled at [rate], Hann-windowed over ±3 chips, with unit DC gain. */
        fun matchedTaps(rate: Double): FloatArray {
            val perChip = rate / RdsCode.CHIP_RATE
            val half = kotlin.math.ceil(3 * perChip).toInt()
            val taps = DoubleArray(2 * half + 1) { i ->
                val k = i - half
                RdsCode.pulse(k / perChip) * 0.5 * (1 + cos(PI * k / (half + 1)))
            }
            val sum = taps.sum()
            return FloatArray(taps.size) { (taps[it] / sum).toFloat() }
        }
    }
}
