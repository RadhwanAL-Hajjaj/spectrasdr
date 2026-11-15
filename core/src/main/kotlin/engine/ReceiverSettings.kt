// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowType
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind

/**
 * Everything the user can set on the receiver. Immutable: a change is a copy, handed to
 * [Receiver.update], and takes effect on the DSP thread at the next block.
 */
data class ReceiverSettings(
    /** The frequency listened to, in Hz. The receiver decides where the tuner goes. */
    val frequencyHz: Long,
    /** IQ sample rate asked of the source. The receiver puts it back to the stream's rate when the source refuses or ignores the request. */
    val sampleRate: Int,
    val mode: Mode = Mode.WFM,
    val bandwidthHz: Int = mode.defaultBandwidthHz,
    /** Squelch threshold in dBFS; negative infinity leaves the squelch open. Ignored while [autoSquelch]. */
    val squelchDb: Double = Double.NEGATIVE_INFINITY,
    val autoSquelch: Boolean = false,
    /** Linear volume, 0–1. */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** False forces broadcast FM to mono. */
    val stereo: Boolean = true,
    /** Broadcast FM de-emphasis: 50 µs, or 75 µs in the Americas and South Korea. */
    val deemphasisSeconds: Double = 50e-6,
    val cwPitchHz: Double = 700.0,
    /** Removes the DC offset from the IQ stream (§6.1). Null leaves it to the source: on for RTL-SDR, off otherwise. */
    val dcRemoval: Boolean? = null,
    val fftSize: Int = 4_096,
    val window: WindowType = WindowType.BLACKMAN_HARRIS,
    val framesPerSecond: Int = 30,
    /** Weight of the newest spectrum frame in the average, 0.1–1. */
    val averaging: Double = 0.5,
    val peakDecayDbPerSecond: Double = 20.0,
) {
    init {
        require(frequencyHz >= 0) { "frequency must not be negative" }
        require(sampleRate > 0) { "sample rate must be positive" }
        require(bandwidthHz in mode.minBandwidthHz..mode.maxBandwidthHz) {
            "$mode bandwidth must be ${mode.minBandwidthHz}–${mode.maxBandwidthHz} Hz, got $bandwidthHz"
        }
        require(!squelchDb.isNaN()) { "squelch threshold must be a number" }
        require(volume in 0f..1f) { "volume must be 0–1" }
        require(deemphasisSeconds in 10e-6..200e-6) { "de-emphasis must be 10–200 µs" }
        require(cwPitchHz in 300.0..1_200.0) { "CW pitch must be 300–1,200 Hz" }
        require(fftSize in 1_024..65_536 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two from 1,024 to 65,536" }
        require(framesPerSecond in 10..60) { "frame rate must be 10–60 per second" }
        require(averaging in 0.1..1.0) { "averaging must be 0.1–1" }
        require(peakDecayDbPerSecond >= 0) { "peak decay must not be negative" }
    }

    /** These settings in another [mode], with that mode's default bandwidth. */
    fun withMode(mode: Mode): ReceiverSettings = copy(mode = mode, bandwidthHz = mode.defaultBandwidthHz)
}

/** A snapshot of what the receiver is doing, published about ten times per second of signal. */
data class ReceiverStatus(
    /** Tuner frequency of the samples being processed, in Hz. */
    val centerHz: Long = 0,
    /** Their sample rate. */
    val sampleRate: Int = 0,
    /** The frequency actually listened to; differs from the request when a recording's band ends before it. */
    val frequencyHz: Long = 0,
    /** False from the moment the tuner is told to move until samples from the new frequency arrive. */
    val tuned: Boolean = false,
    /** False when the sample rate is too low for the mode: the spectrum still runs. */
    val demodulating: Boolean = false,
    /** Channel power in dBFS (the S-meter). */
    val channelPowerDb: Double = Double.NEGATIVE_INFINITY,
    /** Noise expected in the empty channel in dBFS; negative infinity while unknown. */
    val noiseFloorDb: Double = Double.NEGATIVE_INFINITY,
    val squelchOpen: Boolean = false,
    val stereo: Boolean = false,
    val rdsSynced: Boolean = false,
    /** Blocks processed since the start. */
    val blocks: Long = 0,
    /** Blocks discarded because they were taken before a retune had taken effect. */
    val staleBlocks: Long = 0,
    /** Blocks dropped because the DSP thread fell behind (§11). */
    val overruns: Long = 0,
    /** Share of real time the DSP thread needed lately: 0.25 means four times faster than real time. */
    val load: Double = 0.0,
)

/**
 * Receiver events. Every callback runs on the receiver's DSP thread and must return quickly: audio
 * and spectrum stop while it runs. A callback may call [Receiver.update] and [Receiver.stop] (which
 * then only asks for the stop and returns at once).
 */
interface ReceiverListener {
    /**
     * The source has started streaming; called once per session, before any [onStatus] that reports
     * a processed block. From here on [info] (kind, sample rates, gain model, options) is final for
     * the session.
     */
    fun onSourceStarted(info: SourceInfo) {}

    fun onStatus(status: ReceiverStatus) {}

    /** The decoded RDS data changed; an empty [RdsState] means there is none (any more). */
    fun onRds(state: RdsState) {}

    /**
     * A request could not be carried out (for example a frequency the source cannot tune); the receiver carries on.
     * A refused frequency or sample rate is not taken: [Receiver.settings] goes back to the one in force.
     */
    fun onWarning(message: String) {}

    /** The source failed; [onStopped] follows. */
    fun onSourceError(error: SourceError) {}

    /** The receiver has ended, by [Receiver.stop] or because its source did. Called exactly once. */
    fun onStopped() {}
}
