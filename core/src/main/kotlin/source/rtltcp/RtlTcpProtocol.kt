// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/** rtl_tcp wire protocol: 12-byte dongle header and 5-byte big-endian commands (§5.2, §5.3). */
object RtlTcpProtocol {
    const val HEADER_SIZE = 12
    const val COMMAND_SIZE = 5

    const val SET_FREQ = 0x01
    const val SET_SAMPLE_RATE = 0x02
    const val SET_GAIN_MODE = 0x03
    const val SET_GAIN = 0x04
    const val SET_FREQ_CORRECTION = 0x05
    const val SET_IF_GAIN = 0x06
    const val SET_TEST_MODE = 0x07
    const val SET_AGC_MODE = 0x08
    const val SET_DIRECT_SAMPLING = 0x09
    const val SET_OFFSET_TUNING = 0x0a
    const val SET_RTL_XTAL = 0x0b
    const val SET_TUNER_XTAL = 0x0c
    const val SET_GAIN_BY_INDEX = 0x0d
    const val SET_BIAS_TEE = 0x0e

    const val ANDROID_EXIT = 0x7e
    const val ANDROID_GAIN_PERCENT = 0x7f
    const val ANDROID_ENABLE_16BIT = 0x80

    const val RSP_SET_ANTENNA = 0x1f
    const val RSP_SET_LNA_STATE = 0x20
    const val RSP_SET_IF_GAIN_R = 0x21
    const val RSP_SET_AGC = 0x22
    const val RSP_SET_AGC_SETPOINT = 0x23
    const val RSP_SET_NOTCH = 0x24
    const val RSP_SET_BIAS_T = 0x25
    const val RSP_SET_REF_OUT = 0x26

    data class DongleInfo(val magic: String, val tunerType: TunerType, val gainCount: Int)

    /** librtlsdr tuner ids and their gain tables in tenths of a dB. */
    enum class TunerType(val id: Int, val gainsTenthsDb: List<Int>) {
        UNKNOWN(0, emptyList()),
        E4000(1, listOf(-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420)),
        FC0012(2, listOf(-99, -40, 71, 179, 192)),
        FC0013(3, listOf(-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197)),
        FC2580(4, emptyList()),
        R820T(5, R82XX_GAINS),
        R828D(6, R82XX_GAINS);

        companion object {
            fun of(id: Int): TunerType = entries.firstOrNull { it.id == id } ?: UNKNOWN
        }
    }

    fun parseHeader(b: ByteArray): DongleInfo {
        require(b.size >= HEADER_SIZE) { "header needs $HEADER_SIZE bytes" }
        return DongleInfo(String(b, 0, 4, Charsets.US_ASCII), TunerType.of(readIntBE(b, 4)), readIntBE(b, 8))
    }

    fun encodeCommand(command: Int, param: Int, out: ByteArray = ByteArray(COMMAND_SIZE)): ByteArray {
        out[0] = command.toByte()
        writeIntBE(out, 1, param)
        return out
    }

    fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    fun writeIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}

/** R820T / R828D gain steps (librtlsdr r82xx_gains), tenths of a dB. */
private val R82XX_GAINS = listOf(
    0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
    280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
)
