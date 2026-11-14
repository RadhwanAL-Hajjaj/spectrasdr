// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

/** Clock time from group 4A: modified Julian day, UTC hour and minute, local offset in half hours. */
data class RdsClock(val mjd: Int, val hour: Int, val minute: Int, val offsetHalfHours: Int)

/**
 * What has been decoded from a station so far. Text holds confirmed characters only; positions
 * not yet confirmed are spaces. [pi] is −1 until a programme identification has been confirmed.
 */
data class RdsState(
    val pi: Int = -1,
    val pty: Int = 0,
    val tp: Boolean = false,
    val ta: Boolean = false,
    val ps: String = "",
    val rt: String = "",
    val clock: RdsClock? = null,
)

/**
 * RDS block synchronisation, error correction and group decoding (§6.4). Feed it the data bits
 * (after differential decoding) one at a time; [state] is replaced whenever something changes.
 *
 * Bursts of up to 5 bit errors per block are corrected, but only while block sync is held, and a
 * value from a corrected block is shown only once it has been received twice in a row; a value
 * from an error-free block is shown at once. Sync is dropped after 50 blocks without an error-free
 * one, because corrected blocks alone prove nothing: a third of all random words look correctable.
 */
class RdsDecoder {
    /** The latest decoded state; an immutable snapshot that is safe to read from any thread. */
    @Volatile
    var state = RdsState()
        private set

    /** True while block synchronisation is held. */
    @Volatile
    var synced = false
        private set

    /** Blocks received without errors, corrected, and failed, since the last [reset]. */
    var blocksClean = 0L
        private set
    var blocksCorrected = 0L
        private set
    var blocksFailed = 0L
        private set

    private var reg = 0
    private var bitsInReg = 0
    private var bitCount = 0
    private var blockIndex = 0
    private var sinceClean = 0
    private var candidateIndex = -1
    private var candidateAge = 0

    private val word = IntArray(4)
    private val valid = BooleanArray(4)
    private val clean = BooleanArray(4)

    private var pi = -1
    private var piCandidate = -1
    private var flags = 0            // tp (bit 5) and pty (bits 4–0), as last confirmed
    private var flagsCandidate = -1
    private var ta = false
    private var taCandidate = -1
    private val ps = IntArray(8) { -1 }
    private val psCandidate = IntArray(8) { -1 }
    private val rt = IntArray(64) { -1 }
    private val rtCandidate = IntArray(64) { -1 }
    private var rtFlag = -1
    private var clock: RdsClock? = null

    /** Takes the next data bit (only bit 0 of [bit] is used). */
    fun push(bit: Int) {
        reg = ((reg shl 1) or (bit and 1)) and 0x3FFFFFF
        if (!synced) {
            search()
            return
        }
        if (++bitCount < 26) return
        bitCount = 0
        receiveBlock()
    }

    /** Forgets everything, for use after a retune. */
    fun reset() {
        synced = false
        reg = 0
        bitsInReg = 0
        bitCount = 0
        candidateIndex = -1
        blocksClean = 0
        blocksCorrected = 0
        blocksFailed = 0
        pi = -1
        piCandidate = -1
        clearProgramme()
        state = RdsState()
    }

    private fun search() {
        if (bitsInReg < 26) bitsInReg++
        if (candidateIndex >= 0) candidateAge++
        if (bitsInReg < 26) return
        val index = when (RdsCode.syndrome(reg)) {
            RdsCode.OFFSET_A -> 0
            RdsCode.OFFSET_B -> 1
            RdsCode.OFFSET_C, RdsCode.OFFSET_C_PRIME -> 2
            RdsCode.OFFSET_D -> 3
            else -> -1
        }
        if (index < 0) {
            if (candidateAge > 26 * MAX_SEARCH_BLOCKS) candidateIndex = -1
            return
        }
        if (candidateIndex >= 0 && candidateAge % 26 == 0 && (candidateIndex + candidateAge / 26) % 4 == index) {
            // Two error-free blocks the right distance apart: synchronised. This block starts the group
            // assembly at its own position; earlier blocks of the group are simply missing.
            synced = true
            sinceClean = 0
            bitCount = 0
            blockIndex = index
            valid.fill(false)
            clean.fill(false)
            store(reg ushr 10, isClean = true)
        } else {
            candidateIndex = index
            candidateAge = 0
        }
    }

    private fun receiveBlock() {
        val syndrome = RdsCode.syndrome(reg)
        var data = -1
        var isClean = false
        if (blockIndex == 2 && (syndrome == RdsCode.OFFSET_C || syndrome == RdsCode.OFFSET_C_PRIME)) {
            data = reg ushr 10
            isClean = true
        } else {
            val offset = when (blockIndex) {
                0 -> RdsCode.OFFSET_A
                1 -> RdsCode.OFFSET_B
                2 -> if (valid[1] && (word[1] ushr 11) and 1 == 1) RdsCode.OFFSET_C_PRIME else RdsCode.OFFSET_C
                else -> RdsCode.OFFSET_D
            }
            if (syndrome == offset) {
                data = reg ushr 10
                isClean = true
            } else {
                val error = RdsCode.burstErrors[syndrome xor offset]
                if (error != 0) data = (reg xor error) ushr 10
            }
        }
        if (isClean) {
            blocksClean++
            sinceClean = 0
        } else {
            if (data >= 0) blocksCorrected++ else blocksFailed++
            if (++sinceClean > MAX_BLOCKS_WITHOUT_CLEAN) {
                synced = false
                bitsInReg = 26
                candidateIndex = -1
                return
            }
        }
        if (data >= 0) store(data, isClean) else advance()
    }

