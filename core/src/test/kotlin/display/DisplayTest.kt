// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.display

import io.github.radhwanalhajjaj.spectrasdr.core.session.DriverUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTest {
    @Test
    fun frequenciesAreShownInMegahertz() {
        assertEquals("100.300 000 MHz", FrequencyText.megahertz(100_300_000))
        assertEquals("0.531 000 MHz", FrequencyText.megahertz(531_000))
        assertEquals("1296.000 005 MHz", FrequencyText.megahertz(1_296_000_005))
        assertEquals("100.3 MHz", FrequencyText.short(100_300_000))
        assertEquals("145.5125 MHz", FrequencyText.short(145_512_500))
        assertEquals("100.0 MHz", FrequencyText.short(100_000_000))
        assertArrayEquals(intArrayOf(0, 1, 0, 0, 3, 0, 0, 0, 0, 0), FrequencyText.digits(100_300_000))
    }

    @Test
    fun typedFrequenciesAreRead() {
        assertEquals(100_300_000L, FrequencyText.parseMegahertz("100.3"))
        assertEquals(145_512_500L, FrequencyText.parseMegahertz(" 145,5125 "))
        assertEquals(7_100_000L, FrequencyText.parseMegahertz("7.1"))
        assertEquals(433_920_000L, FrequencyText.parseMegahertz("433.92"))
        assertEquals(1L, FrequencyText.parseMegahertz("0.0000014"))
        assertNull(FrequencyText.parseMegahertz(""))
        assertNull(FrequencyText.parseMegahertz("abc"))
        assertNull(FrequencyText.parseMegahertz("1.2.3"))
        assertNull(FrequencyText.parseMegahertz("-5"))
        assertNull(FrequencyText.parseMegahertz("10000"))
        assertNull(FrequencyText.parseMegahertz("99999999999999999999999"))
    }

    @Test
    fun aDigitTurnsWithCarryAndStopsAtTheLimits() {
        assertEquals(100_400_000L, FrequencyText.turnDigit(100_300_000, power = 5, steps = 1))
        assertEquals(101_000_000L, FrequencyText.turnDigit(100_900_000, power = 5, steps = 1))
        assertEquals(99_900_000L, FrequencyText.turnDigit(100_000_000, power = 5, steps = -1))
        assertEquals(0L, FrequencyText.turnDigit(300_000, power = 6, steps = -1))
        assertEquals(108_000_000L, FrequencyText.turnDigit(107_500_000, power = 6, steps = 1, min = 87_500_000, max = 108_000_000))
        assertEquals(100_312_500L, FrequencyText.snap(100_310_000, 12_500))
        assertEquals(100_300_000L, FrequencyText.snap(100_306_000, 12_500))
    }

    @Test
    fun theSpectrumScaleMapsFrequenciesAndLevels() {
        val s = SpectrumScale(centerHz = 100_000_000, sampleRate = 2_400_000, topDb = 0f, bottomDb = -120f)
        assertEquals(0.5f, s.xOf(100_000_000.0), 1e-6f)
        assertEquals(0f, s.xOf(98_800_000.0), 1e-6f)
        assertEquals(0.75f, s.xOf(100_600_000.0), 1e-6f)
        assertEquals(100_600_000L, s.hzAt(0.75f))
        assertEquals(0.5f, s.yOf(-60f), 1e-6f)
        assertEquals(1f, s.yOf(-200f), 0f)
        assertEquals(0f, s.yOf(10f), 0f)
        assertEquals(0.5f, s.xOfBin(2_048, 4_096), 2e-4f)
    }

    @Test
    fun columnsKeepTheStrongestBin() {
        val db = FloatArray(8) { -100f }
        db[5] = -20f
        val out = FloatArray(4)
        SpectrumScale.columns(db, 8, out)
        assertArrayEquals(floatArrayOf(-100f, -100f, -20f, -100f), out, 0f)
        val same = FloatArray(8)
        SpectrumScale.columns(db, 8, same)
        assertArrayEquals(db, same, 0f)
    }

    @Test
    fun theDriverAddressFollowsTheIqsrcConvention() {
        assertEquals(
            "iqsrc://-a 127.0.0.1 -p 14423 -s 2400000 -f 100300000 -P 0",
            DriverUri.build(port = 14423, sampleRate = 2_400_000, frequencyHz = 100_300_000),
        )
    }
}
