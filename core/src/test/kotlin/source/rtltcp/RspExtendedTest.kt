// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class RspExtendedTest {
    private val caps = RspCapabilities(
        version = 1,
        capabilities = 0x01 or 0x08 or 0x10 or 0x80,
        hardwareVersion = 255,
        sampleFormat = RspCapabilities.FORMAT_S16,
        antennaInputCount = 3,
        thirdAntennaName = "Hi-Z",
        thirdAntennaFreqLimit = 60_000_000,
        tunerCount = 1,
        ifgrMin = 20,
        ifgrMax = 59,
    )

    private fun immediate(port: Int) = RtlTcpClient(RtlTcpConfig("127.0.0.1", port, retuneSync = RetuneSyncMode.IMMEDIATE))

    @Test
    fun parsesTheStructureIncludingItsLittleEndianField() {
        val bytes = RspCapabilities.encode(caps)
        assertEquals(45, bytes.size)
        // 60_000_000 = 0x03938700, sent in host (little-endian) order
        assertEquals(listOf(0x00, 0x87, 0x93, 0x03), (38..41).map { bytes[it].toInt() and 0xFF })
        assertEquals(caps, RspCapabilities.parse(bytes))
        assertEquals(RspModel.RSP1A, caps.model)
    }

    @Test
    fun detectsExtendedModeAndConvertsS16Samples() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            stations = listOf(100_000_000.0 to 0.5),
            noiseRms = 0.0,
        ).start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            val x = l.awaitEpochSamples(epoch = 1, complex = 5_000)
            val info = l.info!!
            assertEquals(SourceKind.RSP_TCP, info.kind)
            assertEquals(SampleFormat.S16LE, info.sampleFormat)
            assertEquals(GainModel.Rsp(10, 20, 59, true), info.gainModel)
            assertEquals(setOf(OptionKind.BIAS_T, OptionKind.RSP_ANTENNA, OptionKind.RSP_NOTCHES), info.options)
            assertEquals(0.5f, x[0], 1e-4f)
            assertEquals(0f, x[1], 1e-4f)
            c.close()
        }
    }

    @Test
    fun standardServerKeepsTheFirstSampleBytes() {
        FakeRtlTcpServer(startInTestMode = true).start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            val first = l.awaitEpochSamples(epoch = 1, complex = 2)
            assertEquals(SourceKind.RTL_TCP, l.info!!.kind)
            assertEquals(-1f, first[0], 1e-6f)
            assertEquals((1 - 127.5f) / 127.5f, first[1], 1e-6f)
            assertEquals((2 - 127.5f) / 127.5f, first[2], 1e-6f)
            assertEquals((3 - 127.5f) / 127.5f, first[3], 1e-6f)
            c.close()
        }
    }

    @Test
    fun extendedCommandsUseRspCodes() {
        FakeRtlTcpServer(extraHandshake = RspCapabilities.encode(caps), format = SampleFormat.S16LE).start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setGain(GainSetting.RspLna(3))
            c.setGain(GainSetting.RspIfgr(45))
            c.setGain(GainSetting.RspAgc(true, -25))
            c.setOption(SourceOption.RspNotches(0x06))
            c.setOption(SourceOption.RspAntenna(2))
            c.setOption(SourceOption.BiasT(true))
            server.awaitCommands(2 + 7)
            assertEquals(
                listOf(0x20 to 3, 0x21 to 45, 0x22 to 1, 0x23 to -25, 0x24 to 6, 0x1f to 2, 0x25 to 1),
                server.commands.drop(2),
            )
            c.close()
        }
    }

    @Test
    fun biasTeeUsesTheStandardCommandOnRtlServers() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setOption(SourceOption.BiasT(true))
            server.awaitCommands(3)
            assertEquals(RtlTcpProtocol.SET_BIAS_TEE to 1, server.commands[2])
            c.close()
        }
    }
}