    private fun store(data: Int, isClean: Boolean) {
        word[blockIndex] = data
        valid[blockIndex] = true
        clean[blockIndex] = isClean
        advance()
    }

    private fun advance() {
        if (blockIndex == 3) {
            decodeGroup()
            valid.fill(false)
            clean.fill(false)
            blockIndex = 0
        } else {
            blockIndex++
        }
    }

    /** True when [value] may be shown: it came error-free, or matches the previous reception. */
    private fun confirmed(value: Int, previous: Int, isClean: Boolean) = isClean || value == previous

    private fun decodeGroup() {
        var changed = false
        if (valid[0]) {
            val value = word[0]
            if (confirmed(value, piCandidate, clean[0]) && value != pi) {
                pi = value
                clearProgramme()
                changed = true
            }
            piCandidate = value
        }
        if (valid[1]) {
            val b = word[1]
            val type = b ushr 12
            val versionB = (b ushr 11) and 1 == 1
            val f = (b ushr 5) and 0x3F
            if (confirmed(f, flagsCandidate, clean[1]) && f != flags) {
                flags = f
                changed = true
            }
            flagsCandidate = f
            when (type) {
                0 -> changed = decodeBasic(b) or changed
                2 -> changed = decodeRadioText(b, versionB) or changed
                4 -> if (!versionB) changed = decodeClock(b) or changed
            }
        }
        if (changed) publish()
    }

    private fun decodeBasic(b: Int): Boolean {
        var changed = false
        val announcement = (b ushr 4) and 1
        if (confirmed(announcement, taCandidate, clean[1]) && (announcement == 1) != ta) {
            ta = announcement == 1
            changed = true
        }
        taCandidate = announcement
        if (valid[3]) {
            val at = 2 * (b and 3)
            changed = accept(ps, psCandidate, at, word[3] ushr 8, clean[1] && clean[3]) or changed
            changed = accept(ps, psCandidate, at + 1, word[3] and 0xFF, clean[1] && clean[3]) or changed
        }
        return changed
    }

    private fun decodeRadioText(b: Int, versionB: Boolean): Boolean {
        var changed = false
        val flag = (b ushr 4) and 1
        if (flag != rtFlag) {
            // A changed A/B flag announces a new text; believe it only from an error-free block.
            if (!clean[1]) return false
            if (rtFlag >= 0) {
                rt.fill(-1)
                rtCandidate.fill(-1)
                changed = true
            }
            rtFlag = flag
        }
        val segment = b and 0xF
        if (versionB) {
            if (valid[3]) {
                changed = accept(rt, rtCandidate, 2 * segment, word[3] ushr 8, clean[1] && clean[3]) or changed
                changed = accept(rt, rtCandidate, 2 * segment + 1, word[3] and 0xFF, clean[1] && clean[3]) or changed
            }
        } else {
            if (valid[2]) {
                changed = accept(rt, rtCandidate, 4 * segment, word[2] ushr 8, clean[1] && clean[2]) or changed
                changed = accept(rt, rtCandidate, 4 * segment + 1, word[2] and 0xFF, clean[1] && clean[2]) or changed
            }
            if (valid[3]) {
                changed = accept(rt, rtCandidate, 4 * segment + 2, word[3] ushr 8, clean[1] && clean[3]) or changed
                changed = accept(rt, rtCandidate, 4 * segment + 3, word[3] and 0xFF, clean[1] && clean[3]) or changed
            }
        }
        return changed
    }

    private fun decodeClock(b: Int): Boolean {
        // The time changes every minute, so repetition cannot confirm it: take error-free groups only.
        if (!(clean[1] && valid[2] && clean[2] && valid[3] && clean[3])) return false
        val mjd = ((b and 3) shl 15) or (word[2] ushr 1)
        val hour = ((word[2] and 1) shl 4) or (word[3] ushr 12)
        val minute = (word[3] ushr 6) and 0x3F
        val magnitude = word[3] and 0x1F
        val offset = if ((word[3] ushr 5) and 1 == 1) -magnitude else magnitude
        if (hour > 23 || minute > 59) return false
        val value = RdsClock(mjd, hour, minute, offset)
        if (value == clock) return false
        clock = value
        return true
    }

    private fun accept(shown: IntArray, candidate: IntArray, at: Int, code: Int, isClean: Boolean): Boolean {
        val ok = confirmed(code, candidate[at], isClean)
        candidate[at] = code
        if (!ok || shown[at] == code) return false
        shown[at] = code
        return true
    }

    private fun clearProgramme() {
        flags = 0
        flagsCandidate = -1
        ta = false
        taCandidate = -1
        ps.fill(-1)
        psCandidate.fill(-1)
        rt.fill(-1)
        rtCandidate.fill(-1)
        rtFlag = -1
        clock = null
    }

    private fun publish() {
        val name = StringBuilder(8)
        var anyName = false
        for (code in ps) {
            name.append(char(code))
            if (code >= 0) anyName = true
        }
        val text = StringBuilder(64)
        for (code in rt) {
            if (code == 0x0D) break
            text.append(char(code))
        }
        state = RdsState(
            pi = pi,
            pty = flags and 0x1F,
            tp = (flags ushr 5) and 1 == 1,
            ta = ta,
            ps = if (anyName) name.toString() else "",
            rt = text.toString().trimEnd(),
            clock = clock,
        )
    }

    /** RDS basic character set, reduced to its ASCII part; everything else shows as a space. */
    private fun char(code: Int): Char = if (code in 0x20..0x7E) code.toChar() else ' '

    private companion object {
        const val MAX_SEARCH_BLOCKS = 3
        const val MAX_BLOCKS_WITHOUT_CLEAN = 50
    }
}
