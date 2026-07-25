package com.wessamk.naatiq.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wessamk.naatiq.R
import com.wessamk.naatiq.ServiceLocator
import com.wessamk.naatiq.speech.PlaybackStatus
import com.wessamk.naatiq.speech.SpeechState
import com.wessamk.naatiq.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps reading while the app is in the background and shows the playback notification.
 * The service owns no speech state of its own; it mirrors [ServiceLocator.speechEngine].
 */
class SpeechService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine by lazy { ServiceLocator.speechEngine }
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.install(this)
        createChannel()
        scope.launch {
            engine.state.collectLatest { state ->
                if (state.status == PlaybackStatus.IDLE) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    notify(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceLocator.install(this)
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePlayPause()
            ACTION_NEXT -> engine.skipToNext()
            ACTION_PREVIOUS -> engine.skipToPrevious()
            ACTION_STOP -> {
                engine.stop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        notify(engine.state.value)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(state: SpeechState) {
        val notification = buildNotification(state)
        if (!startedForeground) {
            startForeground(NOTIFICATION_ID, notification)
            startedForeground = true
        } else {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: SpeechState): android.app.Notification {
        val speaking = state.status == PlaybackStatus.SPEAKING
        val content = state.currentSegment?.text?.take(120)
            ?: getString(if (speaking) R.string.notification_reading else R.string.notification_paused)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (speaking) R.string.notification_reading else R.string.notification_paused))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(speaking)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.cd_previous),
                command(ACTION_PREVIOUS),
            )
            .addAction(
                R.drawable.ic_notification,
                getString(if (speaking) R.string.cd_pause else R.string.cd_play),
                command(ACTION_TOGGLE),
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.cd_next),
                command(ACTION_NEXT),
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.cd_stop),
                command(ACTION_STOP),
            )
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, SpeechService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (!startedForeground) return
        startedForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "naatiq_playback"
        private const val NOTIFICATION_ID = 1

        const val ACTION_TOGGLE = "com.wessamk.naatiq.TOGGLE"
        const val ACTION_NEXT = "com.wessamk.naatiq.NEXT"
        const val ACTION_PREVIOUS = "com.wessamk.naatiq.PREVIOUS"
        const val ACTION_STOP = "com.wessamk.naatiq.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SpeechService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Background start restrictions: reading still works while the app is visible.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpeechService::class.java))
        }
    }
}
