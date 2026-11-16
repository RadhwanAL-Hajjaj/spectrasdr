// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText

/**
 * The frequency in megahertz, one digit at a time: tap the upper half of a digit to turn it up,
 * the lower half to turn it down, or drag it up and down like a thumbwheel. The label at the end
 * opens a field for typing a frequency.
 */
@Composable
fun FrequencyReadout(frequencyHz: Long, onChange: (Long) -> Unit, onType: () -> Unit, modifier: Modifier = Modifier) {
    val digits = FrequencyText.digits(frequencyHz)
    val current by rememberUpdatedState(frequencyHz)
    val change by rememberUpdatedState(onChange)
    val firstShown = digits.indexOfFirst { it != 0 }.let { if (it < 0 || it > 3) 3 else it }
    Row(
        modifier = modifier.semantics { contentDescription = FrequencyText.megahertz(frequencyHz) }.testTag("frequency"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until 10) {
            val power = 9 - i
            val large = i < 7
            Text(
                text = digits[i].toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = if (large) 40.sp else 26.sp,
                color = if (i < firstShown) Instrument.dim else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .testTag("digit$power")
                    .pointerInput(power) {
                        detectTapGestures { at ->
                            change(FrequencyText.turnDigit(current, power, if (at.y < size.height / 2) 1 else -1))
                        }
                    }
                    .pointerInput(power) {
                        var travelled = 0f
                        val notch = 18.dp.toPx()
                        detectVerticalDragGestures(onDragStart = { travelled = 0f }) { _, dy ->
                            travelled += dy
                            while (travelled <= -notch) {
                                change(FrequencyText.turnDigit(current, power, 1))
                                travelled += notch
                            }
                            while (travelled >= notch) {
                                change(FrequencyText.turnDigit(current, power, -1))
                                travelled -= notch
                            }
                        }
                    },
            )
            if (i == 3) Text(".", fontFamily = FontFamily.Monospace, fontSize = 40.sp)
            if (i == 6) Text(" ", fontSize = 14.sp)
        }
        Text(
            "MHz ✎",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 6.dp, bottom = 6.dp)
                .clickable(onClickLabel = "Type a frequency", onClick = onType)
                .testTag("type-frequency"),
        )
    }
}

/** Asks for a frequency in megahertz; [onDone] gets it in hertz. */
@Composable
fun FrequencyDialog(current: Long, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    var text by remember { mutableStateOf(FrequencyText.short(current).removeSuffix(" MHz")) }
    val hz = FrequencyText.parseMegahertz(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frequency") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("MHz") },
                singleLine = true,
                isError = hz == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.testTag("frequency-field"),
            )
        },
        confirmButton = {
            TextButton(enabled = hz != null, onClick = { hz?.let(onDone) }, modifier = Modifier.testTag("frequency-ok")) { Text("Tune") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
