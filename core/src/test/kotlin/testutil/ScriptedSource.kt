// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBand
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockPool
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A source for receiver tests. It hands over one block at a time and waits until that block has
 * been released, so the receiver sees every block and counts are exact. Retunes and rate changes
 * requested while it streams take effect [pipelineBlocks] blocks later, and those blocks still
 * carry the old epoch, frequency and rate, like samples waiting in a driver's buffers.
 */
class ScriptedSource(
    private val stations: List<SimStation> = emptyList(),
    initialCenterHz: Long = 100_000_000,
    initialSampleRate: Int = 240_000,
    private val canTune: Boolean = true,
    private val canSetRate: Boolean = true,
    private val pipelineBlocks: Int = 0,
    /** Frequencies above this are refused with an IllegalArgumentException. */
    private val maxFrequencyHz: Long = Long.MAX_VALUE,
    kind: SourceKind = SourceKind.SIMULATOR,
    private val blockSize: Int = 4_096,
    private val noiseLevel: Float = 0.0005f,
    /** Added to the in-phase samples, like a tuner's DC offset. */
    private val dcOffset: Float = 0f,
    /** The kind [info] shows from [open] on and `onStarted` reports, like a network source after its handshake; null keeps [kind]. */
    private val startedKind: SourceKind? = null,
) : IqSource {
    @Volatile
    override var info = SourceInfo("Scripted", kind, SampleFormat.S16LE, canTune, listOf(initialSampleRate), GainModel.None, emptySet())
        private set

    /** Everything asked of the source, in order: "rate 240000", "freq 100000000", "open", "close". */
    val calls = CopyOnWriteArrayList<String>()

    private class Change(val atBlock: Long, val centerHz: Long, val rate: Int, val epoch: Int)

    private val lock = Any()
    private val pending = ArrayDeque<Change>()
    private var requestedCenterHz = initialCenterHz
    private var requestedRate = initialSampleRate
    private var requestedEpoch = 0
    private var emitted = 0L

    @Volatile private var running = false
    @Volatile private var failure: SourceError? = null
    private var thread: Thread? = null

    override val centerFrequencyHz: Long get() = synchronized(lock) { requestedCenterHz }

    override fun open(listener: SourceListener) {
        calls += "open"
        check(thread == null) { "already open" }
        running = true
        thread = Thread({ run(listener) }, "scripted-source").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        calls += "close"
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    }

    override fun setCenterFrequency(hz: Long): Int {
        calls += "freq $hz"
        require(hz in 0..maxFrequencyHz) { "Frequency $hz Hz is outside 0..$maxFrequencyHz Hz" }
        return synchronized(lock) {
            if (!canTune) return requestedEpoch
            requestedCenterHz = hz
            change()
        }
    }

    override fun setSampleRate(sps: Int): Int {
        calls += "rate $sps"
        return synchronized(lock) {
            if (!canSetRate) return requestedEpoch
            requestedRate = sps
            change()
        }
    }

    private fun change(): Int {
        requestedEpoch++
        pending.addLast(Change(if (running) emitted + pipelineBlocks else 0, requestedCenterHz, requestedRate, requestedEpoch))
        return requestedEpoch
    }

    override fun setGain(gain: GainSetting) {}

    override fun setPpm(ppm: Int) {}

    override fun setOption(option: SourceOption) {}

    /** Reports [error] and ends the stream, as a lost connection would. */
    fun fail(error: SourceError) {
        failure = error
    }

    private fun run(listener: SourceListener) {
        val pool = BlockPool(blockSize, preallocate = 1)
        val random = Random(5)
        var centerHz = 0L
        var rate = 0
        var epoch = 0
        var band: SimBand? = null
        var seq = 0L
        synchronized(lock) {
            centerHz = requestedCenterHz
            rate = requestedRate
            epoch = requestedEpoch
            pending.clear()   // everything asked before the stream started is already in force
        }
        try {
            startedKind?.let { info = info.copy(kind = it) }
            listener.onStarted(info)
            while (running) {
                val error = failure
                if (error != null) {
                    listener.onError(error)
                    break
                }
                synchronized(lock) {
                    while (pending.isNotEmpty() && pending.first().atBlock <= emitted) {
                        val c = pending.removeFirst()
                        centerHz = c.centerHz
                        epoch = c.epoch
                        if (c.rate != rate) {
                            rate = c.rate
                            band = null
                        }
                    }
                    emitted++
                }
                val renderer = band ?: SimBand(stations, rate, blockSize).also { band = it }
                val block = pool.obtain()
                val out = block.samples
                for (n in 0 until blockSize) {
                    out[2 * n] = noiseLevel * random.nextGaussian().toFloat() + dcOffset
                    out[2 * n + 1] = noiseLevel * random.nextGaussian().toFloat()
                }
                renderer.render(out, blockSize, centerHz)
                block.length = blockSize
                block.seq = seq++
                block.centerHz = centerHz
                block.sampleRate = rate
                block.tuneEpoch = epoch
                listener.onBlock(block)
                while (running && pool.freeCount < pool.allocated) Thread.sleep(0, 100_000)   // until it is released
            }
        } finally {
            running = false
            listener.onStopped()
        }
    }
}
