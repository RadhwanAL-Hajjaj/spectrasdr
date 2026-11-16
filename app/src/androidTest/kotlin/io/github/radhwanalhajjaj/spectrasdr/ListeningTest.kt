// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningTest {
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val done = automation.executeShellCommand("screencap -p /sdcard/Download/screen-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(done).use { it.readBytes() }
    }

    @Test
    fun theSimulatorPlaysAStationAndShowsItsName() {
        val radio = rule.activity.radio
        screenshot("1-stopped")
        rule.onNodeWithTag("listen").performScrollTo().performClick()
        rule.onNodeWithTag("source-simulator").performClick()
        rule.onNodeWithTag("source-start").performClick()

        rule.waitUntil(120_000) { rule.onAllNodesWithText("DEMO FM").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("stereo").assertIsDisplayed()
        rule.waitUntil(30_000) { radio.player.framesPlayed > 2 * 48_000 }
        val status = radio.status.value
        android.util.Log.w("SPECTRA", "load=${status.load} overruns=${status.overruns} underruns=${radio.ring.underruns} dropped=${radio.ring.droppedFrames}")
        screenshot("2-fm")
        assertTrue("the DSP keeps up: load ${status.load}", status.load < 0.9)

        rule.onNodeWithTag("mode-NFM").performClick()
        rule.waitUntil(10_000) { radio.settings.value.mode.name == "NFM" }
        screenshot("3-nfm")

        rule.onNodeWithTag("stop").performScrollTo().performClick()
        rule.waitUntil(15_000) { radio.state.value == SessionState.Idle }
    }
}
