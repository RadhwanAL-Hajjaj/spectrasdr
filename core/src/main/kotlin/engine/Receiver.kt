// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlocker
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueue
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceListener
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzer
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The receiver (§4.3). The source's thread hands blocks to a queue that holds about half a second;
 * the receiver's DSP thread takes them out and runs the spectrum analyzer and the listening chain
 * on each. Settings are replaced as a whole through [update] from any thread and take effect on
 * the DSP thread before the next block, so the signal path itself needs no locks.
 *
 * Tuning follows [TuningPolicy]: a new frequency inside the stream only moves the channel; one
 * outside it retunes the source, and the blocks taken before the retune took effect are discarded
 * by their tune epoch (§7.1). A frequency or sample rate the source refuses is not taken: [settings]
 * goes back to the one in force and the refused request is not asked for again until the user
 * repeats it. The stream is the truth for the sample rate: when the blocks taken after the last
 * request carry another rate (a refused first request, a source with a fixed rate), [settings]
 * follows them and the tuner is planned with the real rate.
 *
 * One receiver is one session: [start] it once and [stop] it once. The receiver opens and closes
 * the source.
 */
class Receiver(
    private val source: IqSource,
    private val sink: AudioSink,
    initial: ReceiverSettings,
    private val listener: ReceiverListener,
) {
    /**
     * The settings in force or about to be. Any thread may read it. The receiver itself replaces
     * it when the source refuses a request or the stream has another sample rate.
     */
    @Volatile
    var settings: ReceiverSettings = initial
        private set

    /** The latest status snapshot, published by the DSP thread. Any thread may read it. */
    @Volatile
    var status = ReceiverStatus()
        private set

    private val queue = BlockQueue(8)
    private val errors = ConcurrentLinkedQueue<SourceError>()
    private val started = AtomicBoolean(false)
    private val settingsLock = Any()

    @Volatile private var running = false
    @Volatile private var sourceEnded = false
    @Volatile private var startedInfo: SourceInfo? = null   // from onStarted, until the DSP thread takes it
    @Volatile private var thread: Thread? = null

    // Touched by the source's thread only.
    private var queuedRate = 0
    private var queuedBlockSize = 0

    // Taken from source.info, which may be provisional, until the source has started; from then on
    // from what onStarted reported. Changed by the DSP thread only.
    private var usableFraction = TuningPolicy.usableFraction(source.info.kind)
    private var dcRemovalByDefault = defaultDcRemoval(source.info.kind)
    @Volatile private var analyzer = newAnalyzer(initial)   // replaced by the DSP thread, read by any thread

    // Everything below is touched by the DSP thread only.
    private var applied: ReceiverSettings? = null
    private var wantedCenterHz: Long? = null
    private var refusedCenterHz: Long? = null
    private var requiredEpoch = 0
    private var processedEpoch = -1   // tune epoch of the last block processed
    private var askedRate = 0         // the rate last asked of the source, or the stream's when it delivers another
    private var tuned = false
    private var chain: ReceiverChain? = null
    private var chainRate = 0
    private var chainSettings: ReceiverSettings? = null
    private var dcBlocker: DcBlocker? = null
    private var dcRate = 0
    private var lastRds = NO_RDS
    private var blocks = 0L
    private var staleBlocks = 0L
    private var staleSamples = 0L
    private var sinceStatus = 0L
    private var workNanos = 0L

    /**
     * The FFT size of the frames [spectrum] delivers right now. Any thread may read it; a new
     * [ReceiverSettings.fftSize] shows here once the DSP thread has taken it, at the next block.
     */
    val fftSize: Int get() = analyzer.fftSize

    /**
     * Replaces the settings with [change] applied to the current ones; they take effect on the DSP
     * thread at the next block. Any thread may call it, the DSP thread (a listener callback) too.
     *
     * [change] runs under a lock: it must be quick and must not call back into the receiver. An
     * exception it throws reaches the caller and leaves the settings unchanged; for example an
     * invalid combination, such as a mode change by `copy(mode = …)` that keeps a bandwidth the new
     * mode does not allow; a mode change goes through [ReceiverSettings.withMode].
     */
    fun update(change: (ReceiverSettings) -> ReceiverSettings) {
        synchronized(settingsLock) { settings = change(settings) }
    }

    /**
     * Copies the newest spectrum into [dst] when there is one newer than [dst] holds; returns
     * whether it copied. A frame whose size is not [fftSize] gets nothing. Any thread may call it.
     */
    fun spectrum(dst: SpectrumFrame): Boolean {
        val a = analyzer
        return dst.size == a.fftSize && a.latest(dst)
    }

    /**
     * Starts the DSP thread, which tunes and opens the source and receives. May be called from any
     * thread, once per receiver; it returns at once. Problems are reported through the listener.
     */
    fun start() {
        check(started.compareAndSet(false, true)) { "a Receiver can be started once" }
        running = true
        thread = Thread({ run() }, "receiver-dsp").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stops receiving and closes the source. May be called from any thread, any number of times.
     *
     * It asks the DSP thread to end and waits up to [timeoutMs] for it; true when the thread has
     * ended (the source is closed and [ReceiverListener.onStopped] has been called). When the thread
     * is still busy (stuck in [AudioSink.write] or a listener callback), it closes the source itself
     * from the calling thread and returns false; [ReceiverListener.onStopped] then comes when the DSP
     * thread gets free, still exactly once. So it blocks for up to [timeoutMs] plus the time the
     * source's own close takes: Android code must not call it on the main thread, and a listener
     * callback must not wait for the thread that is calling stop().
     *
     * Called from the DSP thread itself (inside a listener callback) it only asks for the stop and
     * returns true at once; the session ends when the callback has returned.
     */
    fun stop(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        running = false
        val t = thread
        if (t == null || t === Thread.currentThread()) return true
        t.join(timeoutMs)
        if (!t.isAlive) return true
        runCatching { source.close() }   // the DSP thread closes it again when it gets free; sources allow that
        return false
    }

    private val sourceListener = object : SourceListener {
        override fun onStarted(info: SourceInfo) {
            startedInfo = info
        }

        override fun onBlock(block: IqBlock) {
            if (!running) {
                block.release()
                return
            }
            if (block.sampleRate != queuedRate || block.capacity != queuedBlockSize) {
                queuedRate = block.sampleRate
                queuedBlockSize = block.capacity
                queue.capacity = queueCapacity(block.sampleRate, block.capacity)
            }
            queue.offer(block)
        }

        override fun onError(error: SourceError) {
            errors.add(error)
        }

        override fun onStopped() {
            sourceEnded = true
        }
    }

    private fun run() {
        try {
            apply(settings)   // tunes the source before it is opened, so its first blocks are already right
            if (running) source.open(sourceListener)
            while (running) {
                reportErrors()
                val block = queue.take(POLL_MS)
                takeStartedInfo()   // after take: the source reports its start before its first block
                if (block == null) {
                    if (sourceEnded) break
                    continue
                }
                try {
                    process(block)
                } finally {
                    block.release()
                }
            }
        } catch (e: Exception) {
            if (running) errors.add(SourceError.Io("The receiver stopped: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            running = false
            runCatching { source.close() }
            queue.clear()
            reportErrors()
            listener.onStopped()
        }
    }

    /** Takes what the source reported in onStarted: its kind decides the usable fraction and the DC removal default. */
    private fun takeStartedInfo() {
        val info = startedInfo ?: return
        startedInfo = null
        dcRemovalByDefault = defaultDcRemoval(info.kind)
        val fraction = TuningPolicy.usableFraction(info.kind)
        if (fraction != usableFraction) {
            usableFraction = fraction
            analyzer = newAnalyzer(applied ?: settings)
        }
        listener.onSourceStarted(info)
    }

    private fun reportErrors() {
        while (true) listener.onSourceError(errors.poll() ?: return)
    }

    private fun process(block: IqBlock) {
        val began = System.nanoTime()
        var s = settings
        if (s !== applied) s = apply(s)   // what is in force afterwards: a refused frequency or rate is already put back
        if (block.tuneEpoch >= requiredEpoch && block.sampleRate != s.sampleRate) s = followStream(s, block.sampleRate)
        if (block.tuneEpoch < requiredEpoch) {
            staleSamples += block.length
            if (staleSamples < STALE_LIMIT_SECONDS * block.sampleRate) {
                staleBlocks++
                return
            }
            // The source never marked the change. Carrying on beats staying silent for ever.
            listener.onWarning("The source did not confirm the retune; receiving at ${block.centerHz} Hz")
            requiredEpoch = block.tuneEpoch
        }
        staleSamples = 0
        processedEpoch = block.tuneEpoch
        if (!tuned) {
            tuned = true
            chain?.reset()
            analyzer.reset()
            dcBlocker?.reset()
        }
        val rate = block.sampleRate
        val samples = block.samples
        val count = block.length
        if (s.dcRemoval ?: dcRemovalByDefault) {
            val blocker = dcBlocker.takeIf { dcRate == rate } ?: DcBlocker(2, DC_CUTOFF_HZ, rate.toDouble()).also {
                dcBlocker = it
                dcRate = rate
            }
            blocker.process(samples, count)
        }
        analyzer.process(samples, count, rate, block.centerHz)
        val c = chainFor(rate, s)
        val half = TuningPolicy.halfWidthHz(s.mode, s.bandwidthHz, s.cwPitchHz)
        val offset = TuningPolicy.clampOffset((s.frequencyHz - block.centerHz).toDouble(), rate, half)
        if (c != null) {
            if (c.offsetHz != offset) c.offsetHz = offset
            c.noiseDensityDb = analyzer.noiseDensityDb
            c.process(samples, count)   // overwrites the block, so it comes last
        }
        blocks++
        val state = c?.rds?.state ?: NO_RDS
        if (state !== lastRds && state != lastRds) listener.onRds(state)
        lastRds = state
        workNanos += System.nanoTime() - began
        sinceStatus += count
        if (sinceStatus >= rate / STATUS_PER_SECOND) publish(block, c, offset)
    }

    private fun publish(block: IqBlock, c: ReceiverChain?, offsetHz: Double) {
        val seconds = sinceStatus.toDouble() / block.sampleRate
        val now = ReceiverStatus(
            centerHz = block.centerHz,
            sampleRate = block.sampleRate,
            frequencyHz = block.centerHz + offsetHz.roundToLong(),
            tuned = true,
            demodulating = c != null,
            channelPowerDb = c?.channelPowerDb ?: Double.NEGATIVE_INFINITY,
            noiseFloorDb = c?.noiseFloorDb ?: Double.NEGATIVE_INFINITY,
            squelchOpen = c?.squelch?.open ?: false,
            stereo = c?.stereo ?: false,
            rdsSynced = c?.rds?.synced ?: false,
            blocks = blocks,
            staleBlocks = staleBlocks,
            overruns = queue.overruns,
            load = workNanos / (seconds * 1e9),
        )
        sinceStatus = 0
        workNanos = 0
        status = now
        listener.onStatus(now)
    }

    /** Brings the analyzer, the chain's controls and the tuner in line with [s]. */
    private fun apply(s: ReceiverSettings): ReceiverSettings {
        var inForce = s
        val old = applied
        applied = s
        if (old != null && (s.fftSize != old.fftSize || s.window != old.window || s.framesPerSecond != old.framesPerSecond)) {
            analyzer = newAnalyzer(s)
        }
        analyzer.averaging = s.averaging
        analyzer.peakDecayDbPerSecond = s.peakDecayDbPerSecond
        chain?.let { configure(it, s) }

        var moved = false
        var planRate = s.sampleRate
        if (s.sampleRate != askedRate) {
            val inForceRate = askedRate
            askedRate = s.sampleRate
            try {
                if (moves(source.setSampleRate(s.sampleRate))) moved = true
            } catch (e: IllegalArgumentException) {
                listener.onWarning(e.message ?: "The source cannot deliver ${s.sampleRate} samples per second")
                if (old != null) {
                    // Put back, as a refused frequency is below. A refused first request is corrected by the stream.
                    planRate = inForceRate
                    putBack(s, { it.sampleRate == s.sampleRate }) { it.copy(sampleRate = inForceRate) }?.let {
                        inForce = it
                        askedRate = inForceRate
                    }
                }
            }
        }
        if (source.info.canTune) {
            val center = TuningPolicy.plan(s.frequencyHz, wantedCenterHz, planRate, s.mode, s.bandwidthHz, s.cwPitchHz, usableFraction)
            if (center == wantedCenterHz) {
                refusedCenterHz = null
            } else if (center != refusedCenterHz) {
                try {
                    if (moves(source.setCenterFrequency(center))) moved = true
                    wantedCenterHz = center
                    refusedCenterHz = null
                } catch (e: IllegalArgumentException) {
                    refusedCenterHz = center   // not asked again until the plan wants another one
                    listener.onWarning(e.message ?: "The source cannot tune to $center Hz")
                    if (old != null) {
                        // Put back, so the settings no longer hold the refused request and asking for it
                        // again is a new request; only a refused first request has to be remembered.
                        putBack(s, { it.frequencyHz == s.frequencyHz }) { it.copy(frequencyHz = old.frequencyHz) }?.let {
                            inForce = it
                            refusedCenterHz = null
                        }
                    }
                }
            }
        }
        if (moved) {
            if (tuned) {
                tuned = false
                val waiting = status.copy(tuned = false)
                status = waiting
                listener.onStatus(waiting)
            }
        } else if (old != null && inForce.frequencyHz != old.frequencyHz) {
            chain?.resetDecoders()   // another station inside the same stream
        }
        return inForce
    }

    /**
     * Takes the tune epoch a request returned; whether the request moves the stream. A source that
     * ignores a request returns an epoch its blocks already carry.
     */
    private fun moves(epoch: Int): Boolean {
        requiredEpoch = maxOf(requiredEpoch, epoch)
        return epoch > processedEpoch
    }

    /**
     * Replaces the settings with [restore] of the current ones after the request in [refused] could
     * not be carried out, unless the user has asked for something else since ([unchanged] is false
     * for the current settings); returns the settings now in force, or null.
     */
    private inline fun putBack(
        refused: ReceiverSettings,
        unchanged: (ReceiverSettings) -> Boolean,
        restore: (ReceiverSettings) -> ReceiverSettings,
    ): ReceiverSettings? =
        synchronized(settingsLock) {
            val current = settings
            if (!unchanged(current)) return null
            val reverted = restore(current)
            if (current === refused) applied = reverted
            settings = reverted
            reverted
        }

    /**
     * The stream is the truth: blocks taken after the last request carry [rate] where [s] holds
     * another (a refused first request, a source with a fixed rate). The settings follow the
     * stream, unless the user has asked for another rate since, the source is not asked again, and
     * the tuner is planned with the real rate.
     */
    private fun followStream(s: ReceiverSettings, rate: Int): ReceiverSettings {
        askedRate = rate
        val followed = putBack(s, { it.sampleRate == s.sampleRate }) { it.copy(sampleRate = rate) } ?: return s
        return apply(followed)
    }

    /** The chain for blocks at [rate], rebuilt when the rate or a setting that shapes it changed. */
    private fun chainFor(rate: Int, s: ReceiverSettings): ReceiverChain? {
        val built = chainSettings
        if (rate == chainRate && built != null && s.mode == built.mode && s.bandwidthHz == built.bandwidthHz &&
            s.deemphasisSeconds == built.deemphasisSeconds && s.cwPitchHz == built.cwPitchHz
        ) {
            return chain
        }
        chainRate = rate
        chainSettings = s
        chain = if (ReceiverChain.supports(rate, s.mode)) {
            ReceiverChain(rate, s.mode, s.bandwidthHz, sink, s.deemphasisSeconds, s.cwPitchHz).also { configure(it, s) }
        } else {
            listener.onWarning("${s.mode} needs at least ${ReceiverChain.minimumSampleRate(s.mode)} samples per second; the source delivers $rate")
            null
        }
        return chain
    }

    private fun configure(c: ReceiverChain, s: ReceiverSettings) {
        c.squelch.thresholdDb = s.squelchDb
        c.squelch.auto = s.autoSquelch
        c.audio.volume = s.volume
        c.audio.muted = s.muted
        c.stereoEnabled = s.stereo
    }

    private fun newAnalyzer(s: ReceiverSettings) =
        SpectrumAnalyzer(s.fftSize, s.window, s.framesPerSecond.toDouble(), usableFraction = usableFraction)

    companion object {
        private val NO_RDS = RdsState()
        private const val QUEUE_SECONDS = 0.5
        private const val POLL_MS = 50L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val STATUS_PER_SECOND = 10
        private const val DC_CUTOFF_HZ = 30.0
        private const val STALE_LIMIT_SECONDS = 5.0

        /** Blocks of [blockSize] samples that hold about half a second at [sampleRate] (§4.3). */
        fun queueCapacity(sampleRate: Int, blockSize: Int): Int =
            maxOf(2, ceil(QUEUE_SECONDS * sampleRate / blockSize).toInt())

        /** DC removal is on by default for RTL-SDR sources (§6.1). */
        fun defaultDcRemoval(kind: SourceKind): Boolean = kind == SourceKind.RTL_TCP || kind == SourceKind.ANDROID_DRIVER
    }
}
