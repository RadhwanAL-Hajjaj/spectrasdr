// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerRetuneTest {
    private val rate = 1_000_000.0
    private val stationA = 100_000_000.0 + 200_000   // +200 kHz when tuned to 100 MHz
    private val stationB = 105_000_000.0 - 317_000   // −317 kHz when tuned to 105 MHz
    private val stationC = 110_000_000.0 + 123_000   // +123 kHz when tuned to 110 MHz

    private fun server(delay: Int) = FakeRtlTcpServer(
        sampleRate = rate,
        pipelineDelaySamples = delay,
        noiseRms = 0.01,
        stations = listOf(stationA to 0.3, stationB to 0.3, stationC to 0.3),
    ).start()

    private fun client(port: Int) = RtlTcpClient(RtlTcpConfig("127.0.0.1", port, initialSampleRate = rate.toInt()))

    @Test
    fun noGhostsAfterARetuneDespiteALongPipeline() {
        server(delay = 300_000).use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            val before = l.awaitEpochSamples(epoch = 1, complex = 50_000)
            assertEquals(RetuneSyncMode.MARKER, c.syncMode)
            assertTrue(Signals.complexToneDb(before, 200_000.0, rate) > -15)
            val epoch = c.setCenterFrequency(105_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 150_000)
            for (blk in l.blocksOf(epoch)) {
                assertTrue("ghost of A in block ${blk.seq}", Signals.complexToneDb(blk.samples, 200_000.0, rate) < -40)
                assertTrue("B missing in block ${blk.seq}", Signals.complexToneDb(blk.samples, -317_000.0, rate) > -15)
            }
            c.close()
        }
    }

    @Test
    fun backToBackRetunesLandOnTheLastFrequency() {
        server(delay = 200_000).use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            l.awaitEpochSamples(epoch = 1, complex = 10_000)
            c.setCenterFrequency(105_000_000)
            val epoch = c.setCenterFrequency(110_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 100_000)
            for (blk in l.blocksOf(epoch)) {
                assertTrue(Signals.complexToneDb(blk.samples, 123_000.0, rate) > -15)
                assertTrue(Signals.complexToneDb(blk.samples, -317_000.0, rate) < -40)
                assertTrue(Signals.complexToneDb(blk.samples, 200_000.0, rate) < -40)
            }
            c.close()
        }
    }
}
