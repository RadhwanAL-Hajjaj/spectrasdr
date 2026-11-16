// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.radhwanalhajjaj.spectrasdr.MainActivity
import io.github.radhwanalhajjaj.spectrasdr.R
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.radio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the radio playing while the app is in the background (§10): a foreground service of the
 * media-playback type with a notification, a wake lock, a Wi-Fi lock for network sources, and
 * audio focus. The radio itself lives in [Radio]; this service only follows its state.
 */
class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var inForeground = false   // main thread only

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> radio.setPaused(false)
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> radio.setPaused(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            combine(radio.state, radio.settings, radio.rds) { state, settings, rds ->
                Triple(state, "${FrequencyText.short(settings.frequencyHz)} ${settings.mode.name}", rds.ps.trim())
            }.distinctUntilChanged().collect { (state, tuning, station) ->
                if (inForeground && state !is SessionState.Idle && state !is SessionState.Failed) {
                    holdLocks(state)
                    manager.notify(NOTIFICATION, notification(tuning, describe(state, station)))
                }
            }
        }
        // Ends itself, and only after it has become a foreground service: stopping one that was asked
        // for with startForegroundService() before it called startForeground() ends the whole app.
        scope.launch {
            radio.serviceWanted.collect { wanted -> if (!wanted && inForeground) finish() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            radio.stop()
            return START_NOT_STICKY
        }
        val first = notification(getString(R.string.app_name), getString(R.string.state_starting))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION, first)
        }
        inForeground = true
        if (radio.serviceWanted.value) requestFocus() else finish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        abandonFocus()
        super.onDestroy()
    }

    private fun finish() {
        inForeground = false
        releaseLocks()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun describe(state: SessionState, station: String): String = when (state) {
        is SessionState.Connecting -> getString(R.string.state_connecting, state.spec.label)
        is SessionState.Running -> station.ifEmpty { state.info.name }
        is SessionState.Waiting -> getString(R.string.state_waiting, state.seconds)
        is SessionState.Failed -> state.message
        SessionState.Idle -> ""
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    private fun holdLocks(state: SessionState) {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spectrasdr:listening")
                .apply { setReferenceCounted(false); acquire() }
        }
        val network = when (state) {
            is SessionState.Connecting -> state.spec is SourceSpec.Network
            is SessionState.Running -> state.spec is SourceSpec.Network
            is SessionState.Waiting -> true
            else -> false
        }
        if (network && wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "spectrasdr:stream")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        getSystemService(AudioManager::class.java).requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val NOTIFICATION = 1
        const val ACTION_STOP = "io.github.radhwanalhajjaj.spectrasdr.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RadioService::class.java))
        }
    }
}
