// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

import io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioSink
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Receiver
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverListener
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ThreadHook
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listening sessions as the app sees them: pick a source, start, change settings, stop. It builds
 * the source and the [Receiver], keeps the user's settings from one session to the next, and
 * reconnects a lost network source with a growing delay, restoring frequency, rate and gain (§11).
 *
 * Every method may be called from any thread and returns at once; opening, stopping and
 * reconnecting happen on the session's own thread, because stopping a receiver can take seconds.
 */
class RadioSession(
    private val sink: AudioSink,
    initialSettings: ReceiverSettings,
    private val listener: SessionListener,
    private val sources: SourceFactory = SourceFactory.Default,
    /** Seconds to wait before each reconnection; the last value repeats. */
    private val backoffSeconds: List<Int> = listOf(1, 2, 4, 8, 15),
    /** Reconnecting stops after this long without a working connection. */
    private val giveUpSeconds: Int = 120,
    /** A connection that lasted this long counts as working again. */
    private val stableSeconds: Int = 10,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Told about each receiver's source and DSP threads, to raise their priority. */
    private val threads: ThreadHook = ThreadHook { },
) : AutoCloseable {
    init {
        require(backoffSeconds.isNotEmpty() && backoffSeconds.all { it >= 0 }) { "back-off needs at least one delay, none negative" }
    }

    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "radio-session").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true                              // a cancelled retry leaves the queue at once
        executeExistingDelayedTasksAfterShutdownPolicy = false   // a pending retry does not keep the thread alive
    }
    private val closed = AtomicBoolean(false)
    private val settingsLock = Any()

    @Volatile private var wanted = initialSettings
    @Volatile private var receiver: Receiver? = null
    @Volatile private var source: IqSource? = null
    @Volatile private var generation = 0

    // What the user set on the source; put back after every (re)connection. Gains are kept one
    // per control: the tuner's gain, and for an RSP its LNA state, IF gain reduction and AGC.
    private val gains = ConcurrentHashMap<String, GainSetting>()
    @Volatile private var ppm: Int? = null
    private val options = ConcurrentHashMap<OptionKind, SourceOption>()

    // Session thread only.
    private var spec: SourceSpec? = null
    private var attempt = 0
    private var failingSinceNs = 0L
    private var failing = false
    private var runningSinceNs = 0L
    private var retry: ScheduledFuture<*>? = null

    /** What the session is doing now. */
    @Volatile
    var state: SessionState = SessionState.Idle
        private set

    /** The settings in force while running, and the ones the next session starts with. */
    val settings: ReceiverSettings get() = receiver?.settings ?: wanted

    /** FFT size of the spectrum frames [spectrum] delivers now. */
    val fftSize: Int get() = receiver?.fftSize ?: wanted.fftSize

    /** Starts listening to [spec], ending whatever ran before. */
    fun start(spec: SourceSpec) {
        post {
            stopNow()
            this.spec = spec
            attempt = 0
            failing = false
            connect()
        }
    }

    /** Stops listening. */
    fun stop() {
        post {
            stopNow()
            spec = null
            publish(SessionState.Idle)
        }
    }

    /**
     * Changes the settings: of the running receiver, and of the sessions that follow. [change]
     * must be quick; an exception it throws (an invalid combination) reaches the caller and
     * changes nothing.
     */
    fun update(change: (ReceiverSettings) -> ReceiverSettings) {
        synchronized(settingsLock) {
            val r = receiver
            if (r != null) {
                r.update(change)
                wanted = r.settings
            } else {
                wanted = change(wanted)
            }
        }
    }

    fun setGain(gain: GainSetting) {
        gains[controlOf(gain)] = gain
        control("gain") { source?.setGain(gain) }
    }

    fun setPpm(ppm: Int) {
        this.ppm = ppm
        control("frequency correction") { source?.setPpm(ppm) }
    }

    fun setOption(option: SourceOption) {
        options[option.kind] = option
        control("option") { source?.setOption(option) }
    }

    /** Copies the newest spectrum frame into [dst] if there is one it has not seen; any thread. */
    fun spectrum(dst: SpectrumFrame): Boolean = receiver?.spectrum(dst) ?: false

    /** Ends the session for good. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        post { stopNow() }
        executor.shutdown()
    }

    /** Runs [block] on the session thread; after [close] it is ignored. */
    private fun post(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Sends one control to the source; a failure becomes a message for the user, not a lost exception. */
    private fun control(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            listener.onMessage("Could not change the $what: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- session thread ----

    private fun connect() {
        val spec = spec ?: return
        val gen = ++generation
        publish(SessionState.Connecting(spec, attempt))
        var src: IqSource? = null
        var r: Receiver? = null
        try {
            val s = sources.create(spec, wanted)
            src = s
            // The settings are read where the receiver is published, so a change made while the
            // source was being built is neither lost nor overwritten.
            r = synchronized(settingsLock) {
                Receiver(s, sink, wanted, Events(gen, spec, s), threads).also {
                    source = s
                    receiver = it
                }
            }
            r.start()
        } catch (e: RuntimeException) {
            r?.let { forget(it) }
            runCatching { src?.close() }
            publish(SessionState.Failed(spec, "Cannot use ${spec.label}: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** Stops the receiver, if any, and forgets it; later events from it are ignored. */
    private fun stopNow() {
        retry?.cancel(false)
        retry = null
        generation++
        val r = receiver ?: return
        r.stop()
        forget(r)
    }

    private fun forget(r: Receiver) {
        synchronized(settingsLock) {
            if (receiver === r) {
                wanted = r.settings
                receiver = null
                source = null
            }
        }
    }

    private fun started(spec: SourceSpec, src: IqSource, info: SourceInfo) {
        runningSinceNs = nanoTime()
        for (g in gains.values) if (fits(g, info.gainModel)) control("gain") { src.setGain(g) }
        ppm?.let { p -> control("frequency correction") { src.setPpm(p) } }
        for (o in options.values) if (o.kind in info.options) control("option") { src.setOption(o) }
        publish(SessionState.Running(spec, info))
    }

    private fun ended(spec: SourceSpec, r: Receiver, error: SourceError?) {
        forget(r)
        if (error == null) {
            publish(SessionState.Idle)
            return
        }
        val reason = SessionMessages.describe(error, spec)
        if (!SessionMessages.canRetry(error, spec)) {
            publish(SessionState.Failed(spec, reason))
            return
        }
        val now = nanoTime()
        val wasStable = state is SessionState.Running && now - runningSinceNs >= stableSeconds * 1_000_000_000L
        if (!failing || wasStable) {
            failing = true
            failingSinceNs = now
            attempt = 0
        }
        if (now - failingSinceNs >= giveUpSeconds * 1_000_000_000L) {
            publish(SessionState.Failed(spec, "$reason Gave up after trying for $giveUpSeconds seconds."))
            return
        }
        val seconds = backoffSeconds[minOf(attempt, backoffSeconds.lastIndex)]
        attempt++
        publish(SessionState.Waiting(spec, attempt, seconds, reason))
        retry = executor.schedule({ retry = null; connect() }, seconds.toLong(), TimeUnit.SECONDS)
    }

    private fun fits(gain: GainSetting, model: GainModel): Boolean = when (model) {
        is GainModel.TunerSteps -> gain is GainSetting.Auto || gain is GainSetting.TunerTenthsDb || gain is GainSetting.TunerIndex
        is GainModel.Rsp -> gain is GainSetting.RspLna || gain is GainSetting.RspIfgr || gain is GainSetting.RspAgc
        GainModel.None -> false
    }

    private fun controlOf(gain: GainSetting): String = when (gain) {
        is GainSetting.Auto, is GainSetting.TunerTenthsDb, is GainSetting.TunerIndex -> "tuner"
        is GainSetting.RspLna -> "lna"
        is GainSetting.RspIfgr -> "ifgr"
        is GainSetting.RspAgc -> "agc"
    }

    private fun publish(next: SessionState) {
        state = next
        listener.onState(next)
    }

    /** One receiver's events; anything from a receiver that is no longer the current one is dropped. */
    private inner class Events(private val gen: Int, private val spec: SourceSpec, private val src: IqSource) : ReceiverListener {
        @Volatile private var error: SourceError? = null

        private val current get() = gen == generation

        override fun onSourceStarted(info: SourceInfo) {
            post { if (current) started(spec, src, info) }
        }

        override fun onStatus(status: ReceiverStatus) {
            if (current) listener.onStatus(status)
        }

        override fun onRds(state: RdsState) {
            if (current) listener.onRds(state)
        }

        override fun onWarning(message: String) {
            if (current) listener.onMessage(message)
        }

        override fun onSourceError(error: SourceError) {
            this.error = error
        }

        override fun onStopped() {
            val r = receiver
            post { if (current && r != null) ended(spec, r, error) }
        }
    }
}
