// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Stands in for an SDR driver app in the emulator tests (§5.1): reads the port from the iqsrc
 * address, starts a small rtl_tcp server there, and answers OK with the commands it supports.
 */
class FakeDriverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arguments = intent.data.toString().removePrefix("iqsrc://").split(' ')
        val port = arguments.getOrNull(arguments.indexOf("-p") + 1)?.toIntOrNull()
        if (port == null) {
            setResult(RESULT_CANCELED, Intent().putExtra("detailed_exception_message", "No port in ${intent.data}"))
        } else {
            server?.close()
            server = FakeRtlTcp(port)
            setResult(RESULT_OK, Intent().putExtra("supportedTcpCommands", intArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x0d, 0x7e)))
        }
        finish()
    }

    companion object {
        /** Kept here so the server outlives the activity. */
        @Volatile
        var server: FakeRtlTcp? = null
    }
}
