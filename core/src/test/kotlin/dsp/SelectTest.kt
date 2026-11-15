// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class SelectTest {
    private fun check(values: FloatArray, n: Int = values.size) {
        val sorted = values.copyOf(n).also { it.sort() }
        for (k in 0 until n) {
            assertEquals("k = $k of $n", sorted[k], Select.kth(values.copyOf(), n, k), 0f)
        }
    }

    @Test
    fun matchesSortingOnRandomValues() {
        val rnd = Random(1)
        for (size in intArrayOf(1, 2, 3, 4, 7, 100, 513)) {
            check(FloatArray(size) { rnd.nextFloat() })
        }
        val large = FloatArray(20_000) { rnd.nextGaussian().toFloat() }
        val sorted = large.copyOf().also { it.sort() }
        assertEquals(sorted[4_000], Select.kth(large, large.size, 4_000), 0f)
    }

    @Test
    fun handlesSortedReversedAndRepeatedValues() {
        check(FloatArray(200) { it.toFloat() })
        check(FloatArray(200) { -it.toFloat() })
        check(FloatArray(200) { 3f })
        check(FloatArray(200) { (it % 4).toFloat() })
    }

    @Test
    fun looksOnlyAtTheFirstNValues() {
        val values = floatArrayOf(5f, 1f, 4f, -100f, -200f)
        assertEquals(4f, Select.kth(values, 3, 1), 0f)
        assertEquals(-100f, values[3], 0f)
        assertEquals(-200f, values[4], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnIndexOutsideTheRange() {
        Select.kth(floatArrayOf(1f, 2f), 2, 2)
    }
}
