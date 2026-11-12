// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/**
 * Exact retune boundaries from RTL2832U test-mode bursts (§7.1 step 2). After a retune's own
 * commands, test mode goes on and, [markerGapMs] later, off. In test mode the chip sends an
 * incrementing byte counter, and commands execute in order, so new-frequency samples begin where
 * the last expected burst ends (plus a [guardMs] guard). A burst is ≥ [minRun] consecutive
 * incrementing bytes.
 */
class CounterMarkerSync(
    private val minRun: Int = 64,
    private val guardMs: Double = 1.0,
    private val timeoutMs: Double = 1_000.0,
    private val markerGapMs: Long = 5,
) : RetuneSync() {
    private var expected = 0
    private var run = 0
    private var last = -1
    private var inBurst = false
    private var guardLeft = 0
    private var guardTotal = 0
    private var scanned = 0L
    private var timeoutBytes = Long.MAX_VALUE
    private var bps = 2

    /** True once a retune finished by timeout instead of a marker: the server ignores test mode. */
    @Volatile
    var timedOut = false
        private set

    override fun wrap(commands: List<TimedCommand>): List<TimedCommand> = commands +
        TimedCommand(RtlTcpProtocol.SET_TEST_MODE, 1) +
        TimedCommand(RtlTcpProtocol.SET_TEST_MODE, 0, markerGapMs)

    override fun onRetuneRequested(count: Int, bytesPerSample: Int, sampleRate: Int) {
        expected += count
        bps = bytesPerSample
        guardTotal = (guardMs / 1000.0 * sampleRate).toInt() * bytesPerSample
        timeoutBytes = (timeoutMs / 1000.0 * sampleRate).toLong() * bytesPerSample
        scanned = 0
        guardLeft = 0
    }

    override fun scan(bytes: ByteArray, off: Int, len: Int): Int {
        var i = 0
        while (i < len) {
            if (guardLeft > 0) {
                val take = minOf(guardLeft, len - i)
                guardLeft -= take
                i += take
                if (guardLeft == 0) return i
                continue
            }
            val v = bytes[off + i].toInt() and 0xFF
            if (last >= 0 && v == ((last + 1) and 0xFF)) {
                if (++run >= minRun) inBurst = true
            } else {
                if (inBurst) {
                    inBurst = false
                    expected--
                    if (expected <= 0) {
                        expected = 0
                        run = 0
                        last = -1
                        val start = i - i % bps   // the breaking byte's sample starts here
                        if (guardTotal == 0) return start
                        guardLeft = guardTotal
                        i = start
                        continue
                    }
                }
                run = 0
            }
            last = v
            i++
            if (++scanned >= timeoutBytes && !inBurst) {
                expected = 0
                run = 0
                last = -1
                timedOut = true
                return (i + bps - 1) / bps * bps
            }
        }
        return -1
    }
}
