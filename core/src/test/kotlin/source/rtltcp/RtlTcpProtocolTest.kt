// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtlTcpProtocolTest {
    @Test
    fun parsesTheDongleHeader() {
        val h = ByteArray(12)
        "RTL0".toByteArray(Charsets.US_ASCII).copyInto(h)
        RtlTcpProtocol.writeIntBE(h, 4, 5)
        RtlTcpProtocol.writeIntBE(h, 8, 29)
        val d = RtlTcpProtocol.parseHeader(h)
        assertEquals("RTL0", d.magic)
        assertEquals(RtlTcpProtocol.TunerType.R820T, d.tunerType)
        assertEquals(29, d.gainCount)
        assertEquals(29, d.tunerType.gainsTenthsDb.size)
        assertEquals(496, d.tunerType.gainsTenthsDb.last())
    }

    @Test
    fun encodesCommandsBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x05, 0xF5.toByte(), 0xE1.toByte(), 0x00),
            RtlTcpProtocol.encodeCommand(RtlTcpProtocol.SET_FREQ, 100_000_000),
        )
        assertArrayEquals(
            byteArrayOf(0x05, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFD.toByte()),
            RtlTcpProtocol.encodeCommand(RtlTcpProtocol.SET_FREQ_CORRECTION, -3),
        )
    }

    @Test
    fun frequenciesAboveTwoGigahertzAreSentUnsigned() {
        assertEquals(2_400_000_000L, RtlTcpClient.freqParam(2_400_000_000L).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun unknownTunerIdsMapToUnknown() {
        assertEquals(RtlTcpProtocol.TunerType.UNKNOWN, RtlTcpProtocol.TunerType.of(42))
    }

    @Test
    fun littleEndianHelpersRoundTrip() {
        val b = ByteArray(4)
        RtlTcpProtocol.writeIntLE(b, 0, 60_000_000)
        assertEquals(0x00, b[0].toInt() and 0xFF)
        assertEquals(0x87, b[1].toInt() and 0xFF)
        assertEquals(60_000_000, RtlTcpProtocol.readIntLE(b, 0))
    }
}
