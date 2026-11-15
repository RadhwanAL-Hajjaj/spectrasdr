// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

/**
 * Turns channel-filtered complex samples into audio (§6.4). The audio leaves at [outputRate] with
 * [channels] interleaved channels; the engine resamples it to the sink's rate afterwards.
 */
interface Demodulator {
    /** Sample rate of the complex input in Hz. */
    val inputRate: Double

    /** Sample rate of the audio output in Hz. */
    val outputRate: Double

    /** Audio channels produced: 1 or 2. */
    val channels: Int

    /** Upper bound of the audio frames produced for [inCount] input samples. */
    fun maxOutput(inCount: Int): Int

    /**
     * Demodulates [inCount] complex samples from [input] (which may be overwritten) and writes
     * interleaved audio frames to [output]; returns the number of frames.
     */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int

    /** Clears all filter and loop state, for use after a retune. */
    fun reset()
}

/** Receives the FM multiplex of a [WfmDemod] beside the audio path; the RDS demodulator is one. */
interface MultiplexTap {
    /**
     * Takes [count] multiplex samples (±1.0 is ±75 kHz of deviation). [pilotSin] and [pilotCos]
     * hold sin θ and cos θ of the pilot phase for each sample (pilot = cos θ); they mean something
     * only while [pilotLocked].
     */
    fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int)

    /** Clears all state, for use after a retune. */
    fun reset()
}
