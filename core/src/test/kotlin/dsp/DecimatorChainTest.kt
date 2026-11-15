// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecimatorChainTest {
    private val fs = 2_400_000.0

    @Test
    fun planStagesSplitsLargestFirst() {
        assertEquals(listOf(5, 2), DecimatorChain.planStages(10))
        assertEquals(listOf(5, 5, 2), DecimatorChain.planStages(50))
        assertEquals(listOf(8, 4), DecimatorChain.planStages(32))
        assertEquals(listOf(13), DecimatorChain.planStages(13))
        assertEquals(listOf(6, 2), DecimatorChain.planStages(12))
        assertEquals(emptyList<Int>(), DecimatorChain.planStages(1))
    }

    private fun levelThrough(chain: DecimatorChain, freqHz: Double): Double {
        val n = 240_000
        val input = Signals.complexTone(n, freqHz, fs)
        val out = FloatArray(2 * chain.maxOutput(n))
        val m = chain.process(input, n, out)
        return Signals.complexPowerDb(out, m / 2, m)
    }

    @Test
    fun wfmChainKeepsThePassbandAndRejectsAliases() {
        fun chain() = DecimatorChain.design(fs, 10, 100_000.0)
        assertEquals(240_000.0, chain().outputRate, 1e-9)
        assertEquals(0.0, levelThrough(chain(), 50_000.0), 0.5)
        assertEquals(0.0, levelThrough(chain(), -95_000.0), 0.5)
        assertTrue(levelThrough(chain(), 290_000.0) < -60.0)   // would alias to +50 kHz
        assertTrue(levelThrough(chain(), -500_000.0) < -60.0)
    }

    @Test
    fun narrowChannelChain() {
        fun chain() = DecimatorChain.design(fs, 50, 6_250.0)
        assertEquals(listOf(5, 5, 2), chain().stageFactors)
        assertEquals(48_000.0, chain().outputRate, 1e-9)
        assertEquals(0.0, levelThrough(chain(), 6_000.0), 0.5)
        assertTrue(levelThrough(chain(), 51_000.0) < -60.0)    // would alias to +3 kHz
    }

    @Test
    fun blockSplittingDoesNotChangeTheOutput() {
        val n = 50_000
        val input = FloatArray(2 * n) { ((it * 7919) % 1000) / 1000f - 0.5f }
        val whole = DecimatorChain.design(fs, 50, 10_000.0)
        val outWhole = FloatArray(2 * whole.maxOutput(n))
        val mWhole = whole.process(input, n, outWhole)

        val split = DecimatorChain.design(fs, 50, 10_000.0)
        val outSplit = FloatArray(2 * split.maxOutput(n))
        val tmp = FloatArray(2 * split.maxOutput(n))
        var mSplit = 0
        var offset = 0
        for (len in intArrayOf(1, 999, 17_000, 12_345, n - 1 - 999 - 17_000 - 12_345)) {
            val k = split.process(input.copyOfRange(2 * offset, 2 * (offset + len)), len, tmp)
            System.arraycopy(tmp, 0, outSplit, 2 * mSplit, 2 * k)
            mSplit += k
            offset += len
        }
        assertEquals(mWhole, mSplit)
        for (i in 0 until 2 * mWhole) assertEquals(outWhole[i], outSplit[i], 1e-5f)
    }
}
