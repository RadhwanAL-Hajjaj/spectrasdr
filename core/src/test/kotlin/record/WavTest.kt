// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WavTest {
    private fun tempFile(name: String = "test.wav"): File =
        Files.createTempDirectory("wav").toFile().resolve(name).also { it.deleteOnExit() }

    @Test
    fun roundTripsEightAndSixteenBitFiles() {
        for (bits in intArrayOf(8, 16)) {
            val f = tempFile()
            val data = ByteArray(10_000) { (it * 31).toByte() }
            WavWriter(f, 96_000, 2, bits).use { it.write(data) }
            val info = WavReader.read(f)
            assertEquals(96_000, info.sampleRate)
            assertEquals(2, info.channels)
            assertEquals(bits, info.bitsPerSample)
            assertEquals(44L, info.dataOffset)
            assertEquals(10_000L, info.dataBytes)
            assertArrayEquals(data, f.readBytes().copyOfRange(44, 44 + 10_000))
        }
    }

    @Test
    fun headerStaysValidWithoutClose() {
        val f = tempFile()
        var now = 0L
        val w = WavWriter(f, 48_000, 1, 16, headerIntervalMs = 2_000, clock = { now })
        w.write(ByteArray(1_000))
        now = 2_500
        w.write(ByteArray(500))     // triggers a header update covering 1 500 bytes
        now = 2_600
        w.write(ByteArray(500))     // buffered, not yet in the header
        val crashed = tempFile("copy.wav")
        f.copyTo(crashed, overwrite = true)   // simulates the process dying here
        assertEquals(1_500L, WavReader.read(crashed).dataBytes)
        w.close()
        assertEquals(2_000L, WavReader.read(f).dataBytes)
    }

    @Test
    fun zeroDataSizeMeansUseTheFileLength() {
        val f = tempFile()
        WavWriter(f, 8_000, 2, 8).use { it.write(ByteArray(1_000)) }
        val bytes = f.readBytes()
        for (i in 40 until 44) bytes[i] = 0
        f.writeBytes(bytes)
        assertEquals(1_000L, WavReader.read(f).dataBytes)
    }

    @Test
    fun skipsUnknownChunks() {
        val f = tempFile()
        WavWriter(f, 8_000, 2, 8).use { it.write(ByteArray(100) { 7 }) }
        val plain = f.readBytes()
        val list = byteArrayOf(
            'L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
            4, 0, 0, 0,
            'I'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), 'O'.code.toByte(),
        )
        f.writeBytes(plain.copyOfRange(0, 12) + list + plain.copyOfRange(12, plain.size))
        val info = WavReader.read(f)
        assertEquals(44L + list.size, info.dataOffset)
        assertEquals(100L, info.dataBytes)
    }
}
