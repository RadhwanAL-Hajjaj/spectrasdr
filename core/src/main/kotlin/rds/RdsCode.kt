// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

/**
 * The RDS block code (IEC 62106 Annex B): a (26,16) shortened cyclic code with generator
 * g(x) = x¹⁰ + x⁸ + x⁷ + x⁵ + x⁴ + x³ + 1. Every block is a 16-bit information word followed by a
 * 10-bit checkword to which an offset word (A, B, C, C′ or D) has been added.
 */
object RdsCode {
    /** Data rate in bit/s: the 57 kHz subcarrier divided by 48. */
    const val BIT_RATE = 1_187.5

    /** Biphase chips per second: two per bit. */
    const val CHIP_RATE = 2_375.0

    const val POLY = 0x5B9
    const val OFFSET_A = 0x0FC
    const val OFFSET_B = 0x198
    const val OFFSET_C = 0x168
    const val OFFSET_C_PRIME = 0x350
    const val OFFSET_D = 0x1B4

    /** Longest error burst the code corrects. */
    const val MAX_BURST = 5

    /**
     * The shaped chip pulse at [u] chip periods from its centre: the impulse response of the
     * standard's cosine-shaped data filter, p(u) = cos(2πu) / (1 − 16u²).
     */
    fun pulse(u: Double): Double {
        val d = 1 - 16 * u * u
        return if (kotlin.math.abs(d) < 1e-9) kotlin.math.PI / 4 else kotlin.math.cos(2 * kotlin.math.PI * u) / d
    }

    /** Remainder of word(x)·x¹⁰ divided by g(x); [bits] is the length of [word]. */
    fun remainder(word: Int, bits: Int): Int {
        var reg = 0
        for (i in bits - 1 downTo 0) {
            reg = (reg shl 1) or ((word ushr i) and 1)
            if (reg and 0x400 != 0) reg = reg xor POLY
        }
        repeat(10) {
            reg = reg shl 1
            if (reg and 0x400 != 0) reg = reg xor POLY
        }
        return reg and 0x3FF
    }

    /** Checkword of a 16-bit information word, before the offset word is added. */
    fun crc(info: Int): Int = remainder(info and 0xFFFF, 16)

    /** The 26-bit block for [info] with [offset]. */
    fun encode(info: Int, offset: Int): Int = ((info and 0xFFFF) shl 10) or (crc(info) xor offset)

    /** Syndrome of a 26-bit block: the block's offset word when it was received without errors. */
    fun syndrome(block: Int): Int = crc(block ushr 10) xor (block and 0x3FF)

    /**
     * For every syndrome (with the offset word already removed) of an error burst of at most
     * [MAX_BURST] bits: the 26-bit error pattern to flip. 0 marks a syndrome that is not correctable.
     */
    val burstErrors: IntArray = IntArray(1024).also { table ->
        for (length in 1..MAX_BURST) {
            // A burst of `length` bits has its first and last bit set; the bits between are free.
            val inner = if (length <= 2) 1 else 1 shl (length - 2)
            for (middle in 0 until inner) {
                val pattern = if (length == 1) 1 else (1 shl (length - 1)) or (middle shl 1) or 1
                for (shift in 0..26 - length) {
                    val error = pattern shl shift
                    table[syndrome(error)] = error
                }
            }
        }
    }
}
