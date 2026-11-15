// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

class RdsCodeTest {
    @Test
    fun validBlocksGiveThePublishedSyndromes() {
        // x¹⁰-premultiplied syndromes of offset words A, B, C, D and C′ (IEC 62106 Annex B, as used by gr-rds)
        val expected = intArrayOf(383, 14, 303, 663, 748)
        val offsets = intArrayOf(RdsCode.OFFSET_A, RdsCode.OFFSET_B, RdsCode.OFFSET_C, RdsCode.OFFSET_D, RdsCode.OFFSET_C_PRIME)
        val rnd = Random(1)
        repeat(50) {
            val info = rnd.nextInt(0x10000)
            for (i in offsets.indices) {
                assertEquals(expected[i], RdsCode.remainder(RdsCode.encode(info, offsets[i]), 26))
            }
        }
    }

    @Test
    fun syndromeOfACleanBlockIsItsOffsetWord() {
        assertEquals(RdsCode.OFFSET_A, RdsCode.syndrome(RdsCode.encode(0x1234, RdsCode.OFFSET_A)))
        assertEquals(RdsCode.OFFSET_D, RdsCode.syndrome(RdsCode.encode(0xFFFF, RdsCode.OFFSET_D)))
        assertEquals(RdsCode.OFFSET_C_PRIME, RdsCode.syndrome(RdsCode.encode(0, RdsCode.OFFSET_C_PRIME)))
    }

    @Test
    fun everyBurstOfUpToFiveBitsHasItsOwnSyndrome() {
        var patterns = 0
        for (length in 1..RdsCode.MAX_BURST) {
            val inner = if (length <= 2) 1 else 1 shl (length - 2)
            for (middle in 0 until inner) {
                val pattern = if (length == 1) 1 else (1 shl (length - 1)) or (middle shl 1) or 1
                for (shift in 0..26 - length) {
                    val error = pattern shl shift
                    val syndrome = RdsCode.syndrome(error)
                    assertNotEquals(0, syndrome)
                    assertEquals(error, RdsCode.burstErrors[syndrome])
                    patterns++
                }
            }
        }
        assertEquals(367, patterns)
        assertEquals(367, RdsCode.burstErrors.count { it != 0 })
    }

    @Test
    fun aBurstErrorIsLocatedThroughItsSyndrome() {
        val block = RdsCode.encode(0xBEEF, RdsCode.OFFSET_B)
        val error = 0b10111 shl 9
        val syndrome = RdsCode.syndrome(block xor error) xor RdsCode.OFFSET_B
        assertEquals(error, RdsCode.burstErrors[syndrome])
    }
}
