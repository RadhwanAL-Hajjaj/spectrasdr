// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import org.junit.Assert.assertEquals
import org.junit.Test

class SineTableTest {
    @Test
    fun ordinaryPhases() {
        assertEquals(0f, SineTable.sin(0.0), 1e-3f)
        assertEquals(1f, SineTable.sin(0.25), 1e-3f)
        assertEquals(0f, SineTable.sin(0.5), 1e-3f)
        assertEquals(-1f, SineTable.sin(0.75), 1e-3f)
        assertEquals(1f, SineTable.cos(0.0), 1e-3f)
    }

    @Test
    fun aTinyNegativePhaseStaysInsideTheTable() {
        // turns − floor(turns) rounds to 1.0 for a phase in (−2⁻⁵⁴, 0)
        assertEquals(0f, SineTable.sin(-1e-17), 1e-3f)
    }
}
