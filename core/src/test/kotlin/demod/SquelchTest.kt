// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquelchTest {
    @Test
    fun staysOpenUntilAThresholdIsSet() {
        val squelch = Squelch()
        assertTrue(squelch.update(-120.0))
        squelch.thresholdDb = -50.0
        assertFalse(squelch.update(-120.0))
    }

    @Test
    fun opensAtTheThresholdAndClosesThreeDecibelsBelow() {
        val squelch = Squelch()
        squelch.thresholdDb = -50.0
        assertFalse(squelch.update(-60.0))
        assertFalse(squelch.update(-50.1))
        assertTrue(squelch.update(-50.0))
        assertTrue(squelch.update(-52.9))
        assertFalse(squelch.update(-53.1))
        assertFalse(squelch.update(-51.0))
        assertEquals(-51.0, squelch.levelDb, 0.0)
    }

    @Test
    fun automaticThresholdSitsSixDecibelsAboveTheNoiseFloor() {
        val squelch = Squelch()
        squelch.auto = true
        assertTrue("open while the floor is unknown", squelch.update(-90.0))
        squelch.noiseFloorDb = -90.0
        assertEquals(-84.0, squelch.effectiveThresholdDb, 0.0)
        assertFalse(squelch.update(-89.0))
        assertFalse(squelch.update(-84.1))
        assertTrue(squelch.update(-84.0))
        assertTrue(squelch.update(-86.9))
        assertFalse(squelch.update(-87.1))
        squelch.thresholdDb = -100.0                 // ignored in automatic mode
        assertFalse(squelch.update(-89.0))
        squelch.auto = false
        assertTrue(squelch.update(-89.0))
    }

    @Test
    fun gateFadesInFiveMilliseconds() {
        val squelch = Squelch()
        squelch.thresholdDb = -50.0
        val rate = 48_000.0
        val audio = FloatArray(960) { 1f }
        squelch.update(-80.0)                            // closes
        squelch.gate(audio, 480, 2, rate)
        assertEquals(1f - 1f / 240, audio[0], 1e-4f)     // 5 ms = 240 frames
        assertEquals(audio[0], audio[1], 0f)
        assertEquals(0f, audio[2 * 240], 1e-4f)
        assertEquals(0f, audio[2 * 479], 0f)
        audio.fill(1f)
        squelch.gate(audio, 480, 2, rate)
        assertEquals("stays silent while closed", 0f, audio[100], 0f)
        audio.fill(1f)
        squelch.update(-40.0)                            // opens
        squelch.gate(audio, 480, 2, rate)
        assertEquals(1f / 240, audio[0], 1e-4f)
        assertEquals(1f, audio[2 * 240], 1e-4f)
    }
}
