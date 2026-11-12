// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
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

    @Test
    fun fullDiskFailsCleanlyAndCloseNeverThrows() {
        Assume.assumeTrue(File("/dev/full").exists())
        val w = WavWriter(File("/dev/full"), 48_000, 1, 16)
        val chunk = ByteArray(1_024)
        var failed = false
        for (i in 0 until 1_000) {
            try {
                w.write(chunk)
            } catch (e: IOException) {
                failed = true
                break
            }
        }
        assertTrue("no write failed on /dev/full", failed)
        w.close()
        w.close()
        try {
            w.write(chunk)
            fail("write after a failure should throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("earlier write"))
        }
    }

    private fun malformed(name: String, bytes: ByteArray) {
        val f = tempFile(name)
        f.writeBytes(bytes)
        try {
            WavReader.read(f)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains(name))
        }
    }

    private fun wavWithFmtSize(size: Long): ByteArray {
        val b = ByteArray(44)
        "RIFF".toByteArray().copyInto(b, 0)
        "WAVE".toByteArray().copyInto(b, 8)
        "fmt ".toByteArray().copyInto(b, 12)
        for (i in 0 until 4) b[16 + i] = (size shr (8 * i)).toByte()
        return b
    }

    @Test
    fun rejectsFmtChunkTooSmall() = malformed("small_fmt.wav", wavWithFmtSize(8))

    @Test
    fun rejectsHugeFmtChunk() = malformed("huge_fmt.wav", wavWithFmtSize(0x7FFFFFF0L))

    @Test
    fun rejectsTinyFile() = malformed("tiny.wav", ByteArray(10))
}
