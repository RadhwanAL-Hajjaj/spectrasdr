// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DriftControllerTest {
    @Test
    fun holdsTheFillLevelAgainstAClockOffset() {
        val bufferSeconds = 0.15
        val controller = DriftController(bufferSeconds = bufferSeconds)
        val sdrClockFast = 120e-6
        var fill = 0.5
        var correction = 0.0
        val dt = 0.05
        repeat((1_800 / dt).toInt()) {   // 30 simulated minutes
            fill += (sdrClockFast - correction) * dt / bufferSeconds
            correction = controller.update(fill, dt)
        }
        assertEquals(0.5, fill, 0.02)
        assertEquals(sdrClockFast, correction, 5e-6)
    }

    @Test
    fun neverExceedsTheMaximumCorrection() {
        val controller = DriftController(maxCorrection = 500e-6)
        repeat(1_000) { assertTrue(abs(controller.update(1.0, 0.05)) <= 500e-6) }
    }
}
