// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Receiver
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverListener
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class SimulatorSourceTest {
    private val carrier = listOf(SimStation.Am(100_100_000, depth = 0.0, level = 0.1f))
    private val unpaced = 1e9

    @Test
    fun anEmptyBandIsNoiseAtTheRequestedLevel() {
        val source = SimulatorSource(emptyList(), speed = unpaced, noiseLevel = 0.002f)
        val l = CollectingListener()
        source.open(l)
        val iq = l.awaitEpochSamples(epoch = 0, complex = 65_536)
        source.close()
        assertEquals("0.002 of full scale is −54 dBFS", -54.0, Signals.complexPowerDb(iq), 0.3)
    }

    @Test
    fun streamsTheBandAroundItsFrequency() {
        val source = SimulatorSource(carrier, speed = unpaced, noiseLevel = 0.002f)
        assertEquals(SourceKind.SIMULATOR, source.info.kind)
        assertTrue(source.info.canTune)
        assertEquals(100_000_000L, source.centerFrequencyHz)
        val l = CollectingListener()
        source.open(l)
        val iq = l.awaitEpochSamples(epoch = 0, complex = 65_536)
        source.close()
        assertTrue(l.stopped.await(5, SECONDS))
        val first = l.blocks.first()
        assertEquals(100_000_000L, first.centerHz)
        assertEquals(960_000, first.sampleRate)
        assertEquals(2 * 16_384, first.samples.size)
        assertEquals(-20.0, Signals.complexToneDb(iq, 100_000.0, 960_000.0), 0.1)
        assertTrue(l.errors.isEmpty())
    }

    @Test
    fun retunesTakeEffectAtABlockBoundary() {
        val source = SimulatorSource(carrier, speed = unpaced)
        val l = CollectingListener()
        source.open(l)
        l.awaitEpochSamples(epoch = 0, complex = 16_384)
        val epoch = source.setCenterFrequency(100_050_000)
        assertEquals(1, epoch)
        val iq = l.awaitEpochSamples(epoch = epoch, complex = 32_768)
        source.close()
        assertEquals(100_050_000L, source.centerFrequencyHz)
        assertTrue(l.blocksOf(0).all { it.centerHz == 100_000_000L })
        assertTrue(l.blocksOf(epoch).all { it.centerHz == 100_050_000L })
        assertEquals("the carrier is now 50 kHz above the centre", -20.0, Signals.complexToneDb(iq, 50_000.0, 960_000.0), 0.1)
    }

    @Test
    fun sampleRateChangesAreMarkedByAnEpochToo() {
        val source = SimulatorSource(carrier, speed = unpaced)
        val l = CollectingListener()
        source.open(l)
        l.awaitEpochSamples(epoch = 0, complex = 16_384)
        val epoch = source.setSampleRate(240_000)
        val iq = l.awaitEpochSamples(epoch = epoch, complex = 32_768)
        source.close()
        assertTrue(l.blocksOf(0).all { it.sampleRate == 960_000 })
        assertTrue(l.blocksOf(epoch).all { it.sampleRate == 240_000 })
        assertEquals(-20.0, Signals.complexToneDb(iq, 100_000.0, 240_000.0), 0.1)
    }

    @Test
    fun neverRunsAheadOfTheClock() {
        for (speed in doubleArrayOf(1.0, 4.0)) {
            val source = SimulatorSource(carrier, initialSampleRate = 240_000, speed = speed)
            val l = CollectingListener(keepPerEpoch = 0)
            val began = System.nanoTime()
            source.open(l)
            Thread.sleep(400)
            val samples = l.totalComplex.get()
            val seconds = (System.nanoTime() - began) / 1e9
            source.close()
            assertTrue("speed $speed: $samples samples in $seconds s", samples <= speed * 240_000 * seconds + 2 * 16_384)
            assertTrue("speed $speed: the stream runs", samples > 0)
        }
    }

    @Test
    fun rejectsWhatItCannotSimulate() {
        val source = SimulatorSource(carrier)
        for (bad in listOf<() -> Unit>(
            { source.setSampleRate(10_000) },
            { source.setSampleRate(10_000_000) },
            { source.setCenterFrequency(-1) },
            { SimulatorSource(carrier, initialSampleRate = 0) },
            { SimulatorSource(carrier, speed = 0.0) },
        )) {
            try {
                bad()
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(1, source.setSampleRate(1_024_000))
    }

    @Test
    fun theDefaultBandPlaysThroughTheReceiverWithStationNames() {
        val names = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener = object : ReceiverListener {
            override fun onRds(state: RdsState) {
                names += state.ps
            }
        }
        val source = SimulatorSource(speed = 4.0)
        val receiver = Receiver(source, CollectingSink(), ReceiverSettings(100_300_000, 960_000), listener)
        receiver.start()
        try {
            fun await(what: String, condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 30_000
                while (!condition()) {
                    check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; names so far: $names" }
                    Thread.sleep(5)
                }
            }
            await("the first station") { names.lastOrNull() == "DEMO FM " }
            assertTrue(receiver.status.stereo)
            assertEquals(100_300_000L, receiver.status.centerHz)
            receiver.update { it.copy(frequencyHz = 99_500_000) }
            await("the second station") { names.lastOrNull() == "SPECTRA1" }
            assertEquals(99_500_000L, receiver.status.centerHz)
            assertTrue(receiver.status.squelchOpen)
        } finally {
            receiver.stop()
        }
    }
}
