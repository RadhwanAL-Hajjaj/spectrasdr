// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleConverterTest {
    @Test
    fun u8MapsToSymmetricFloats() {
        val src = byteArrayOf(0, 255.toByte(), 127, 128.toByte())
        val dst = FloatArray(4)
        assertEquals(2, SampleConverter.convert(SampleFormat.U8, src, 0, src.size, dst, 0, 2))
        assertEquals(-1f, dst[0], 1e-6f)
        assertEquals(1f, dst[1], 1e-6f)
        assertEquals(-0.5f / 127.5f, dst[2], 1e-6f)
        assertEquals(0.5f / 127.5f, dst[3], 1e-6f)
    }

    @Test
    fun s16IsLittleEndianSigned() {
        // I = 32767, Q = -32768, then I = 1, Q = -1
        val src = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val dst = FloatArray(4)
        assertEquals(2, SampleConverter.convert(SampleFormat.S16LE, src, 0, src.size, dst, 0, 10))
        assertEquals(32767f / 32768f, dst[0], 1e-7f)
        assertEquals(-1f, dst[1], 1e-7f)
        assertEquals(1f / 32768f, dst[2], 1e-9f)
        assertEquals(-1f / 32768f, dst[3], 1e-9f)
    }

    @Test
    fun trailingPartialSampleIsLeftForTheCaller() {
        val src = ByteArray(7) { 127 }
        val dst = FloatArray(8)
        assertEquals(1, SampleConverter.convert(SampleFormat.S16LE, src, 0, 7, dst, 0, 4))
        assertEquals(3, SampleConverter.convert(SampleFormat.U8, src, 0, 7, dst, 0, 4))
    }

    @Test
    fun respectsOffsetsAndMaximum() {
        val src = ByteArray(10) { (it * 20).toByte() }
        val dst = FloatArray(10) { 9f }
        assertEquals(2, SampleConverter.convert(SampleFormat.U8, src, 2, 8, dst, 1, 2))
        assertEquals(9f, dst[0], 0f)
        assertEquals(9f, dst[1], 0f)
        assertEquals((40 - 127.5f) / 127.5f, dst[2], 1e-6f)
        assertEquals((100 - 127.5f) / 127.5f, dst[5], 1e-6f)
        assertEquals(9f, dst[6], 0f)
    }
}
