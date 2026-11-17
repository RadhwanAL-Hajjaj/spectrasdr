// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colours of the instrument parts that are not Material components. */
object Instrument {
    val trace = Color(0xFF4DD0E1)
    val peak = Color(0xFF6D8A91)
    val grid = Color(0xFF263238)
    val channel = Color(0x334DD0E1)
    val channelCentre = Color(0xFFFFB74D)
    val plot = Color(0xFF0B0F14)
    val dim = Color(0xFF546E7A)
    val good = Color(0xFF81C784)
}

private val colors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF452B00),
    background = Color(0xFF10151C),
    surface = Color(0xFF10151C),
    surfaceVariant = Color(0xFF1C242E),
)

/** Dark-first (§10): a radio is used at night and next to a bright waterfall. */
@Composable
fun SpectraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
