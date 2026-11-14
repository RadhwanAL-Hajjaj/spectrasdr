// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class GeneratorTest {
    @Test
    fun oscillatorStaysOnTheUnitCircleAndOnFrequency() {
        val osc = Oscillator(1_000.0, 48_000.0)
        var worst = 0.0
        for (n in 0 until 480_000) {
            worst = maxOf(worst, abs(osc.sine - sin(2 * PI * 1_000.0 * n / 48_000.0)))
            assertEquals(1.0, hypot(osc.sine, osc.cosine), 1e-9)
            osc.advance()
        }
        assertTrue("worst error $worst", worst < 1e-6)
    }

    @Test
    fun sineTableIsAccurate() {
        var worst = 0.0
        for (i in 0..10_000) {
            val turns = i / 1_000.0 - 3.0
            worst = maxOf(worst, abs(SineTable.sin(turns) - sin(2 * PI * turns)))
            worst = maxOf(worst, abs(SineTable.cos(turns) - kotlin.math.cos(2 * PI * turns)))
        }
        assertTrue("worst error $worst", worst < 1e-5)
    }

    @Test
    fun fmModulatorPutsAConstantMessageAtItsDeviation() {
        val fs = 240_000.0
        val n = 24_000
        val message = FloatArray(n) { 0.5f }
        val iq = FloatArray(2 * n)
        FmModulator(fs, 75_000.0).modulate(message, n, iq)
        assertEquals(0.0, Signals.complexToneDb(iq, 37_500.0, fs), 0.01)
        FmModulator(fs, 75_000.0, carrierHz = -20_000.0).modulate(message, n, iq, amplitude = 0.5f, add = true)
        assertEquals(-6.02, Signals.complexToneDb(iq, 17_500.0, fs), 0.05)
    }

    @Test
    fun rdsPulseHasTheStandardShape() {
        assertEquals(1.0, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.0), 1e-12)
        assertEquals(PI / 4, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.25), 1e-6)
        assertEquals(1.0 / 3, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.5), 1e-12)
        assertEquals(0.0, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.75), 1e-12)
        assertEquals(0.0, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(1.25), 1e-12)
    }

    @Test
    fun rdsBasebandStaysBelowTwoPointFourKilohertz() {
        val fs = 19_000.0   // 8 samples per chip
        val bits = ByteArray(1_040) { ((it * 7 + it / 3) % 2).toByte() }
        val gen = RdsBaseband(bits, fs)
        val n = 19_000
        val x = FloatArray(n) { gen.next() }
        var inBand = 0.0
        var outOfBand = 0.0
        for (f in 100..9_400 step 100) {
            val a = Signals.toneAmplitude(x, f.toDouble(), fs, 0, n)
            if (f <= 2_400) inBand += a * a else outOfBand += a * a
        }
        assertTrue("in-band $inBand out-of-band $outOfBand", outOfBand < inBand * 1e-2)
        assertTrue(Signals.peak(x) in 1.0f..1.6f)
    }

    @Test
    fun encoderProducesWholeGroupsWithValidChecks() {
        val enc = RdsEncoder(0x1234, "TEST FM", "Hello")
        // 4 PS groups + 2 text groups ("Hello" + carriage return + padding = 8 characters)
        assertEquals(6 * 104, enc.bits.size)
        assertEquals("Hello\r  ", RdsEncoder.radioText("Hello"))
        var word = 0
        for (i in 0 until 26) word = (word shl 1) or enc.bits[i].toInt()
        assertEquals(0x1234, word ushr 10)
        assertEquals(io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.OFFSET_A, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.syndrome(word))
    }

    @Test
    fun multiplexHasPilotAndSubcarrierWhereTheStandardPutsThem() {
        val fs = 240_000.0
        val n = 240_000
        val gen = MpxGenerator(fs, leftHz = 1_000.0, rightHz = 0.0)
        val mpx = FloatArray(n)
        gen.generate(mpx, n)
        assertEquals(0.09, Signals.toneAmplitude(mpx, 19_000.0, fs, 0, n), 1e-3)
        // Left only: M carries L/2 and the 38 kHz double sideband carries L/2 as two lines of L/4
        assertEquals(0.91 / 2, Signals.toneAmplitude(mpx, 1_000.0, fs, 0, n), 1e-3)
        assertEquals(0.91 / 4, Signals.toneAmplitude(mpx, 37_000.0, fs, 0, n), 1e-3)
        assertEquals(0.91 / 4, Signals.toneAmplitude(mpx, 39_000.0, fs, 0, n), 1e-3)
        assertTrue(Signals.peak(mpx) <= 1.0f)
    }
}
