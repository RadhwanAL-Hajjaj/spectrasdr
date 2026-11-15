// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot

class NcoTest {
    private fun dc(n: Int) = FloatArray(2 * n) { if (it % 2 == 0) 1f else 0f }

    @Test
    fun shiftsDcToTheRequestedBin() {
        val fs = 48_000.0
        val n = 4096
        val data = dc(n)
        Nco(fs).apply { frequencyHz = fs * 64 / n }.mix(data, 0, n)
        Fft(n).transform(data)
        assertEquals(n.toDouble(), hypot(data[128].toDouble(), data[129].toDouble()), n * 1e-4)
    }

    @Test
    fun amplitudeStaysAtOneOverMillionsOfSamples() {
        val nco = Nco(2_400_000.0).apply { frequencyHz = 123_456.7 }
        val block = 16_384
        val data = FloatArray(2 * block)
        repeat(200) {
            for (i in 0 until block) { data[2 * i] = 1f; data[2 * i + 1] = 0f }
            nco.mix(data, 0, block)
        }
        assertEquals(1.0, hypot(data[2 * block - 2].toDouble(), data[2 * block - 1].toDouble()), 1e-6)
    }

    @Test
    fun negativeFrequencyUndoesPositive() {
        val fs = 1_000_000.0
        val n = 1000
        val data = FloatArray(2 * n) { if (it % 2 == 0) 0.5f else -0.25f }
        val original = data.copyOf()
        Nco(fs).apply { frequencyHz = 70_000.0 }.mix(data, 0, n)
        Nco(fs).apply { frequencyHz = -70_000.0 }.mix(data, 0, n)
        for (i in data.indices) assertEquals(original[i], data[i], 1e-5f)
    }

    @Test
    fun phaseIsContinuousAcrossCalls() {
        val fs = 48_000.0
        val whole = dc(100)
        val split = dc(100)
        Nco(fs).apply { frequencyHz = 1_000.0 }.mix(whole, 0, 100)
        val nco = Nco(fs).apply { frequencyHz = 1_000.0 }
        nco.mix(split, 0, 37)
        nco.mix(split, 37, 63)
        for (i in whole.indices) assertEquals(whole[i], split[i], 1e-6f)
    }
}
