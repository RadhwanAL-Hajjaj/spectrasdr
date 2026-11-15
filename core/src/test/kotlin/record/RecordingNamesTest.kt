// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RecordingNamesTest {
    private val t = Instant.parse("2026-11-12T09:05:07Z")

    @Test
    fun formatsIqAndAudioNames() {
        assertEquals("SpectraSDR_20261112_090507Z_145500000Hz_IQ.wav", RecordingNames.iq(t, 145_500_000))
        assertEquals("SpectraSDR_20261112_090507Z_98100000Hz_WFM.wav", RecordingNames.audio(t, 98_100_000, "WFM"))
    }

    @Test
    fun parsesCentreFrequencyFromCommonNamingSchemes() {
        assertEquals(100_100_000L, RecordingNames.parseCenterHz("SDRSharp_20171123_145327Z_100100000Hz_IQ.wav"))
        assertEquals(100_100_000L, RecordingNames.parseCenterHz("baseband_100100000Hz_14-53-27_23-11-2017.wav"))
        assertEquals(145_500_000L, RecordingNames.parseCenterHz(RecordingNames.iq(t, 145_500_000)))
        assertNull(RecordingNames.parseCenterHz("recording.wav"))
    }
}
