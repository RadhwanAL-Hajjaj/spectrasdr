// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RdsEndToEndTest {
    private val encoder = RdsEncoder(0xD318, "SPECTRA ", "Hello from SpectraSDR, RDS test", pty = 10)

    private fun assertDecoded(decoder: RdsDecoder) {
        assertTrue(decoder.synced)
        assertEquals(0xD318, decoder.state.pi)
        assertEquals("SPECTRA ", decoder.state.ps)
        assertEquals("Hello from SpectraSDR, RDS test", decoder.state.rt)
        assertEquals(10, decoder.state.pty)
    }

    @Test
    fun decodesAStereoStationExactly() {
        val decoder = RdsTestSignals.receive(encoder, 4.0)
        assertDecoded(decoder)
        assertEquals(0L, decoder.blocksFailed)
        assertEquals(0L, decoder.blocksCorrected)
    }

    @Test
    fun subcarrierInQuadratureWithThePilotHarmonicDecodesToo() {
        assertDecoded(RdsTestSignals.receive(encoder, 4.0, rdsPhaseTurns = 0.25))
        assertDecoded(RdsTestSignals.receive(encoder, 4.0, rdsPhaseTurns = 0.4))
    }

    @Test
    fun monoStationWithoutPilotDecodesWithTheFreeRunningCarrier() {
        assertDecoded(RdsTestSignals.receive(encoder, 5.0, stereo = false))
    }

    @Test
    fun toleratesFiftyPartsPerMillionOfClockError() {
        assertDecoded(RdsTestSignals.receive(encoder, 5.0, rateError = 5e-5))
        assertDecoded(RdsTestSignals.receive(encoder, 5.0, rateError = -5e-5, stereo = false))
    }
}
