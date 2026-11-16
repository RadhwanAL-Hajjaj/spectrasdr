// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.radhwanalhajjaj.spectrasdr.ui.ReceiverScreen
import io.github.radhwanalhajjaj.spectrasdr.ui.SpectraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpectraTheme {
                ReceiverScreen(radio)
            }
        }
    }
}
