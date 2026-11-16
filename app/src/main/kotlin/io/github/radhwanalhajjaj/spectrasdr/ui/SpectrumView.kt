// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText
import io.github.radhwanalhajjaj.spectrasdr.core.display.SpectrumScale
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio

/** Holds the newest spectrum frame and the columns it is drawn from; drawing thread only. */
private class SpectrumData {
    var frame = SpectrumFrame(4_096)
    var levels = FloatArray(0)
    var peaks = FloatArray(0)
    var valid = false

    fun poll(radio: Radio): Boolean {
        val size = radio.fftSize
        if (frame.size != size) {
            frame = SpectrumFrame(size)
            valid = false
        }
        if (!radio.spectrum(frame)) return false
        valid = true
        return true
    }

    fun columns(width: Int) {
        val n = minOf(width, frame.size).coerceAtLeast(1)
        if (levels.size != n) {
            levels = FloatArray(n)
            peaks = FloatArray(n)
        }
        SpectrumScale.columns(frame.levelsDb, frame.size, levels)
        SpectrumScale.columns(frame.peakDb, frame.size, peaks)
    }
}

/**
 * The spectrum of the whole stream with the tuned channel marked. Tap to tune there (to the
 * nearest [stepHz]).
 */
@Composable
fun SpectrumView(
    radio: Radio,
    settings: ReceiverSettings,
    stepHz: Int,
    onTune: (Long) -> Unit,
    modifier: Modifier = Modifier,
    topDb: Float = -10f,
    bottomDb: Float = -110f,
) {
    val data = remember { SpectrumData() }
    var drawn by remember { mutableLongStateOf(0L) }
    val tune by rememberUpdatedState(onTune)
    val step by rememberUpdatedState(stepHz)
    val text = rememberTextMeasurer()
    val label = TextStyle(color = Instrument.dim, fontSize = 10.sp)

    LaunchedEffect(radio) {
        while (true) {
            withFrameNanos { }
            if (data.poll(radio)) drawn++
        }
    }

    Canvas(
        modifier = modifier
            .background(Instrument.plot)
            .testTag("spectrum")
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    if (data.valid) {
                        val scale = SpectrumScale(data.frame.centerHz, data.frame.sampleRate)
                        tune(FrequencyText.snap(scale.hzAt(at.x / size.width), step))
                    }
                }
            },
    ) {
        drawn   // read, so that a new frame redraws
        val w = size.width
        val h = size.height
        for (i in 1 until 5) {
            val y = h * i / 5
            drawLine(Instrument.grid, Offset(0f, y), Offset(w, y), 1f)
            drawText(text, "${(topDb + (bottomDb - topDb) * i / 5).toInt()}", Offset(4f, y + 2f), label)
        }
        if (!data.valid || data.frame.sampleRate <= 0) return@Canvas
        val scale = SpectrumScale(data.frame.centerHz, data.frame.sampleRate, topDb, bottomDb)

        for (i in 1 until 4) {
            val x = w * i / 4
            drawLine(Instrument.grid, Offset(x, 0f), Offset(x, h), 1f)
            drawText(text, FrequencyText.short(scale.hzAt(i / 4f)).removeSuffix(" MHz"), Offset(x + 4f, h - 14.dp.toPx()), label)
        }

        val left = scale.xOf(settings.frequencyHz - settings.bandwidthHz / 2.0) * w
        val right = scale.xOf(settings.frequencyHz + settings.bandwidthHz / 2.0) * w
        drawRect(Instrument.channel, Offset(left, 0f), Size((right - left).coerceAtLeast(2f), h))
        val centre = scale.xOf(settings.frequencyHz.toDouble()) * w
        drawLine(Instrument.channelCentre, Offset(centre, 0f), Offset(centre, h), 1.5f)

        data.columns(w.toInt())
        val n = data.levels.size
        val peaks = Path()
        val trace = Path()
        for (c in 0 until n) {
            val x = (c + 0.5f) * w / n
            val yPeak = scale.yOf(data.peaks[c]) * h
            val y = scale.yOf(data.levels[c]) * h
            if (c == 0) {
                peaks.moveTo(x, yPeak)
                trace.moveTo(x, y)
            } else {
                peaks.lineTo(x, yPeak)
                trace.lineTo(x, y)
            }
        }
        drawPath(peaks, Instrument.peak, style = Stroke(1f))
        drawPath(trace, Instrument.trace, style = Stroke(1.5.dp.toPx()))
    }
}
