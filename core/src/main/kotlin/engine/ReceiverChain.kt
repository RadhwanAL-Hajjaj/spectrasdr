// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.demod.AmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.CwDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.Demodulator
import io.github.radhwanalhajjaj.spectrasdr.core.demod.NfmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.Sideband
import io.github.radhwanalhajjaj.spectrasdr.core.demod.Squelch
import io.github.radhwanalhajjaj.spectrasdr.core.demod.SsbDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.WfmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsDecoder
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsDemodulator
import kotlin.math.log10

/**
 * The single-threaded listening path for one mode at one sample rate: channelizer, demodulator,
 * squelch gate and audio output. The [Receiver] builds a new chain whenever the mode, bandwidth
 * or sample rate changes; the in-app benchmark runs chains directly.
 */
class ReceiverChain(
    val sampleRate: Int,
    val mode: Mode,
    val bandwidthHz: Int,
    sink: AudioSink,
    val deemphasisSeconds: Double = 50e-6,
    val cwPitchHz: Double = 700.0,
) {
    init {
        require(supports(sampleRate, mode)) { "$mode needs at least ${minimumSampleRate(mode)} samples per second, got $sampleRate" }
    }

    private val channelizer = Channelizer.forMode(sampleRate.toDouble(), mode, bandwidthHz, cwPitchHz)
    private val channelRate = channelizer.outputRate

    /** The RDS decoder, present in WFM when the channel rate can carry the 57 kHz subcarrier. */
    val rds: RdsDecoder? = if (mode == Mode.WFM && channelRate >= 125_000.0) RdsDecoder() else null

    private val demod: Demodulator = when (mode) {
        Mode.WFM -> WfmDemod(channelRate, deemphasisSeconds).also { if (rds != null) it.tap = RdsDemodulator(channelRate, rds) }
        Mode.NFM -> NfmDemod(channelRate, deviationHz = minOf(5_000.0, 0.4 * bandwidthHz))
        Mode.AM -> AmDemod(channelRate)
        Mode.USB -> SsbDemod(channelRate, Sideband.USB)
        Mode.LSB -> SsbDemod(channelRate, Sideband.LSB)
        Mode.CW -> CwDemod(channelRate, cwPitchHz, bandwidthHz.toDouble())
    }

    val squelch = Squelch()
    val audio = AudioOutput(sink, demod.outputRate, demod.channels)

    // The spectrum cannot tell the floor when the channel takes up most of what it sees.
    private val floorKnown = channelizer.noiseBandwidthHz <= 0.5 * sampleRate
    private val noiseBandwidthDb = 10 * log10(channelizer.noiseBandwidthHz)
    private var channel = FloatArray(0)
    private var pcm = FloatArray(0)

    /**
     * Offset of the tuned frequency from the centre of the IQ stream, in Hz. In CW the channel is
     * placed one pitch below it, so the carrier is heard at the pitch.
     */
    var offsetHz = 0.0
        set(value) {
            field = value
            channelizer.offsetHz = if (mode == Mode.CW) value - cwPitchHz else value
        }

    /**
     * Noise floor of the IQ stream in dBFS per hertz, as the spectrum analyzer estimates it; the
     * automatic squelch needs it. Negative infinity (unknown) keeps the automatic squelch open.
     */
    var noiseDensityDb = Double.NEGATIVE_INFINITY

    /** Noise power expected in the empty channel in dBFS; negative infinity while unknown. */
    val noiseFloorDb: Double get() = if (floorKnown) noiseDensityDb + noiseBandwidthDb else Double.NEGATIVE_INFINITY

    init {
        offsetHz = 0.0
    }

    /** Channel power of the last block in dBFS. */
    val channelPowerDb: Double get() = channelizer.powerDb

    /** True while broadcast FM is decoded in stereo. */
    val stereo: Boolean get() = (demod as? WfmDemod)?.stereo ?: false

    /** False forces broadcast FM to mono. */
    var stereoEnabled = true
        set(value) {
            field = value
            (demod as? WfmDemod)?.stereoEnabled = value
        }

    /** Processes one block of [count] complex samples; [samples] is overwritten. */
    fun process(samples: FloatArray, count: Int) {
        val needChannel = 2 * channelizer.maxOutput(count)
        if (channel.size < needChannel) channel = FloatArray(needChannel)
        val n = channelizer.process(samples, count, channel)
        squelch.noiseFloorDb = noiseFloorDb
        squelch.update(channelizer.powerDb)
        val needPcm = demod.channels * demod.maxOutput(n)
        if (pcm.size < needPcm) pcm = FloatArray(needPcm)
        val frames = demod.process(channel, n, pcm)
        squelch.gate(pcm, frames, demod.channels, demod.outputRate)
        audio.write(pcm, frames)
    }

    /** Clears filter, loop and decoder state, for use after the tuner moved. */
    fun reset() {
        channelizer.reset()
        demod.reset()
        rds?.reset()
    }

    /** Forgets what was decoded from the station, for use after tuning to another one inside the stream. */
    fun resetDecoders() {
        rds?.reset()
    }

    companion object {
        /** The lowest sample rate [mode] can be demodulated from. */
        fun minimumSampleRate(mode: Mode): Int = if (mode == Mode.WFM) 120_000 else 48_000

        fun supports(sampleRate: Int, mode: Mode): Boolean = sampleRate >= minimumSampleRate(mode)
    }
}
