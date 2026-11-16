// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app end to end on an emulator, with no radio hardware (§12). */
@RunWith(AndroidJUnit4::class)
class ListeningTest {
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var radio: Radio

    @Before
    fun startFromFm() {
        radio = rule.activity.radio
        radio.update { it.withMode(Mode.WFM).copy(frequencyHz = 100_300_000) }
    }

    @After
    fun stopListening() {
        radio.stop()
        rule.waitUntil(20_000) { radio.state.value == SessionState.Idle }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val done = automation.executeShellCommand("screencap -p /sdcard/Download/screen-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(done).use { it.readBytes() }
    }

    private fun tunedAndRunning() = radio.state.value is SessionState.Running && radio.status.value.tuned

    /** The DSP load as the middle of twenty readings, so one slow moment does not decide it. */
    private fun typicalLoad(): Double {
        val readings = DoubleArray(20) {
            Thread.sleep(150)
            radio.status.value.load
        }
        readings.sort()
        return readings[readings.size / 2]
    }

    @Test
    fun theSimulatorPlaysAStationAndShowsItsName() {
        screenshot("1-stopped")
        rule.onNodeWithTag("listen").performScrollTo().performClick()
        rule.onNodeWithTag("source-simulator").performClick()
        rule.onNodeWithTag("source-start").performClick()

        rule.waitUntil(120_000) { rule.onAllNodesWithText("DEMO FM").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("stereo").assertIsDisplayed()
        rule.waitUntil(30_000) { radio.player.framesPlayed > 2 * 48_000 }
        val load = typicalLoad()
        val status = radio.status.value
        Log.w("SPECTRA", "simulator: load=$load overruns=${status.overruns} underruns=${radio.ring.underruns} dropped=${radio.ring.droppedFrames}")
        screenshot("2-fm")
        assertTrue("the signal processing keeps up: typical load $load", load < 0.8)

        rule.onNodeWithTag("type-frequency").performClick()
        rule.onNodeWithTag("frequency-field").performTextReplacement("145.5")
        rule.onNodeWithTag("frequency-ok").performClick()
        rule.onNodeWithTag("mode-NFM").performClick()
        rule.waitUntil(20_000) { radio.settings.value.mode == Mode.NFM && radio.settings.value.frequencyHz == 145_500_000L && radio.status.value.tuned }
        screenshot("3-nfm")

        rule.onNodeWithTag("stop").performScrollTo().performClick()
        rule.waitUntil(20_000) { radio.state.value == SessionState.Idle }
    }

    @Test
    fun aNetworkServerIsUsedAndReconnectedAfterALoss() {
        FakeRtlTcp().use { server ->
            rule.onNodeWithTag("listen").performScrollTo().performClick()
            rule.onNodeWithTag("source-server").performClick()
            rule.onNodeWithTag("server-host").performTextReplacement("127.0.0.1")
            rule.onNodeWithTag("server-port").performTextReplacement(server.port.toString())
            rule.onNodeWithTag("source-start").performClick()

            rule.waitUntil(90_000) { tunedAndRunning() }
            assertEquals(SourceSpec.Network("127.0.0.1", server.port), (radio.state.value as SessionState.Running).spec)
            Log.w("SPECTRA", "network: load=${typicalLoad()} overruns=${radio.status.value.overruns} commands=${server.commands}")
            screenshot("4-network")

            server.dropClients()
            rule.waitUntil(30_000) { radio.state.value !is SessionState.Running }
            rule.waitUntil(90_000) { server.connections >= 2 && tunedAndRunning() }
        }
    }

    @Test
    fun quickStartsAndStopsLeaveTheAppRunning() {
        // Stopping the foreground service before it has become one ends the whole app.
        repeat(6) {
            rule.runOnUiThread { radio.start(SourceSpec.Simulator) }
            if (it % 2 == 0) Thread.sleep(40L * it)
            rule.runOnUiThread { radio.stop() }
        }
        rule.runOnUiThread { radio.start(SourceSpec.Simulator) }
        rule.waitUntil(60_000) { tunedAndRunning() }
        rule.runOnUiThread { radio.stop() }
        rule.waitUntil(20_000) { radio.state.value == SessionState.Idle && !radio.serviceWanted.value }
    }

    @Test
    fun theDongleIsStartedThroughADriverApp() {
        rule.onNodeWithTag("listen").performScrollTo().performClick()
        rule.onNodeWithTag("source-dongle").performClick()
        rule.onNodeWithTag("source-start").performClick()

        rule.waitUntil(90_000) { tunedAndRunning() }
        val spec = (radio.state.value as SessionState.Running).spec
        assertTrue("the source is the dongle: $spec", spec is SourceSpec.Driver)
        screenshot("5-dongle")
    }
}
