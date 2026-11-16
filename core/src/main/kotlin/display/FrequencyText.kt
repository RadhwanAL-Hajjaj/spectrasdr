// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.display

import java.math.BigDecimal
import java.util.Locale

/** Frequencies as people read and type them. */
object FrequencyText {
    /** The highest frequency the readout can show: 9,999.999 999 MHz. */
    const val MAX_HZ = 9_999_999_999L

    /** Ten digits, most significant first, for a readout like 0100.300 000 (in MHz). */
    fun digits(hz: Long): IntArray {
        var v = hz.coerceIn(0, MAX_HZ)
        val d = IntArray(10)
        for (i in 9 downTo 0) {
            d[i] = (v % 10).toInt()
            v /= 10
        }
        return d
    }

    /** "100.300 000 MHz": megahertz with the kilohertz and hertz grouped. */
    fun megahertz(hz: Long): String {
        val v = hz.coerceIn(0, MAX_HZ)
        val mhz = v / 1_000_000
        val khz = v / 1_000 % 1_000
        val rest = v % 1_000
        return String.format(Locale.ROOT, "%d.%03d %03d MHz", mhz, khz, rest)
    }

    /** [value] with [decimals] places, always with Western digits and a point, whatever the device's language. */
    fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    /** A short form for labels and the notification: "100.3 MHz", "145.5125 MHz", "7.1 MHz". */
    fun short(hz: Long): String {
        val text = BigDecimal(hz.coerceAtLeast(0)).movePointLeft(6).stripTrailingZeros().toPlainString()
        return (if ('.' in text) text else "$text.0") + " MHz"
    }

    /**
     * Reads what a user typed as megahertz ("100.3", "145,5125", " 7.1 "); null when it is not a
     * number or outside the readout's range.
     */
    fun parseMegahertz(text: String): Long? {
        val cleaned = text.trim().replace(',', '.').replace(" ", "")
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it == '.' } || cleaned.count { it == '.' } > 1) return null
        val hz = runCatching { BigDecimal(cleaned).movePointRight(6).setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger() }.getOrNull() ?: return null
        return if (hz.bitLength() <= 62 && hz.toLong() in 0..MAX_HZ) hz.toLong() else null
    }

    /**
     * The frequency after turning the digit worth 10^[power] Hz by [steps] (negative turns down),
     * kept inside [min]..[max].
     */
    fun turnDigit(hz: Long, power: Int, steps: Int, min: Long = 0, max: Long = MAX_HZ): Long {
        require(power in 0..9) { "power must be 0–9" }
        var unit = 1L
        repeat(power) { unit *= 10 }
        return (hz + steps * unit).coerceIn(min, max)
    }

    /** [hz] moved to the nearest multiple of [stepHz]. */
    fun snap(hz: Long, stepHz: Int): Long {
        require(stepHz > 0) { "step must be positive" }
        return (hz + stepHz / 2) / stepHz * stepHz
    }
}
