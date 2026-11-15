// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RdsDecoderTest {
    private fun feed(decoder: RdsDecoder, bits: ByteArray, cycles: Int = 1) {
        repeat(cycles) { for (b in bits) decoder.push(b.toInt()) }
    }

    @Test
    fun decodesNameAndTextFromACleanStream() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0xD318, "SPECTRA ", "Hello from SpectraSDR", pty = 10, tp = true).bits, 2)
        assertTrue(decoder.synced)
        val s = decoder.state
        assertEquals(0xD318, s.pi)
        assertEquals("SPECTRA ", s.ps)
        assertEquals("Hello from SpectraSDR", s.rt)
        assertEquals(10, s.pty)
        assertTrue(s.tp)
        assertFalse(s.ta)
        assertEquals(0L, decoder.blocksFailed)
        assertEquals(0L, decoder.blocksCorrected)
    }

    @Test
    fun synchronisesFromAnyBitOffset() {
        val bits = RdsEncoder(0x1234, "OFFSET  ").bits
        for (skip in intArrayOf(1, 13, 27, 77, 103)) {
            val decoder = RdsDecoder()
            for (i in skip until bits.size) decoder.push(bits[i].toInt())
            feed(decoder, bits, 2)
            assertEquals("skip $skip", "OFFSET  ", decoder.state.ps)
        }
    }

    @Test
    fun correctsBurstsOfUpToFiveBits() {
        val bits = RdsEncoder(0x4321, "BURSTS  ", "Corrected text").bits
        val decoder = RdsDecoder()
        feed(decoder, bits)                      // clean pass to synchronise
        val rnd = Random(3)
        repeat(4) {
            val damaged = bits.copyOf()
            for (block in 0 until damaged.size / 26) {
                if (block % 5 == 0) continue      // sync is only kept while error-free blocks keep arriving
                val length = 1 + rnd.nextInt(5)
                val start = block * 26 + rnd.nextInt(26 - length + 1)
                damaged[start] = (1 - damaged[start]).toByte()
                damaged[start + length - 1] = (1 - bits[start + length - 1]).toByte()
            }
            feed(decoder, damaged)
        }
        assertTrue(decoder.synced)
        assertEquals(0L, decoder.blocksFailed)
        assertTrue(decoder.blocksCorrected > 0)
        assertEquals("BURSTS  ", decoder.state.ps)
        assertEquals("Corrected text", decoder.state.rt)
    }

    @Test
    fun aCorrectedCharacterNeedsASecondReception() {
        val clean = RdsEncoder(0x4321, "ABCDEFGH").bits
        val other = RdsEncoder(0x4321, "ABCDEFXY").bits
        val decoder = RdsDecoder()
        feed(decoder, clean, 2)
        assertEquals("ABCDEFGH", decoder.state.ps)
        // One bit error in block D of the last name group: the block is corrected, not error-free.
        val damaged = other.copyOf()
        val blockD = 3 * 104 + 3 * 26
        damaged[blockD + 20] = (1 - damaged[blockD + 20]).toByte()
        feed(decoder, damaged)
        assertEquals("first reception is not shown", "ABCDEFGH", decoder.state.ps)
        feed(decoder, damaged)
        assertEquals("second identical reception is shown", "ABCDEFXY", decoder.state.ps)
    }

    @Test
    fun textResetsWhenTheStationChanges() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0x1111, "FIRST   ", "First text").bits, 2)
        assertEquals("First text", decoder.state.rt)
        feed(decoder, RdsEncoder(0x2222, "SECOND  ").bits, 1)
        assertEquals(0x2222, decoder.state.pi)
        assertEquals("SECOND  ", decoder.state.ps)
        assertEquals("", decoder.state.rt)
    }

    @Test
    fun aNewTextFlagClearsTheOldText() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0x1111, "FLAG    ", "A long first radio text").bits, 2)
        val groups = listOf(
            RdsEncoder.group2A(0x1111, 0, false, true, 0, 'N'.code, 'e'.code, 'w'.code, '\r'.code),
        )
        feed(decoder, RdsEncoder.toBits(groups), 2)
        assertEquals("New", decoder.state.rt)
    }

    @Test
    fun decodesVersionBGroupsAndTheClock() {
        val decoder = RdsDecoder()
        val versionB = 1 shl 11
        val groups = ArrayList<IntArray>()
        val name = "VERSIONB"
        for (s in 0 until 4) {
            val g = RdsEncoder.group0A(0x5555, 5, false, true, s, name[2 * s].code, name[2 * s + 1].code)
            groups += intArrayOf(g[0], g[1] or versionB, 0x5555, g[3])
        }
        val text = "Short\r"
        for (s in 0 until 3) {
            groups += intArrayOf(0x5555, (2 shl 12) or versionB or (5 shl 5) or s, 0x5555, (text[2 * s].code shl 8) or text[2 * s + 1].code)
        }
        groups += RdsEncoder.group4A(0x5555, 5, false, mjd = 61_357, hour = 21, minute = 47, offsetHalfHours = -6)
        feed(decoder, RdsEncoder.toBits(groups), 2)
        val s = decoder.state
        assertEquals("VERSIONB", s.ps)
        assertEquals("Short", s.rt)
        assertTrue(s.ta)
        assertEquals(RdsClock(61_357, 21, 47, -6), s.clock)
    }

    @Test
    fun noiseNeitherHoldsSyncNorShowsText() {
        val decoder = RdsDecoder()
        val rnd = Random(7)
        var syncedBits = 0
        repeat(1_187 * 120) {             // two minutes of random bits
            decoder.push(rnd.nextInt(2))
            if (decoder.synced) syncedBits++
        }
        assertTrue("synced for $syncedBits bits", syncedBits < 1_187 * 30)
        assertEquals("", decoder.state.ps.trim())
        assertEquals("", decoder.state.rt)
        assertNull(decoder.state.clock)
    }

    @Test
    fun dropsSyncWhenTheSignalDisappears() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0x1234, "GONE    ").bits, 2)
        assertTrue(decoder.synced)
        val rnd = Random(5)
        repeat(26 * 200) { decoder.push(rnd.nextInt(2)) }
        assertFalse(decoder.synced)
        assertEquals("the last name stays on display", "GONE    ", decoder.state.ps)
    }
}
