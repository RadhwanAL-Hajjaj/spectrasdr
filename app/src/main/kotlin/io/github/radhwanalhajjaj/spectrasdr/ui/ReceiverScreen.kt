// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio
import io.github.radhwanalhajjaj.spectrasdr.radio.RspGain
import kotlin.math.roundToInt

/** Tuning step when tapping the spectrum, per mode. */
private fun stepOf(mode: Mode): Int = when (mode) {
    Mode.WFM -> 100_000
    Mode.NFM -> 12_500
    Mode.AM -> 5_000
    else -> 1_000
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(radio: Radio) {
    val state by radio.state.collectAsState()
    val status by radio.status.collectAsState()
    val rds by radio.rds.collectAsState()
    val settings by radio.settings.collectAsState()
    val gain by radio.gain.collectAsState()
    val rspGain by radio.rspGain.collectAsState()
    val stopReason by radio.stopReason.collectAsState()
    var typing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var choosing by remember { mutableStateOf(false) }
    var driverMissing by remember { mutableStateOf(false) }
    var driverRefusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(radio) { radio.messages.collect { snackbar.showSnackbar(it) } }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val begin: (SourceSpec) -> Unit = { spec ->
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        radio.start(spec)
    }

    val startDriver = rememberDriverStarter(radio) { outcome ->
        when (outcome) {
            is DriverOutcome.Started -> begin(outcome.spec)
            is DriverOutcome.Refused -> driverRefusal = outcome.message
            DriverOutcome.NotInstalled -> driverMissing = true
        }
    }

    val active = state !is SessionState.Idle && state !is SessionState.Failed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpectraSDR") },
                actions = {
                    TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("source")) {
                        Text(sourceLabel(state, radio.lastSpec), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            StateLine(state, status, stopReason)
            FrequencyReadout(
                frequencyHz = settings.frequencyHz,
                onChange = { hz -> radio.update { it.copy(frequencyHz = hz) } },
                onType = { typing = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            ModeRow(settings, onMode = radio::setMode, onBandwidth = { bw -> radio.update { it.copy(bandwidthHz = bw) } })
            SpectrumView(
                radio = radio,
                settings = settings,
                stepHz = stepOf(settings.mode),
                onTune = { hz -> radio.update { it.copy(frequencyHz = hz) } },
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(vertical = 6.dp),
            )
            Meter(status)
            RdsStrip(rds, status)
            Levels(settings, state, gain, rspGain, radio)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (active) {
                    OutlinedButton(onClick = radio::stop, modifier = Modifier.testTag("stop")) { Text("Stop") }
                } else {
                    Button(onClick = { choosing = true }, modifier = Modifier.testTag("listen")) { Text("Listen") }
                }
            }
        }
    }

    if (typing) {
        FrequencyDialog(
            current = settings.frequencyHz,
            onDismiss = { typing = false },
            onDone = { hz ->
                typing = false
                radio.update { it.copy(frequencyHz = hz) }
            },
        )
    }
    if (choosing) {
        SourceDialog(
            last = radio.lastSpec,
            onDismiss = { choosing = false },
            onStart = { choice ->
                choosing = false
                when (choice) {
                    SourceChoice.Simulator -> begin(SourceSpec.Simulator)
                    is SourceChoice.Server -> begin(SourceSpec.Network(choice.host, choice.port))
                    SourceChoice.Dongle -> startDriver()
                }
            },
        )
    }
    if (driverMissing) {
        AlertDialog(
            onDismissRequest = { driverMissing = false },
            title = { Text("SDR driver needed") },
            text = { Text("To use a USB dongle, install the free \"SDR driver\" app. It talks to the dongle; SpectraSDR does the rest.") },
            confirmButton = {
                TextButton(onClick = {
                    driverMissing = false
                    try {
                        context.startActivity(DriverHandOff.storeIntent())
                    } catch (_: ActivityNotFoundException) {
                        driverRefusal = "No app store was found on this device. Search for \"SDR driver\" by SignalWare."
                    }
                }) { Text("Open the store") }
            },
            dismissButton = { TextButton(onClick = { driverMissing = false }) { Text("Not now") } },
        )
    }
    driverRefusal?.let { message ->
        AlertDialog(
            onDismissRequest = { driverRefusal = null },
            title = { Text("The dongle did not start") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { driverRefusal = null }) { Text("OK") } },
        )
    }
}

private fun sourceLabel(state: SessionState, last: SourceSpec): String = when (state) {
    is SessionState.Connecting -> state.spec.label
    is SessionState.Running -> state.spec.label
    is SessionState.Waiting -> state.spec.label
    is SessionState.Failed -> state.spec.label
    SessionState.Idle -> "Source: ${last.label}"
}

@Composable
private fun StateLine(state: SessionState, status: ReceiverStatus, stopReason: String?) {
    val text = when (state) {
        // The reasons say "Stopped: …" or "Audio output failed …" themselves.
        SessionState.Idle -> stopReason ?: "Stopped"
        is SessionState.Connecting -> if (state.attempt == 0) "Connecting to ${state.spec.label}…" else "Reconnecting to ${state.spec.label}…"
        is SessionState.Running -> {
            val rate = "${FrequencyText.fixed(status.sampleRate / 1e6, 2)} MS/s"
            val load = "load ${(status.load * 100).roundToInt()} %"
            val lost = if (status.overruns > 0) " · ${status.overruns} blocks lost" else ""
            "${state.info.name} · $rate · $load$lost"
        }
        is SessionState.Waiting -> "${state.reason} Trying again in ${state.seconds} s."
        is SessionState.Failed -> state.message
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is SessionState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("state"),
    )
}

@Composable
private fun ModeRow(settings: ReceiverSettings, onMode: (Mode) -> Unit, onBandwidth: (Int) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in Mode.entries) {
            FilterChip(
                selected = settings.mode == mode,
                onClick = { onMode(mode) },
                label = { Text(mode.name) },
                modifier = Modifier.testTag("mode-${mode.name}"),
            )
        }
    }
    if (settings.mode.bandwidthsHz.size > 1) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (bw in settings.mode.bandwidthsHz) {
                FilterChip(
                    selected = settings.bandwidthHz == bw,
                    onClick = { onBandwidth(bw) },
                    label = { Text(bandwidthLabel(bw)) },
                )
            }
        }
    }
}

