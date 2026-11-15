// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import kotlin.math.PI
import kotlin.math.floor

/** Sine lookup with linear interpolation. Phases are in turns: 1.0 is a full circle. */
object SineTable {
    private const val SIZE = 4096
    private val table = FloatArray(SIZE + 1) { kotlin.math.sin(2 * PI * it / SIZE).toFloat() }

    fun sin(turns: Double): Float {
        val x = (turns - floor(turns)) * SIZE
        val i = minOf(x.toInt(), SIZE - 1)   // x is SIZE when a tiny negative phase rounds up to a full turn
        return table[i] + (table[i + 1] - table[i]) * (x - i).toFloat()
    }

    fun cos(turns: Double): Float = sin(turns + 0.25)
}
