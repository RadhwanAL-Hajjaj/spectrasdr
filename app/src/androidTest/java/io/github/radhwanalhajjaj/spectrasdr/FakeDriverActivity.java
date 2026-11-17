// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.io.IOException;

/**
 * Stands in for an SDR driver app in the emulator tests (§5.1): reads the port from the iqsrc
 * address, starts a small rtl_tcp server there, and answers OK with the commands it supports.
 *
 * Java, not Kotlin: this activity runs in the test package's own process (see FakeRtlTcp).
 */
public final class FakeDriverActivity extends Activity {
    /** Kept here so that the server outlives the activity. */
    private static volatile FakeRtlTcp server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String address = String.valueOf(getIntent().getData());
        String[] arguments = address.replace("iqsrc://", "").split(" ");
        int port = -1;
        for (int i = 0; i + 1 < arguments.length; i++) {
            if (arguments[i].equals("-p")) {
                try {
                    port = Integer.parseInt(arguments[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        try {
            if (port < 0) throw new IOException("No port in " + address);
            if (server != null) server.close();
            server = new FakeRtlTcp(port, 2_400_000);
            setResult(RESULT_OK, new Intent().putExtra("supportedTcpCommands", new int[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x0d, 0x7e}));
        } catch (IOException e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("detailed_exception_message", String.valueOf(e.getMessage())));
        }
        finish();
    }
}
