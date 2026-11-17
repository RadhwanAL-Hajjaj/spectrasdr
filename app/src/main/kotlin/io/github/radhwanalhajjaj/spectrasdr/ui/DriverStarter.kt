// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.radhwanalhajjaj.spectrasdr.core.session.DriverUri
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio
import java.net.ServerSocket

/** What came of asking the driver apps for the dongle. */
sealed interface DriverOutcome {
    data class Started(val spec: SourceSpec.Driver) : DriverOutcome
    data class Refused(val message: String) : DriverOutcome
    data object NotInstalled : DriverOutcome
}

/** The hand-off to an SDR driver app (§5.1). */
object DriverHandOff {
    /** A port nothing listens on; 1234 if none can be found. */
    fun freePort(): Int = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(1234)

    /** Every installed app that answers an iqsrc address, the tested driver first. */
    fun handlers(context: Context, address: String): List<ComponentName> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address))
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { if (it.packageName == DriverUri.DRIVER_PACKAGE) 0 else 1 }
    }

    fun intent(address: String, handler: ComponentName): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(address)).setComponent(handler)

    /** The driver's Play Store page. */
    fun storeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${DriverUri.DRIVER_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun started(port: Int, data: Intent?): SourceSpec.Driver =
        SourceSpec.Driver(port, data?.getIntArrayExtra(DriverUri.EXTRA_SUPPORTED_COMMANDS)?.toSet())

    fun refusal(data: Intent?): String =
        data?.getStringExtra(DriverUri.EXTRA_ERROR_MESSAGE)?.takeIf { it.isNotBlank() }
            ?: "The SDR driver could not start the dongle. Check that it is plugged in and that the driver app has permission to use it."
}

private class Attempt(val port: Int, val address: String, val handlers: List<ComponentName>, val index: Int)

/**
 * Returns a function that asks the installed driver apps, one after the other, to start the
 * dongle, and reports the outcome.
 */
@Composable
fun rememberDriverStarter(radio: Radio, onOutcome: (DriverOutcome) -> Unit): () -> Unit {
    val context = LocalContext.current
    val outcome by rememberUpdatedState(onOutcome)
    var attempt by remember { mutableStateOf<Attempt?>(null) }
    lateinit var launch: (Attempt) -> Unit

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val a = attempt ?: return@rememberLauncherForActivityResult
        when {
            result.resultCode == Activity.RESULT_OK -> {
                attempt = null
                outcome(DriverOutcome.Started(DriverHandOff.started(a.port, result.data)))
            }
            a.index + 1 < a.handlers.size -> launch(Attempt(a.port, a.address, a.handlers, a.index + 1))
            else -> {
                attempt = null
                outcome(DriverOutcome.Refused(DriverHandOff.refusal(result.data)))
            }
        }
    }
    launch = { a ->
        attempt = a
        try {
            launcher.launch(DriverHandOff.intent(a.address, a.handlers[a.index]))
        } catch (_: ActivityNotFoundException) {
            attempt = null
            outcome(DriverOutcome.NotInstalled)
        }
    }

    return {
        val port = DriverHandOff.freePort()
        val settings = radio.settings.value
        val address = DriverUri.build(port, Radio.DEFAULT_RATE, settings.frequencyHz.coerceIn(0, 0xFFFF_FFFFL))
        val handlers = DriverHandOff.handlers(context, address)
        if (handlers.isEmpty()) outcome(DriverOutcome.NotInstalled) else launch(Attempt(port, address, handlers, 0))
    }
}