private fun bandwidthLabel(hz: Int): String =
    if (hz >= 1_000) "${(hz / 1_000.0).toString().removeSuffix(".0")} kHz" else "$hz Hz"

/** Signal level of the tuned channel in dBFS, with the squelch state. */
@Composable
private fun Meter(status: ReceiverStatus) {
    val db = status.channelPowerDb
    val fraction = if (db.isFinite()) ((db + 100) / 100).coerceIn(0.0, 1.0).toFloat() else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.weight(1f).height(8.dp).background(Instrument.grid)) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(8.dp).background(if (status.squelchOpen) Instrument.good else Instrument.dim))
        }
        Text(
            if (db.isFinite()) "${FrequencyText.fixed(db, 0)} dBFS" else "— dBFS",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(84.dp).padding(start = 8.dp).testTag("level"),
        )
    }
}

@Composable
private fun RdsStrip(rds: RdsState, status: ReceiverStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            rds.ps.trim().ifEmpty { "········" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleLarge,
            color = if (rds.ps.isBlank()) Instrument.dim else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("rds-name"),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(rds.rt.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("rds-text"))
            if (rds.pi >= 0) Text("PI " + rds.pi.toString(16).uppercase().padStart(4, '0'),style = MaterialTheme.typography.labelSmall, color = Instrument.dim)
        }
        if (status.stereo) {
            Text("STEREO", style = MaterialTheme.typography.labelMedium, color = Instrument.good, modifier = Modifier.testTag("stereo"))
        }
    }
}

@Composable
private fun Levels(settings: ReceiverSettings, state: SessionState, gain: GainSetting, rspGain: RspGain, radio: Radio) {
    LabelledSlider("Volume", "${(settings.volume * 100).roundToInt()} %", settings.volume, 0f..1f) { v ->
        radio.update { it.copy(volume = v) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auto squelch", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = settings.autoSquelch, onCheckedChange = { on -> radio.update { it.copy(autoSquelch = on) } }, modifier = Modifier.testTag("auto-squelch"))
    }
    if (!settings.autoSquelch) {
        val level = if (settings.squelchDb.isFinite()) settings.squelchDb.toFloat() else -101f
        LabelledSlider("Squelch", if (level <= -101f) "open" else "${level.roundToInt()} dBFS", level, -101f..0f) { v ->
            radio.update { it.copy(squelchDb = if (v <= -100.5f) Double.NEGATIVE_INFINITY else v.roundToInt().toDouble()) }
        }
    }
    val model = (state as? SessionState.Running)?.info?.gainModel
    if (model is GainModel.TunerSteps && model.tenthsDb.isNotEmpty()) {
        val auto = gain is GainSetting.Auto
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic gain", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = auto,
                onCheckedChange = { on -> radio.setGain(if (on) GainSetting.Auto else GainSetting.TunerIndex(model.tenthsDb.size / 2)) },
                modifier = Modifier.testTag("auto-gain"),
            )
        }
        if (!auto) {
            val index = ((gain as? GainSetting.TunerIndex)?.index ?: (model.tenthsDb.size / 2)).coerceIn(0, model.tenthsDb.lastIndex)
            LabelledSlider(
                "Gain", "${FrequencyText.fixed(model.tenthsDb[index] / 10.0, 1)} dB", index.toFloat(), 0f..model.tenthsDb.lastIndex.toFloat(),
                steps = (model.tenthsDb.size - 2).coerceAtLeast(0),
            ) { v -> radio.setGain(GainSetting.TunerIndex(v.roundToInt())) }
        }
    }
    if (model is GainModel.Rsp) {
        if (model.agc) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic gain control", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = rspGain.agc, onCheckedChange = { on -> radio.setRspGain(rspGain.copy(agc = on)) })
            }
        }
        if (model.lnaStates > 1) {
            LabelledSlider(
                "LNA state", "${rspGain.lnaState}", rspGain.lnaState.toFloat(), 0f..(model.lnaStates - 1).toFloat(),
                steps = (model.lnaStates - 2).coerceAtLeast(0),
            ) { v -> radio.setRspGain(rspGain.copy(lnaState = v.roundToInt())) }
        }
        if (!rspGain.agc || !model.agc) {
            val reduction = rspGain.ifGainReductionDb.coerceIn(model.ifgrMin, model.ifgrMax)
            LabelledSlider("IF gain reduction", "$reduction dB", reduction.toFloat(), model.ifgrMin.toFloat()..model.ifgrMax.toFloat()) { v ->
                radio.setRspGain(rspGain.copy(ifGainReductionDb = v.roundToInt()))
            }
        }
    }
    val rates = (state as? SessionState.Running)?.info?.sampleRates.orEmpty()
    if (rates.size > 1) {
        Text("Sample rate", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rate in rates) {
                FilterChip(
                    selected = settings.sampleRate == rate,
                    onClick = { radio.setSampleRate(rate) },
                    label = { Text("${FrequencyText.fixed(rate / 1e6, 2).trimEnd('0').trimEnd('.')} MS/s") },
                    modifier = Modifier.testTag("rate-$rate"),
                )
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Slider(value = position, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
