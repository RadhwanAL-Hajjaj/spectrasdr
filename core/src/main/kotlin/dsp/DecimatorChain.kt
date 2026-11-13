// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

/** Cascade of [FirDecimator] stages (§6.3); the total factor is the product of the stage factors. */
class DecimatorChain(private val stages: List<FirDecimator>, val inputRate: Double) {
    val factor: Int = stages.fold(1) { acc, s -> acc * s.factor }
    val outputRate: Double = inputRate / factor
    val stageFactors: List<Int> get() = stages.map { it.factor }
    private var scratchA = FloatArray(0)
    private var scratchB = FloatArray(0)

    /** Decimates [inCount] complex samples; [output] must hold [maxOutput] complex samples. */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        if (stages.isEmpty()) {
            System.arraycopy(input, 0, output, 0, 2 * inCount)
            return inCount
        }
        var src = input
        var count = inCount
        for (idx in stages.indices) {
            val stage = stages[idx]
            val dst = if (idx == stages.lastIndex) output else scratch(idx, stage.maxOutput(count))
            count = stage.process(src, count, dst)
            src = dst
        }
        return count
    }

    fun maxOutput(inCount: Int): Int {
        var c = inCount
        for (s in stages) c = s.maxOutput(c)
        return c
    }

    fun reset() = stages.forEach { it.reset() }

    private fun scratch(idx: Int, complex: Int): FloatArray {
        val need = 2 * complex
        return if (idx % 2 == 0) {
            if (scratchA.size < need) scratchA = FloatArray(need)
            scratchA
        } else {
            if (scratchB.size < need) scratchB = FloatArray(need)
            scratchB
        }
    }

    companion object {
        /** Splits [factor] into at most 3 stages, largest first; each stage ≤ 8 unless a prime factor is larger. */
        fun planStages(factor: Int): List<Int> {
            require(factor >= 1) { "factor must be >= 1" }
            val primes = mutableListOf<Int>()
            var rest = factor
            var p = 2
            while (rest > 1) {
                while (rest % p == 0) {
                    primes += p
                    rest /= p
                }
                p++
            }
            primes.sortDescending()
            val stages = mutableListOf<Int>()
            for (q in primes) {
                if (stages.isNotEmpty() && stages.last() * q <= 8) stages[stages.lastIndex] = stages.last() * q
                else stages += q
            }
            while (stages.size > 3) {
                val tail = stages.removeAt(stages.lastIndex)
                stages[stages.lastIndex] = stages.last() * tail
            }
            return stages.sortedDescending()
        }

        /**
         * Designs a chain that decimates by [factor], keeps ±[passbandHz] flat and rejects everything
         * that would alias into it by at least [attenuationDb].
         */
        fun design(inputRate: Double, factor: Int, passbandHz: Double, attenuationDb: Double = 66.0): DecimatorChain {
            val stages = mutableListOf<FirDecimator>()
            var rate = inputRate
            for (f in planStages(factor)) {
                val outRate = rate / f
                val stopband = outRate - passbandHz
                require(stopband > passbandHz) { "passband ±$passbandHz Hz is too wide for $outRate Hz" }
                stages += FirDecimator(FirDesign.lowPassKaiser(passbandHz, stopband, rate, attenuationDb), f)
                rate = outRate
            }
            return DecimatorChain(stages, inputRate)
        }
    }
}
