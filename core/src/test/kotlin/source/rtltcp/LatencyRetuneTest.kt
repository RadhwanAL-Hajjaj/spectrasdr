// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyRetuneTest {
    private val rate = 1_000_000.0
    private val stationA = 100_000_000.0 + 200_000
    private val stationB = 105_000_000.0 - 317_000
    private val caps = RspCapabilities(1, 0x80, 255, RspCapabilities.FORMAT_S16, 1, "", 0, 1, 20, 59)

    private fun awaitProbe(c: RtlTcpClient, count: Int = 1) {
        val deadline = System.currentTimeMillis() + 20_000
        while (c.probeCount < count) {
            check(System.currentTimeMillis() < deadline) { "latency probe did not run" }
            Thread.sleep(10)
        }
    }

    private fun client(port: Int) = RtlTcpClient(RtlTcpConfig("127.0.0.1", port, initialSampleRate = rate.toInt()))

    private fun assertCleanRetune(c: RtlTcpClient, l: CollectingListener) {
        val epoch = c.setCenterFrequency(105_000_000)
        l.awaitEpochSamples(epoch = epoch, complex = 100_000)
        for (blk in l.blocksOf(epoch)) {
            assertTrue("ghost of A in block ${blk.seq}", Signals.complexToneDb(blk.samples, 200_000.0, rate) < -40)
            assertTrue("B missing in block ${blk.seq}", Signals.complexToneDb(blk.samples, -317_000.0, rate) > -15)
        }
    }

    @Test
    fun measuresLatencyOnRspTcpAndRetunesCleanly() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            sampleRate = rate,
            pipelineDelaySamples = 200_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3, stationB to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            awaitProbe(c)
            assertEquals(RetuneSyncMode.LATENCY, c.syncMode)
            val latency = c.retuneLatencySamples!!
            assertTrue("latency $latency", latency in 200_000L..300_000L)
            assertCleanRetune(c, l)
            c.close()
        }
    }

    @Test
    fun fallsBackToLatencyWhenTestModeIsIgnored() {
        FakeRtlTcpServer(
            sampleRate = rate,
            supportsTestMode = false,
            pipelineDelaySamples = 100_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3, stationB to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            awaitProbe(c)
            assertEquals(RetuneSyncMode.LATENCY, c.syncMode)
            assertTrue(c.retuneLatencySamples!! in 100_000L..200_000L)
            assertCleanRetune(c, l)
            c.close()
        }
    }

    @Test
    fun recalibrateRunsAnotherProbe() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            sampleRate = rate,
            pipelineDelaySamples = 50_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 10_000)
            val c = client(s.port)
            c.open(l)
            awaitProbe(c, 1)
            c.recalibrate()
            awaitProbe(c, 2)
            assertTrue(c.retuneLatencySamples!! in 50_000L..150_000L)
            c.close()
        }
    }

    @Test
    fun preOpenRetunesDoNotTriggerTheFallback() {
        FakeRtlTcpServer().start().use { s ->
            val l = CollectingListener(keepPerEpoch = 10_000)
            val c = RtlTcpClient(RtlTcpConfig("127.0.0.1", s.port))
            c.setCenterFrequency(101_000_000)
            c.setCenterFrequency(102_000_000)
            c.open(l)
            val deadline = System.currentTimeMillis() + 5_000
            while (l.blocks.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("no block arrived", l.blocks.isNotEmpty())
            assertEquals(RetuneSyncMode.MARKER, c.syncMode)
            assertEquals(0, c.markerTimeouts)
            assertTrue("errors: ${l.errors}", l.errors.isEmpty())
            c.close()
        }
    }

    @Test
    fun probeRestoresRspGainWhenOnlyTheLnaWasSet() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            sampleRate = rate,
            pipelineDelaySamples = 50_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 10_000)
            val c = client(s.port)
            c.setGain(GainSetting.RspLna(3))
            c.open(l)
            awaitProbe(c, 1)
            val epoch = c.setCenterFrequency(100_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 10_000)
            assertEquals(0.0, s.currentGainDb, 0.0)
            c.close()
        }
    }

    @Test
    fun recalibrateIsANoOpForMarkerClients() {
        FakeRtlTcpServer().start().use { s ->
            val l = CollectingListener(keepPerEpoch = 10_000)
            val c = RtlTcpClient(RtlTcpConfig("127.0.0.1", s.port))
            c.open(l)
            l.awaitEpochSamples(epoch = 1, complex = 10_000)
            c.recalibrate()
            val epoch = c.setCenterFrequency(101_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 10_000)
            assertEquals(RetuneSyncMode.MARKER, c.syncMode)
            assertEquals(0, c.probeCount)
            c.close()
        }
    }
}
