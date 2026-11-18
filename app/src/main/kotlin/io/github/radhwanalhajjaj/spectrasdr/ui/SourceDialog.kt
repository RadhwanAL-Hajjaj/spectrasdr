// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec

/** What the user picked in the source dialog. */
sealed interface SourceChoice {
    data object Simulator : SourceChoice
    data object Dongle : SourceChoice
    data class Server(val host: String, val port: Int) : SourceChoice
}

private enum class Kind { SIMULATOR, DONGLE, SERVER }

@Composable
fun SourceDialog(last: SourceSpec, onDismiss: () -> Unit, onStart: (SourceChoice) -> Unit) {
    var kind by remember {
        mutableStateOf(
            when (last) {
                SourceSpec.Simulator -> Kind.SIMULATOR
                is SourceSpec.Driver -> Kind.DONGLE
                is SourceSpec.Network -> Kind.SERVER
            },
        )
    }
    var host by remember { mutableStateOf((last as? SourceSpec.Network)?.host ?: "") }
    var port by remember { mutableStateOf(((last as? SourceSpec.Network)?.port ?: 1234).toString()) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val ready = kind != Kind.SERVER || (host.isNotBlank() && portNumber != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source") },
        text = {
            Column {
                Option(kind == Kind.DONGLE, "USB dongle", "RTL-SDR through the SDR driver app", "source-dongle") { kind = Kind.DONGLE }
                Option(kind == Kind.SERVER, "Network server", "rtl_tcp or rsp_tcp on your network", "source-server") { kind = Kind.SERVER }
                if (kind == Kind.SERVER) {
                    Row(modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            label = { Text("Address") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(2f).testTag("server-host"),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            isError = portNumber == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("server-port"),
                        )
                    }
                }
                Option(kind == Kind.SIMULATOR, "Simulator", "A made-up band, no hardware needed", "source-simulator") { kind = Kind.SIMULATOR }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onStart(
                        when (kind) {
                            Kind.SIMULATOR -> SourceChoice.Simulator
                            Kind.DONGLE -> SourceChoice.Dongle
                            Kind.SERVER -> SourceChoice.Server(host, portNumber ?: 1234)
                        },
                    )
                },
                modifier = Modifier.testTag("source-start"),
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Option(selected: Boolean, title: String, detail: String, tag: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
