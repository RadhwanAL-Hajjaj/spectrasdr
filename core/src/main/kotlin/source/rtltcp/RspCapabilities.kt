// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/** rsp_tcp extended-mode capability structure: 45 packed bytes after the header (§5.3). */
data class RspCapabilities(
    val version: Int,
    val capabilities: Int,
    val hardwareVersion: Int,
    val sampleFormat: Int,
    val antennaInputCount: Int,
    val thirdAntennaName: String,
    val thirdAntennaFreqLimit: Int,
    val tunerCount: Int,
    val ifgrMin: Int,
    val ifgrMax: Int,
) {
    val model: RspModel get() = RspModel.of(hardwareVersion)
    val hasBiasT: Boolean get() = capabilities and 0x01 != 0
    val hasNotches: Boolean get() = capabilities and 0x78 != 0
    val hasAgc: Boolean get() = capabilities and 0x80 != 0

    companion object {
        const val SIZE = 45
        const val MAGIC = "RSP0"
        const val FORMAT_U8 = 1
        const val FORMAT_S16 = 2

        fun parse(b: ByteArray): RspCapabilities {
            require(b.size >= SIZE && String(b, 0, 4, Charsets.US_ASCII) == MAGIC) { "not an RSP0 structure" }
            val nameEnd = (25 until 38).firstOrNull { b[it].toInt() == 0 } ?: 38
            return RspCapabilities(
                version = RtlTcpProtocol.readIntBE(b, 4),
                capabilities = RtlTcpProtocol.readIntBE(b, 8),
                hardwareVersion = RtlTcpProtocol.readIntBE(b, 16),
                sampleFormat = RtlTcpProtocol.readIntBE(b, 20),
                antennaInputCount = b[24].toInt() and 0xFF,
                thirdAntennaName = String(b, 25, nameEnd - 25, Charsets.US_ASCII),
                // rsp_tcp sends this field without byte-order conversion (little-endian hosts).
                thirdAntennaFreqLimit = RtlTcpProtocol.readIntLE(b, 38),
                tunerCount = b[42].toInt() and 0xFF,
                ifgrMin = b[43].toInt() and 0xFF,
                ifgrMax = b[44].toInt() and 0xFF,
            )
        }

        /** Builds the structure exactly as rsp_tcp sends it. */
        fun encode(c: RspCapabilities): ByteArray {
            val b = ByteArray(SIZE)
            MAGIC.toByteArray(Charsets.US_ASCII).copyInto(b)
            RtlTcpProtocol.writeIntBE(b, 4, c.version)
            RtlTcpProtocol.writeIntBE(b, 8, c.capabilities)
            RtlTcpProtocol.writeIntBE(b, 16, c.hardwareVersion)
            RtlTcpProtocol.writeIntBE(b, 20, c.sampleFormat)
            b[24] = c.antennaInputCount.toByte()
            val name = c.thirdAntennaName.toByteArray(Charsets.US_ASCII)
            name.copyInto(b, 25, 0, minOf(12, name.size))
            RtlTcpProtocol.writeIntLE(b, 38, c.thirdAntennaFreqLimit)
            b[42] = c.tunerCount.toByte()
            b[43] = c.ifgrMin.toByte()
            b[44] = c.ifgrMax.toByte()
            return b
        }
    }
}

/** SDRplay models by the API hwVer that rsp_tcp reports; [maxLnaStates] is the largest over all bands. */
enum class RspModel(val hwVer: Int, val displayName: String, val maxLnaStates: Int) {
    RSP1(1, "RSP1", 4),
    RSP1A(255, "RSP1A", 10),
    RSP2(2, "RSP2", 9),
    RSPDUO(3, "RSPduo", 10),
    RSPDX(4, "RSPdx", 28),
    RSP1B(6, "RSP1B", 10),
    RSPDX_R2(7, "RSPdx-R2", 28),
    UNKNOWN(0, "RSP", 10);

    companion object {
        fun of(hwVer: Int): RspModel = entries.firstOrNull { it.hwVer == hwVer } ?: UNKNOWN
    }
}
