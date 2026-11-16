// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.ScriptedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class RadioSessionTest {
    /** A scripted source that also remembers the gain it was given. */
    private class Recording(val inner: ScriptedSource) : IqSource by inner {
        val gains = CopyOnWriteArrayList<GainSetting>()
        override fun setGain(gain: GainSetting) { gains += gain }
    }

    private class Events : SessionListener {
        val states = CopyOnWriteArrayList<SessionState>()
        val messages = CopyOnWriteArrayList<String>()
        @Volatile var status = ReceiverStatus()
        override fun onState(state: SessionState) { states += state }
        override fun onStatus(status: ReceiverStatus) { this.status = status }
        override fun onMessage(text: String) { messages += text }
    }

    private val made = CopyOnWriteArrayList<Recording>()
    private val events = Events()
    private val settings = ReceiverSettings(100_000_000, 240_000, Mode.NFM)
    private val network = SourceSpec.Network("radio.example", 1234)
    private var session: RadioSession? = null

    private fun session(
        backoff: List<Int> = listOf(0),
        giveUpSeconds: Int = 120,
        clock: () -> Long = System::nanoTime,
        factory: SourceFactory = SourceFactory { _, _ -> Recording(ScriptedSource()).also { made += it } },
    ) = RadioSession(CollectingSink(), settings, events, factory, backoff, giveUpSeconds, stableSeconds = 10, nanoTime = clock).also { session = it }

    @After
    fun closeSession() {
        session?.close()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what; states: ${events.states}")
            Thread.sleep(2)
        }
    }

    private fun awaitRunning(count: Int = 1) =
        await("running $count time(s)") { events.states.count { it is SessionState.Running } >= count && events.status.tuned }

    @Test
    fun startsListeningAndStopsAgain() {
        val s = session()
        assertEquals(SessionState.Idle, s.state)
        s.start(SourceSpec.Simulator)
        awaitRunning()
        assertTrue(events.states[0] is SessionState.Connecting)
        assertEquals(SourceSpec.Simulator, (s.state as SessionState.Running).spec)
        s.stop()
        await("idle") { s.state == SessionState.Idle }
        assertEquals("close", made.single().inner.calls.last())
    }

    @Test
    fun settingsChangedWhileListeningAreKeptForTheNextSession() {
        val s = session()
        s.start(SourceSpec.Simulator)
        awaitRunning()
        s.update { it.copy(frequencyHz = 145_500_000) }
        await("the retune") { "freq" in made[0].inner.calls.last() || made[0].inner.calls.any { it.startsWith("freq 1455") || it.startsWith("freq 1454") } }
        s.stop()
        await("idle") { s.state == SessionState.Idle }
        assertEquals(145_500_000, s.settings.frequencyHz)
        s.update { it.copy(volume = 0.25f) }                    // while idle
        s.start(SourceSpec.Simulator)
        awaitRunning(2)
        assertEquals(145_500_000, s.settings.frequencyHz)
        assertEquals(0.25f, s.settings.volume)
        assertTrue("the new source was tuned near the kept frequency: ${made[1].inner.calls}",
            made[1].inner.calls.any { it.startsWith("freq 1454") || it.startsWith("freq 1455") })
    }

    @Test
    fun anInvalidSettingsChangeReachesTheCallerAndChangesNothing() {
        val s = session()
        try {
            s.update { it.copy(volume = 2f) }
            fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1f, s.settings.volume)
    }

    @Test
    fun aLostNetworkSourceIsReconnectedWithTheSameSettingsAndGain() {
        val s = session(backoff = listOf(0))
        s.setGain(GainSetting.TunerTenthsDb(297))
        s.start(network)
        awaitRunning()
        s.update { it.copy(frequencyHz = 145_500_000) }
        made[0].inner.fail(SourceError.StreamEnded("The server closed the connection"))
        awaitRunning(2)
        val waiting = events.states.filterIsInstance<SessionState.Waiting>().single()
        assertEquals(1, waiting.attempt)
        assertEquals("The connection to radio.example:1234 was lost.", waiting.reason)
        assertEquals(2, made.size)
        assertEquals(listOf<GainSetting>(GainSetting.TunerTenthsDb(297)), made[1].gains)
        assertEquals(145_500_000, s.settings.frequencyHz)
    }

    @Test
    fun reconnectingGivesUpAfterTheLimit() {
        val now = AtomicLong(0)
        val s = session(backoff = listOf(0), giveUpSeconds = 120, clock = { now.get() }) { _, _ ->
            Recording(ScriptedSource()).also { made += it; it.inner.fail(SourceError.ConnectFailed("Cannot connect")) }
        }
        s.start(network)
        await("some attempts") { made.size >= 3 }
        now.set(121_000_000_000L)
        await("giving up") { s.state is SessionState.Failed }
        val failed = s.state as SessionState.Failed
        assertTrue(failed.message, failed.message.endsWith("Gave up after trying for 120 seconds."))
        val count = made.size
        Thread.sleep(100)
        assertEquals("no further attempts", count, made.size)
    }

    @Test
    fun aSourceThatIsNotAnRtlTcpServerFailsAtOnce() {
        val s = session { _, _ ->
            Recording(ScriptedSource()).also { made += it; it.inner.fail(SourceError.ProtocolError("Not an rtl_tcp server (unexpected header)")) }
        }
        s.start(network)
        await("failure") { s.state is SessionState.Failed }
        assertEquals(1, made.size)
        assertEquals(
            "radio.example:1234 is not an rtl_tcp or rsp_tcp server. (Not an rtl_tcp server (unexpected header))",
            (s.state as SessionState.Failed).message,
        )
    }

    @Test
    fun theDongleIsNotReconnectedAutomatically() {
        val s = session()
        s.start(SourceSpec.Driver(port = 14423))
        awaitRunning()
        made[0].inner.fail(SourceError.StreamEnded("The server closed the connection"))
        await("failure") { s.state is SessionState.Failed }
        assertEquals("The dongle stopped sending samples. It may have been unplugged.", (s.state as SessionState.Failed).message)
        assertEquals(1, made.size)
    }

    @Test
    fun stoppingWhileWaitingCancelsTheReconnection() {
        val s = session(backoff = listOf(30)) { _, _ ->
            Recording(ScriptedSource()).also { made += it; it.inner.fail(SourceError.ConnectFailed("Cannot connect")) }
        }
        s.start(network)
        await("waiting") { s.state is SessionState.Waiting }
        assertEquals(30, (s.state as SessionState.Waiting).seconds)
        s.stop()
        await("idle") { s.state == SessionState.Idle }
        assertEquals(1, made.size)
    }

    @Test
    fun startingAnotherSourceEndsTheFirst() {
        val s = session()
        s.start(SourceSpec.Simulator)
        awaitRunning()
        s.start(network)
        await("the second source") { (s.state as? SessionState.Running)?.spec == network }
        assertEquals("close", made[0].inner.calls.last())
        assertEquals(2, made.size)
    }

    @Test
    fun aSourceThatCannotBeBuiltIsReportedAsAFailure() {
        val s = session { _, _ -> throw IllegalArgumentException("port 0 is outside 1..65535") }
        s.start(network)
        await("failure") { s.state is SessionState.Failed }
        assertEquals("Cannot use radio.example:1234: port 0 is outside 1..65535", (s.state as SessionState.Failed).message)
    }
}
