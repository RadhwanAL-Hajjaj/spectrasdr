// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SimulatorSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClient
import io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpConfig

/** Where the samples come from, as the user chooses it. */
sealed interface SourceSpec {
    /** Short name for lists and the notification. */
    val label: String

    /** The built-in synthetic band; needs no hardware. */
    data object Simulator : SourceSpec {
        override val label get() = "Simulator"
    }

    /** An rtl_tcp or rsp_tcp server on the network; which one is found out when connecting. */
    data class Network(val host: String, val port: Int = 1234) : SourceSpec {
        init {
            require(host.isNotBlank()) { "host must not be blank" }
            require(port in 1..65535) { "port $port is outside 1..65535" }
        }

        override val label get() = "$host:$port"
    }

    /**
     * The Android SDR driver app, already started and listening on this device (§5.1).
     * [supportedCommands] is what the driver reported; null means every command.
     */
    data class Driver(val port: Int, val supportedCommands: Set<Int>? = null) : SourceSpec {
        init {
            require(port in 1..65535) { "port $port is outside 1..65535" }
        }

        override val label get() = "USB dongle"
    }
}

/** What a [RadioSession] is doing. */
sealed interface SessionState {
    /** Nothing is running. */
    data object Idle : SessionState

    /** Opening the source; [attempt] is 0 for the first try and counts reconnections after that. */
    data class Connecting(val spec: SourceSpec, val attempt: Int) : SessionState

    /** Samples are arriving. */
    data class Running(val spec: SourceSpec, val info: SourceInfo) : SessionState

    /** The network source was lost; the next try starts in [seconds]. */
    data class Waiting(val spec: SourceSpec, val attempt: Int, val seconds: Int, val reason: String) : SessionState

    /** The session ended with an error the user has to act on; [message] is in plain language. */
    data class Failed(val spec: SourceSpec, val message: String) : SessionState
}

/**
 * What a session reports. [onState] and [onMessage] come from the session's own thread or the
 * receiver's DSP thread, [onStatus] and [onRds] from the DSP thread: return quickly from all of them.
 */
interface SessionListener {
    fun onState(state: SessionState) {}
    fun onStatus(status: ReceiverStatus) {}
    fun onRds(state: RdsState) {}

    /** A notice for the user, in plain language (a refused frequency, a slow link). */
    fun onMessage(text: String) {}
}

/** Builds the source for a [SourceSpec]; replaced in tests. */
fun interface SourceFactory {
    fun create(spec: SourceSpec, settings: ReceiverSettings): IqSource

    companion object {
        val Default = SourceFactory { spec, settings ->
            when (spec) {
                SourceSpec.Simulator -> SimulatorSource()
                is SourceSpec.Network -> RtlTcpClient(
                    RtlTcpConfig(
                        host = spec.host,
                        port = spec.port,
                        name = spec.label,
                        initialFrequencyHz = settings.frequencyHz.coerceIn(0, 0xFFFF_FFFFL),
                        initialSampleRate = settings.sampleRate,
                    ),
                )
                is SourceSpec.Driver -> RtlTcpClient(
                    RtlTcpConfig(
                        host = "127.0.0.1",
                        port = spec.port,
                        kind = SourceKind.ANDROID_DRIVER,
                        name = spec.label,
                        initialFrequencyHz = settings.frequencyHz.coerceIn(0, 0xFFFF_FFFFL),
                        initialSampleRate = settings.sampleRate,
                        supportedCommands = spec.supportedCommands,
                        detectExtended = false,
                        sendExitOnClose = true,
                    ),
                )
            }
        }
    }
}

/** Plain-language text for the errors a source reports (§11). */
object SessionMessages {
    fun describe(error: SourceError, spec: SourceSpec): String = when (error) {
        is SourceError.ConnectFailed -> when (spec) {
            is SourceSpec.Driver -> "The SDR driver did not answer. Start the dongle again. (${error.message})"
            else -> "Could not connect to ${spec.label}. Check that the server is running and that this device can reach it. (${error.message})"
        }
        is SourceError.StreamEnded -> when (spec) {
            is SourceSpec.Driver -> "The dongle stopped sending samples. It may have been unplugged."
            is SourceSpec.Network -> "The connection to ${spec.label} was lost."
            SourceSpec.Simulator -> "The simulator stopped."
        }
        is SourceError.ProtocolError -> "${spec.label} is not an rtl_tcp or rsp_tcp server. (${error.message})"
        is SourceError.DriverRejected -> error.message
        is SourceError.DriverNotInstalled -> error.message
        is SourceError.Io -> "The connection to ${spec.label} failed. (${error.message})"
    }

    /** Whether trying again by itself can help. */
    fun canRetry(error: SourceError, spec: SourceSpec): Boolean =
        spec is SourceSpec.Network && (error is SourceError.ConnectFailed || error is SourceError.StreamEnded || error is SourceError.Io)
}
