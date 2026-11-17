// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

/** The address that asks an Android SDR driver app to start its local rtl_tcp server (§5.1). */
object DriverUri {
    const val SCHEME = "iqsrc"

    /** The driver app this project is tested with (SDR driver by SignalWare). */
    const val DRIVER_PACKAGE = "marto.rtl_tcp_andro"

    const val EXTRA_SUPPORTED_COMMANDS = "supportedTcpCommands"
    const val EXTRA_ERROR_MESSAGE = "detailed_exception_message"
    const val EXTRA_ERROR_CODE = "detailed_exception_code"

    fun build(port: Int, sampleRate: Int, frequencyHz: Long, ppm: Int = 0): String {
        require(port in 1..65535) { "port $port is outside 1..65535" }
        require(sampleRate > 0) { "sample rate must be positive" }
        require(frequencyHz >= 0) { "frequency must not be negative" }
        return "$SCHEME://-a 127.0.0.1 -p $port -s $sampleRate -f $frequencyHz -P $ppm"
    }
}
