// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/** What a source is and can do; drives which controls the UI shows and which commands are sent. */
data class SourceInfo(
    val name: String,
    val kind: SourceKind,
    val sampleFormat: SampleFormat,
    val canTune: Boolean,
    val sampleRates: List<Int>,
    val gainModel: GainModel,
    val options: Set<OptionKind>,
)

enum class SourceKind { RTL_TCP, RSP_TCP, ANDROID_DRIVER, IQ_FILE, SIMULATOR }

/** How a source expresses gain. */
sealed interface GainModel {
    /** Tuner gain steps in tenths of a dB (rtl_tcp), plus automatic gain. */
    data class TunerSteps(val tenthsDb: List<Int>) : GainModel

    /** SDRplay: LNA states 0 until [lnaStates], IF gain reduction [ifgrMin]..[ifgrMax] dB, optional AGC. */
    data class Rsp(val lnaStates: Int, val ifgrMin: Int, val ifgrMax: Int, val agc: Boolean) : GainModel

    data object None : GainModel
}

sealed interface GainSetting {
    data object Auto : GainSetting
    data class TunerTenthsDb(val tenthsDb: Int) : GainSetting
    data class TunerIndex(val index: Int) : GainSetting
    data class RspLna(val state: Int) : GainSetting
    data class RspIfgr(val db: Int) : GainSetting
    data class RspAgc(val enabled: Boolean, val setpointDbfs: Int = -30) : GainSetting
}

enum class OptionKind { BIAS_T, DIRECT_SAMPLING, OFFSET_TUNING, RSP_ANTENNA, RSP_NOTCHES }

sealed interface SourceOption {
    val kind: OptionKind

    data class BiasT(val enabled: Boolean) : SourceOption {
        override val kind get() = OptionKind.BIAS_T
    }

    /** 0 = off, 1 = I branch, 2 = Q branch. */
    data class DirectSampling(val mode: Int) : SourceOption {
        override val kind get() = OptionKind.DIRECT_SAMPLING
    }

    data class OffsetTuning(val enabled: Boolean) : SourceOption {
        override val kind get() = OptionKind.OFFSET_TUNING
    }

    /** 0 = A, 1 = B, 2 = Hi-Z. */
    data class RspAntenna(val port: Int) : SourceOption {
        override val kind get() = OptionKind.RSP_ANTENNA
    }

    /** Bit mask: AM 1, broadcast FM 2, DAB 4, RF 8. */
    data class RspNotches(val mask: Int) : SourceOption {
        override val kind get() = OptionKind.RSP_NOTCHES
    }
}

/** Plain-language source failures (§11). */
sealed class SourceError(val message: String) {
    class ConnectFailed(message: String) : SourceError(message)
    class ProtocolError(message: String) : SourceError(message)
    class StreamEnded(message: String) : SourceError(message)
    class DriverRejected(val code: Int, message: String) : SourceError(message)
    class DriverNotInstalled : SourceError("The SDR driver app is not installed")
    class Io(message: String) : SourceError(message)
}

interface SourceListener {
    fun onStarted(info: SourceInfo)

    /** Runs on the source thread. The listener owns [block] and must release it. */
    fun onBlock(block: IqBlock)

    fun onError(error: SourceError)

    fun onStopped()
}

/**
 * A stream of IQ samples produced on the source's own thread. [setCenterFrequency] returns a tune
 * epoch E: blocks with tuneEpoch ≥ E are at the requested frequency or a later one (§7.1).
 */
interface IqSource {
    val info: SourceInfo
    val centerFrequencyHz: Long

    fun open(listener: SourceListener)

    fun close()

    fun setCenterFrequency(hz: Long): Int

    fun setSampleRate(sps: Int)

    fun setGain(gain: GainSetting)

    fun setPpm(ppm: Int)

    fun setOption(option: SourceOption)

    /** Re-measures retune latency where a source needs it (§7.1 step 3). Default: nothing to do. */
    fun recalibrate() {}
}
