// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.ScriptedSource
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class ReceiverTest {
    private class Events : ReceiverListener {
        val statuses = CopyOnWriteArrayList<ReceiverStatus>()
        val rds = CopyOnWriteArrayList<RdsState>()
        val warnings = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<SourceError>()
        val order = CopyOnWriteArrayList<String>()
        val stopped = CountDownLatch(1)
        val stops = AtomicInteger()

        override fun onStatus(status: ReceiverStatus) {
            statuses += status
        }

        override fun onRds(state: RdsState) {
            rds += state
        }

        override fun onWarning(message: String) {
            warnings += message
        }

        override fun onSourceError(error: SourceError) {
            errors += error
            order += "error"
        }

        override fun onStopped() {
            order += "stopped"
            stops.incrementAndGet()
            stopped.countDown()
        }
    }

    private var receiver: Receiver? = null

    @After
    fun stopReceiver() {
        receiver?.stop()
    }

    private fun start(source: ScriptedSource, settings: ReceiverSettings, sink: CollectingSink = CollectingSink()): Pair<Receiver, Events> {
        val events = Events()
        val r = Receiver(source, sink, settings, events)
        receiver = r
        r.start()
        return r to events
    }

    /** Waits until [condition] holds; fails with [what] after 20 s. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    private val station = SimStation.FmBroadcast(100_000_000, "TEST FM ", "Receiver test", 0xD3A1, 1_000.0, 0.0)

    @Test
    fun tunesTheSourceBeforeOpeningItAndPlaysTheStation() {
        val source = ScriptedSource(listOf(station))
        val sink = CollectingSink()
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000), sink)
        await("the station name") { events.rds.lastOrNull()?.ps == "TEST FM " }
        assertEquals(listOf("rate 240000", "freq 100000000", "open"), source.calls.take(3))
        await("a full status") { r.status.rdsSynced && r.status.stereo }
        val status = r.status
        assertTrue(status.tuned && status.demodulating && status.squelchOpen)
        assertEquals(100_000_000L, status.centerHz)
        assertEquals(100_000_000L, status.frequencyHz)
        assertEquals(240_000, status.sampleRate)
        assertEquals("a station at 0.2 of full scale", -14.0, status.channelPowerDb, 1.5)
        assertEquals(0L, status.staleBlocks)
        assertEquals(0L, status.overruns)
        assertTrue("load ${status.load}", status.load > 0.0)
        assertEquals(0xD3A1, events.rds.last().pi)
        r.stop()
        assertEquals(2, sink.channels)
        val audio = sink.audio()
        val frames = sink.frames
        val left = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        val right = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1)
        assertTrue("left $left, right $right", left > 0.3 && right < left / 10)
    }

    @Test
    fun aFrequencyInsideTheStreamOnlyMovesTheChannel() {
        val source = ScriptedSource(
            listOf(SimStation.NarrowFm(100_300_000, pauseSeconds = 0.0), SimStation.Am(100_200_000)),
            initialSampleRate = 960_000,
        )
        val sink = CollectingSink()
        val (r, events) = start(source, ReceiverSettings(100_300_000, 960_000, Mode.NFM), sink)
        await("the first status") { r.status.tuned }
        assertEquals("a narrow channel sits a quarter of the rate above the tuner", 100_060_000L, r.status.centerHz)
        r.update { it.withMode(Mode.AM).copy(frequencyHz = 100_200_000) }
        await("the new channel") { r.status.frequencyHz == 100_200_000L }
        val before = sink.frames
        await("two seconds of AM audio") { sink.frames >= before + 96_000 }
        r.stop()
        assertEquals(1, source.calls.count { it.startsWith("freq") })
        assertTrue(events.statuses.none { it.blocks > 0 && !it.tuned })
        assertEquals(100_060_000L, r.status.centerHz)
        val audio = sink.audio()
        assertEquals(800.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size - 24_000, audio.size), 2.0)
    }

    @Test
    fun aFrequencyOutsideTheStreamRetunesAndDiscardsWhatWasTakenBefore() {
        val source = ScriptedSource(listOf(station), pipelineBlocks = 5)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the station name") { events.rds.lastOrNull()?.ps == "TEST FM " }
        r.update { it.copy(frequencyHz = 101_000_000) }
        await("the new frequency") { r.status.tuned && r.status.centerHz == 101_000_000L }
        assertEquals("the block in hand and the five in the pipeline", 6L, r.status.staleBlocks)
        assertEquals(listOf("freq 100000000", "freq 101000000"), source.calls.filter { it.startsWith("freq") })
        assertTrue("a status announces the retune", events.statuses.any { !it.tuned && it.centerHz == 100_000_000L })
        assertEquals("the station's data is cleared", "", events.rds.last().ps)
        val frame = SpectrumFrame(4_096)
        await("a spectrum of the new frequency") { r.spectrum(frame) && frame.centerHz == 101_000_000L }
    }

    @Test
    fun aSourceThatCannotTuneIsLimitedToItsBand() {
        val source = ScriptedSource(canTune = false, canSetRate = false, kind = SourceKind.IQ_FILE)
        val (r, _) = start(source, ReceiverSettings(100_500_000, 240_000, Mode.NFM))
        await("the first status") { r.status.tuned }
        assertEquals(100_000_000L, r.status.centerHz)
        assertEquals("120 kHz minus half the channel above the centre", 100_113_750L, r.status.frequencyHz)
        assertTrue(source.calls.none { it.startsWith("freq") })
        r.update { it.copy(frequencyHz = 100_050_000) }
        await("a frequency inside the band") { r.status.frequencyHz == 100_050_000L }
    }

    @Test
    fun aRefusedFrequencyIsReportedAndTheReceiverCarriesOn() {
        val source = ScriptedSource(maxFrequencyHz = 1_000_000_000)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.update { it.copy(frequencyHz = 2_000_000_000) }
        await("the warning") { events.warnings.isNotEmpty() }
        assertTrue(events.warnings.single(), events.warnings.single().contains("2000000000"))
        val blocks = r.status.blocks
        await("more blocks") { r.status.blocks > blocks + 10 }
        assertEquals(100_000_000L, r.status.centerHz)
        assertTrue(events.errors.isEmpty())
    }

    @Test
    fun aSourceErrorIsReportedAndEndsTheSession() {
        val source = ScriptedSource()
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        source.fail(SourceError.StreamEnded("The server closed the connection"))
        assertTrue(events.stopped.await(10, SECONDS))
        assertEquals("The server closed the connection", events.errors.single().message)
        assertEquals(listOf("error", "stopped"), events.order.toList())
        assertEquals("close", source.calls.last())
    }

    @Test
    fun stopClosesTheSourceAndReportsOnce() {
        val source = ScriptedSource()
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.stop()
        r.stop()
        assertEquals(1, events.stops.get())
        assertEquals(1, source.calls.count { it == "close" })
        assertTrue(events.errors.isEmpty())
        try {
            r.start()
            org.junit.Assert.fail("a second start() should throw")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aRateTooLowForTheModeStillShowsTheSpectrum() {
        val source = ScriptedSource(initialSampleRate = 48_000, canSetRate = false)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 48_000))
        await("the first status") { r.status.tuned }
        assertFalse(r.status.demodulating)
        assertTrue(events.warnings.single(), events.warnings.single().contains("WFM needs at least 120000"))
        val frame = SpectrumFrame(4_096)
        await("a spectrum") { r.spectrum(frame) }
        assertEquals(48_000, frame.sampleRate)
        r.update { it.withMode(Mode.NFM) }
        await("narrow FM") { r.status.demodulating }
    }

    @Test
    fun volumeMuteAndSquelchReachTheAudio() {
        val source = ScriptedSource(listOf(SimStation.Am(100_060_000)), initialSampleRate = 240_000)
        val sink = CollectingSink()
        val (r, _) = start(source, ReceiverSettings(100_060_000, 240_000, Mode.AM), sink)
        await("audio") { sink.frames > 48_000 }
        val audio = sink.audio()
        assertTrue(Signals.peak(audio, audio.size - 4_800, audio.size) > 0.2f)

        r.update { it.copy(muted = true) }
        await("silence") { sink.audio().let { a -> Signals.peak(a, a.size - 4_800, a.size) == 0f } }
        r.update { it.copy(muted = false, squelchDb = -10.0) }      // the carrier is at −26 dBFS
        await("a closed squelch") { !r.status.squelchOpen && r.status.blocks > 0 }
        await("silence") { sink.audio().let { a -> Signals.peak(a, a.size - 4_800, a.size) == 0f } }
        r.update { it.copy(squelchDb = -40.0) }
        await("an open squelch") { r.status.squelchOpen }
        assertEquals(-26.0, r.status.channelPowerDb, 1.5)
    }

    @Test
    fun automaticSquelchOpensOnASignalAndClosesOnAnEmptyChannel() {
        val source = ScriptedSource(listOf(SimStation.NarrowFm(100_300_000, pauseSeconds = 0.0)), initialSampleRate = 960_000, noiseLevel = 0.002f)
        val (r, _) = start(source, ReceiverSettings(100_300_000, 960_000, Mode.NFM, autoSquelch = true))
        await("an open squelch") { r.status.tuned && r.status.squelchOpen && r.status.noiseFloorDb > -200.0 }
        // Noise of 0.002 per component is −51 dBFS over 960 kHz; the channel filter passes 14.1 kHz of it.
        assertEquals(-69.3, r.status.noiseFloorDb, 2.0)
        assertEquals(-26.0, r.status.channelPowerDb, 1.0)
        r.update { it.copy(frequencyHz = 100_200_000) }
        await("a closed squelch") { r.status.frequencyHz == 100_200_000L && !r.status.squelchOpen }
        assertEquals(r.status.noiseFloorDb, r.status.channelPowerDb, 2.5)
    }

    @Test
    fun spectrumFollowsTheFftSizeAndDcRemoval() {
        val source = ScriptedSource(dcOffset = 0.1f, kind = SourceKind.RTL_TCP)
        val (r, _) = start(source, ReceiverSettings(100_000_000, 240_000, dcRemoval = false))
        val frame = SpectrumFrame(4_096)
        await("a spectrum") { r.spectrum(frame) }
        assertEquals("the DC offset stands in the centre", -20f, frame.levelsDb[2_048], 0.5f)
        r.update { it.copy(dcRemoval = null, fftSize = 1_024, averaging = 1.0) }   // null: on, because the source is an RTL-SDR
        await("the new FFT size") { r.fftSize == 1_024 }
        assertFalse("a frame of the old size gets nothing", r.spectrum(frame))
        val small = SpectrumFrame(1_024)
        await("a spectrum without the offset") { r.spectrum(small) && small.levelsDb[512] < -60f }
        assertTrue(Receiver.defaultDcRemoval(SourceKind.RTL_TCP))
        assertTrue(Receiver.defaultDcRemoval(SourceKind.ANDROID_DRIVER))
        assertFalse(Receiver.defaultDcRemoval(SourceKind.RSP_TCP))
    }

    @Test
    fun aNewSampleRateIsAskedOfTheSourceAndFollowed() {
        val source = ScriptedSource(listOf(station), pipelineBlocks = 2)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.update { it.copy(sampleRate = 480_000) }
        await("the new rate") { r.status.tuned && r.status.sampleRate == 480_000 }
        assertTrue(source.calls.contains("rate 480000"))
        assertEquals("the tuner did not have to move", 1, source.calls.count { it.startsWith("freq") })
        assertEquals(3L, r.status.staleBlocks)
        await("the station name at the new rate") { events.rds.lastOrNull()?.ps == "TEST FM " }
    }

    @Test
    fun aSourceThatNeverConfirmsARetuneDoesNotSilenceTheReceiverForEver() {
        val source = ScriptedSource(pipelineBlocks = Int.MAX_VALUE / 2)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.update { it.copy(frequencyHz = 101_000_000) }
        await("the warning") { events.warnings.isNotEmpty() }
        assertTrue(events.warnings.single(), events.warnings.single().contains("did not confirm"))
        await("reception at the old frequency") { r.status.tuned }
        assertEquals(100_000_000L, r.status.centerHz)
        // Just under five seconds of 4,096-sample blocks at 240 kS/s were discarded
        assertEquals(292L, r.status.staleBlocks)
        assertEquals("the channel is limited to the stream it is in", 100_020_000L, r.status.frequencyHz)
    }

    @Test
    fun theQueueHoldsAboutHalfASecond() {
        assertEquals(74, Receiver.queueCapacity(2_400_000, 16_384))
        assertEquals(8, Receiver.queueCapacity(240_000, 16_384))
        assertEquals(2, Receiver.queueCapacity(48_000, 16_384))
    }

    @Test
    fun settingsAreValidated() {
        val bad = listOf<() -> ReceiverSettings>(
            { ReceiverSettings(-1, 240_000) },
            { ReceiverSettings(100_000_000, 0) },
            { ReceiverSettings(100_000_000, 240_000, Mode.NFM, bandwidthHz = 200_000) },
            { ReceiverSettings(100_000_000, 240_000, volume = 1.5f) },
            { ReceiverSettings(100_000_000, 240_000, fftSize = 3_000) },
            { ReceiverSettings(100_000_000, 240_000, framesPerSecond = 5) },
            { ReceiverSettings(100_000_000, 240_000, averaging = 0.0) },
            { ReceiverSettings(100_000_000, 240_000, cwPitchHz = 50.0) },
            { ReceiverSettings(100_000_000, 240_000, squelchDb = Double.NaN) },
        )
        for ((i, make) in bad.withIndex()) {
            try {
                make()
                org.junit.Assert.fail("settings #$i should be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(12_500, ReceiverSettings(100_000_000, 240_000).withMode(Mode.NFM).bandwidthHz)
    }
}
