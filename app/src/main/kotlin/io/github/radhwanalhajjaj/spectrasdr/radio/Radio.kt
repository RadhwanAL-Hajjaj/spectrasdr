// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.content.Context
import io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRing
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.session.RadioSession
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionListener
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's radio: one [RadioSession] with its audio output, and its state as flows for the
 * screens. It lives as long as the process; [RadioService] keeps the process alive while it plays.
 */
class Radio(private val context: Context) : SessionListener {
    private val store = SettingsStore(context)

    val ring = AudioRing()
    val player = AudioPlayer(ring)
    private val session = RadioSession(ring, store.loadSettings(), this)

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _status = MutableStateFlow(ReceiverStatus())
    private val _rds = MutableStateFlow(RdsState())
    private val _settings = MutableStateFlow(session.settings)
    private val _gain = MutableStateFlow<GainSetting>(GainSetting.Auto)
    private val _rspGain = MutableStateFlow(RspGain())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val state: StateFlow<SessionState> get() = _state
    val status: StateFlow<ReceiverStatus> get() = _status
    val rds: StateFlow<RdsState> get() = _rds
    val settings: StateFlow<ReceiverSettings> get() = _settings
    /** The tuner gain of an RTL-SDR: automatic or one of its steps. */
    val gain: StateFlow<GainSetting> get() = _gain

    /** The gain controls of an SDRplay receiver. */
    val rspGain: StateFlow<RspGain> get() = _rspGain
    val messages: SharedFlow<String> get() = _messages

    /** The source used last, to offer it again. */
    var lastSpec: SourceSpec = store.loadSpec()
        private set

    val fftSize: Int get() = session.fftSize

    fun start(spec: SourceSpec) {
        lastSpec = spec
        store.saveSpec(spec)
        update { it.copy(sampleRate = store.loadRate(spec)) }
        RadioService.start(context)
        player.start()
        session.start(spec)
    }

    fun stop() {
        session.stop()
    }

    /** Changes the receiver's settings; an invalid change is ignored and reported as a message. */
    fun update(change: (ReceiverSettings) -> ReceiverSettings) {
        try {
            session.update(change)
        } catch (e: IllegalArgumentException) {
            _messages.tryEmit(e.message ?: "That setting is not possible")
            return
        }
        publishSettings()
    }

    fun setMode(mode: Mode) = update { it.withMode(mode) }

    fun setGain(gain: GainSetting) {
        _gain.value = gain
        session.setGain(gain)
    }

    fun setRspGain(gain: RspGain) {
        val before = _rspGain.value
        _rspGain.value = gain
        if (gain.agc != before.agc) session.setGain(GainSetting.RspAgc(gain.agc))
        if (gain.lnaState != before.lnaState) session.setGain(GainSetting.RspLna(gain.lnaState))
        if (gain.ifGainReductionDb != before.ifGainReductionDb) session.setGain(GainSetting.RspIfgr(gain.ifGainReductionDb))
    }

    /** Changes the sample rate and remembers it for this kind of source. */
    fun setSampleRate(rate: Int) {
        update { it.copy(sampleRate = rate) }
        store.saveRate(lastSpec, rate)
    }

    fun setPaused(paused: Boolean) = player.setPaused(paused)

    fun spectrum(dst: SpectrumFrame): Boolean = session.spectrum(dst)

    override fun onState(state: SessionState) {
        _state.value = state
        if (state is SessionState.Idle || state is SessionState.Failed) {
            player.stop()
            _status.value = ReceiverStatus()
            _rds.value = RdsState()
        }
    }

    override fun onStatus(status: ReceiverStatus) {
        _status.value = status
        publishSettings()   // the receiver changes them itself when a source refuses a request
    }

    override fun onRds(state: RdsState) {
        _rds.value = state
    }

    override fun onMessage(text: String) {
        _messages.tryEmit(text)
    }

    private fun publishSettings() {
        val now = session.settings
        if (now != _settings.value) {
            _settings.value = now
            store.saveSettings(now)
        }
    }

    companion object {
        const val DEFAULT_RATE = 2_400_000

        /** The simulator makes its band in real time; a modest rate keeps it light on slow devices. */
        const val SIMULATOR_RATE = 960_000
    }
}

/** SDRplay gain: automatic gain control, the LNA state (0 = most gain) and the IF gain reduction in dB. */
data class RspGain(val agc: Boolean = true, val lnaState: Int = 0, val ifGainReductionDb: Int = 40)
