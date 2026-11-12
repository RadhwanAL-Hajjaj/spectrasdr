// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit.SECONDS

class RtlTcpClientTest {
    private fun client(port: Int, tweak: (RtlTcpConfig) -> RtlTcpConfig = { it }) =
        RtlTcpClient(tweak(RtlTcpConfig("127.0.0.1", port, retuneSync = RetuneSyncMode.IMMEDIATE)))

    @Test
    fun reportsTunerAndGainsFromTheHeader() {
        FakeRtlTcpServer(tunerType = 6).start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            val gains = (l.info!!.gainModel as GainModel.TunerSteps).tenthsDb
            assertEquals(29, gains.size)
            assertEquals(RtlTcpProtocol.TunerType.R828D, c.dongle!!.tunerType)
            c.close()
            assertTrue(l.stopped.await(5, SECONDS))
        }
    }

    @Test
    fun convertsU8SamplesToFloats() {
        FakeRtlTcpServer(stations = listOf(100_000_000.0 to 0.5), noiseRms = 0.0).start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            val x = l.awaitEpochSamples(epoch = 1, complex = 10_000)
            var meanI = 0.0
            var meanQ = 0.0
            for (k in 0 until 10_000) {
                meanI += x[2 * k]
                meanQ += x[2 * k + 1]
            }
            assertEquals((191 - 127.5) / 127.5, meanI / 10_000, 1e-3)
            assertEquals((128 - 127.5) / 127.5, meanQ / 10_000, 1e-3)
            c.close()
        }
    }

    @Test
    fun sendsInitialSettingsThenCommandsInOrder() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port) { it.copy(initialSampleRate = 2_048_000, initialFrequencyHz = 145_500_000) }
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setGain(GainSetting.TunerTenthsDb(297))
            c.setPpm(-3)
            assertEquals(2, c.setCenterFrequency(433_920_000))
            server.awaitCommands(6)
            assertEquals(
                listOf(0x02 to 2_048_000, 0x01 to 145_500_000, 0x03 to 1, 0x04 to 297, 0x05 to -3, 0x01 to 433_920_000),
                server.commands.toList(),
            )
            c.close()
        }
    }

    @Test
    fun skipsCommandsTheServerDoesNotSupport() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port) { it.copy(supportedCommands = setOf(0x01, 0x02)) }
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setPpm(5)
            c.setCenterFrequency(200_000_000)
            server.awaitCommands(3)
            Thread.sleep(100)
            assertTrue(server.commands.none { it.first == RtlTcpProtocol.SET_FREQ_CORRECTION })
            assertFalse(OptionKind.BIAS_T in l.info!!.options)
            c.close()
        }
    }

    @Test
    fun reportsTheEndOfTheStream() {
        FakeRtlTcpServer(totalSamples = 50_000).start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            assertTrue(l.stopped.await(10, SECONDS))
            assertTrue(l.errors.single() is SourceError.StreamEnded)
            c.close()
        }
    }

    @Test
    fun reportsConnectionFailure() {
        val freePort = ServerSocket(0).use { it.localPort }
        val l = CollectingListener()
        client(freePort).open(l)
        assertTrue(l.stopped.await(10, SECONDS))
        assertTrue(l.errors.single() is SourceError.ConnectFailed)
    }

    @Test
    fun sendsExitBeforeClosingTheAndroidDriver() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port) { it.copy(sendExitOnClose = true, kind = SourceKind.ANDROID_DRIVER) }
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.close()
            server.awaitCommands(3)
            assertEquals(RtlTcpProtocol.ANDROID_EXIT, server.commands.last().first)
        }
    }

    @Test
    fun blocksCarryTheirEpochAndFrequency() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            l.awaitEpochSamples(epoch = 1, complex = 1_000)
            val epoch = c.setCenterFrequency(300_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 1_000)
            assertTrue(l.blocksOf(epoch).all { it.centerHz == 300_000_000L })
            c.close()
        }
    }
}
