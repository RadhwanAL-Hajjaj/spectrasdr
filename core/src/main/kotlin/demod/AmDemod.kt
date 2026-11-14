// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Agc
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlocker
import kotlin.math.sqrt

/** AM demodulator (§6.4): envelope detector, DC block and AGC. */
class AmDemod(override val inputRate: Double, agc: Boolean = true) : Demodulator {
    override val outputRate: Double = inputRate
    override val channels: Int = 1

    private val dcBlocker = DcBlocker(1, 30.0, inputRate)
    private val agc = if (agc) Agc(inputRate) else null

    override fun maxOutput(inCount: Int): Int = inCount

    override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        for (n in 0 until inCount) {
            val re = input[2 * n]
            val im = input[2 * n + 1]
            output[n] = sqrt(re * re + im * im)
        }
        dcBlocker.process(output, inCount)
        agc?.process(output, inCount)
        return inCount
    }

    override fun reset() {
        dcBlocker.reset()
        agc?.reset()
    }
}
