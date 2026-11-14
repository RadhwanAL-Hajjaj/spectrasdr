// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode
import kotlin.math.abs

/**
 * Builds the repeating RDS bit stream of a station: 0A groups carrying the programme service name
 * [ps], interleaved with 2A groups carrying the radio text [rt]. Used by the simulator and by tests.
 */
class RdsEncoder(
    val pi: Int,
    ps: String,
    rt: String = "",
    val pty: Int = 0,
    val tp: Boolean = false,
    val ta: Boolean = false,
) {
    /** One full cycle of groups as bits (0 or 1), most significant bit of each block first. */
    val bits: ByteArray

    init {
        val name = ps.padEnd(8).take(8)
        val text = radioText(rt)
        val segments = text.length / 4
        val groups = ArrayList<IntArray>()
        for (i in 0 until maxOf(4, segments)) {
            val s = i % 4
            groups += group0A(pi, pty, tp, ta, s, name[2 * s].code, name[2 * s + 1].code)
            if (i < segments) {
                groups += group2A(pi, pty, tp, false, i, text[4 * i].code, text[4 * i + 1].code, text[4 * i + 2].code, text[4 * i + 3].code)
            }
        }
        bits = toBits(groups)
    }

    companion object {
        private fun bit(b: Boolean) = if (b) 1 else 0

        /** Radio text as transmitted: at most 64 characters, ended by a carriage return when shorter. */
        fun radioText(rt: String): String {
            if (rt.isEmpty()) return ""
            val t = rt.take(64)
            if (t.length == 64) return t
            val ended = t + "\r"
            return ended.padEnd((ended.length + 3) / 4 * 4)
        }

        /** Group 0A: two characters of the programme service name at [segment] (0–3). */
        fun group0A(pi: Int, pty: Int, tp: Boolean, ta: Boolean, segment: Int, c1: Int, c2: Int): IntArray {
            val di = if (segment == 3) 1 else 0   // decoder identification: stereo
            val b = (bit(tp) shl 10) or (pty shl 5) or (bit(ta) shl 4) or (1 shl 3) or (di shl 2) or segment
            return intArrayOf(pi, b, 0xCDCD, (c1 shl 8) or c2)
        }

        /** Group 2A: four radio text characters at [segment] (0–15). */
        fun group2A(pi: Int, pty: Int, tp: Boolean, abFlag: Boolean, segment: Int, c1: Int, c2: Int, c3: Int, c4: Int): IntArray {
            val b = (2 shl 12) or (bit(tp) shl 10) or (pty shl 5) or (bit(abFlag) shl 4) or segment
            return intArrayOf(pi, b, (c1 shl 8) or c2, (c3 shl 8) or c4)
        }

        /** Group 4A: clock time as modified Julian day, UTC hour and minute, and the local offset in half hours. */
        fun group4A(pi: Int, pty: Int, tp: Boolean, mjd: Int, hour: Int, minute: Int, offsetHalfHours: Int): IntArray {
            val b = (4 shl 12) or (bit(tp) shl 10) or (pty shl 5) or ((mjd ushr 15) and 3)
            val c = ((mjd and 0x7FFF) shl 1) or ((hour ushr 4) and 1)
            val sign = if (offsetHalfHours < 0) 1 else 0
            val d = ((hour and 0xF) shl 12) or (minute shl 6) or (sign shl 5) or (abs(offsetHalfHours) and 0x1F)
            return intArrayOf(pi, b, c, d)
        }

        /** The four 26-bit blocks of a group; version B groups (bit 11 of block B) use offset C′. */
        fun blocks(group: IntArray): IntArray {
            val versionB = (group[1] ushr 11) and 1 == 1
            return intArrayOf(
                RdsCode.encode(group[0], RdsCode.OFFSET_A),
                RdsCode.encode(group[1], RdsCode.OFFSET_B),
                RdsCode.encode(group[2], if (versionB) RdsCode.OFFSET_C_PRIME else RdsCode.OFFSET_C),
                RdsCode.encode(group[3], RdsCode.OFFSET_D),
            )
        }

        /** Groups as a bit stream, most significant bit of each block first. */
        fun toBits(groups: List<IntArray>): ByteArray {
            val out = ByteArray(groups.size * 104)
            var o = 0
            for (g in groups) {
                for (block in blocks(g)) {
                    for (i in 25 downTo 0) out[o++] = ((block ushr i) and 1).toByte()
                }
            }
            return out
        }
    }
}
