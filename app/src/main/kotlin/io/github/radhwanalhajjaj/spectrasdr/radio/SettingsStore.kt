// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.content.Context
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec

/** Remembers the last frequency, mode, levels and source between launches. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    fun loadSettings(): ReceiverSettings {
        val fallback = ReceiverSettings(frequencyHz = 100_300_000, sampleRate = Radio.DEFAULT_RATE)
        return runCatching {
            val mode = Mode.valueOf(prefs.getString("mode", Mode.WFM.name)!!)
            ReceiverSettings(
                frequencyHz = prefs.getLong("frequencyHz", fallback.frequencyHz),
                sampleRate = prefs.getInt("sampleRate", fallback.sampleRate),
                mode = mode,
                bandwidthHz = prefs.getInt("bandwidthHz", mode.defaultBandwidthHz),
                squelchDb = prefs.getFloat("squelchDb", Float.NEGATIVE_INFINITY).toDouble(),
                autoSquelch = prefs.getBoolean("autoSquelch", false),
                volume = prefs.getFloat("volume", 1f),
            )
        }.getOrDefault(fallback)
    }

    fun saveSettings(s: ReceiverSettings) {
        prefs.edit()
            .putLong("frequencyHz", s.frequencyHz)
            .putInt("sampleRate", s.sampleRate)
            .putString("mode", s.mode.name)
            .putInt("bandwidthHz", s.bandwidthHz)
            .putFloat("squelchDb", s.squelchDb.toFloat())
            .putBoolean("autoSquelch", s.autoSquelch)
            .putFloat("volume", s.volume)
            .apply()
    }

    fun loadSpec(): SourceSpec = when (prefs.getString("source", "simulator")) {
        "network" -> runCatching {
            SourceSpec.Network(prefs.getString("host", "")!!, prefs.getInt("port", 1234))
        }.getOrDefault(SourceSpec.Simulator)
        "driver" -> SourceSpec.Driver(1234)
        else -> SourceSpec.Simulator
    }

    fun saveSpec(spec: SourceSpec) {
        val e = prefs.edit()
        when (spec) {
            SourceSpec.Simulator -> e.putString("source", "simulator")
            is SourceSpec.Network -> e.putString("source", "network").putString("host", spec.host).putInt("port", spec.port)
            is SourceSpec.Driver -> e.putString("source", "driver")
        }
        e.apply()
    }
}
