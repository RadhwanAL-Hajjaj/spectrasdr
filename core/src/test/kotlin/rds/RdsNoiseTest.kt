// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RDS on weak signals (§12): the share of blocks lost at each signal-to-noise ratio stays under a pass line. */
class RdsNoiseTest {
    private val encoder = RdsEncoder(0xD318, "SPECTRA ", "Hello from SpectraSDR, RDS test", pty = 10)

    /** Twelve seconds (about 545 blocks) of the station at [cnrDb] in the 240 kHz band. */
    private fun receive(cnrDb: Double) = RdsTestSignals.receive(encoder, 12.0, cnrDb = cnrDb, seed = 1)

    private fun blocks(d: RdsDecoder) = d.blocksClean + d.blocksCorrected + d.blocksFailed

    @Test
    fun blockErrorRateStaysUnderItsPassLine() {
        // Carrier-to-noise ratio in dB to the largest share of undecodable blocks allowed there.
        // Measured when the test was written: 0 %, 0 %, 1.5 % and 13 %.
        val passLine = listOf(20.0 to 0.005, 16.0 to 0.02, 14.0 to 0.05, 12.0 to 0.25)
        for ((cnrDb, limit) in passLine) {
            val d = receive(cnrDb)
            val total = blocks(d)
            assertTrue("CNR $cnrDb dB: only $total blocks", total > 500)
            assertTrue("CNR $cnrDb dB: ${d.blocksFailed} of $total blocks failed", d.blocksFailed <= limit * total)
            if (cnrDb >= 14.0) {
                assertEquals("CNR $cnrDb dB", 0xD318, d.state.pi)
                assertEquals("CNR $cnrDb dB", "SPECTRA ", d.state.ps)
                assertEquals("CNR $cnrDb dB", "Hello from SpectraSDR, RDS test", d.state.rt)
            }
        }
    }

    @Test
    fun errorCorrectionRepairsMostDamagedBlocksOfAWeakSignal() {
        val d = receive(14.0)
        val total = blocks(d)
        assertTrue("${d.blocksCorrected} corrected of $total", d.blocksCorrected > total / 10)
        assertTrue("${d.blocksCorrected} corrected, ${d.blocksFailed} failed", d.blocksCorrected > 5 * d.blocksFailed)
    }
}
