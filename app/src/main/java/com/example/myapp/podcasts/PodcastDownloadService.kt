package com.example.myapp.podcasts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapp.MainActivity
import com.example.myapp.R
import com.example.myapp.podcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps episode downloads running with the app closed and the phone locked, and shows what they are
 * doing. The work itself belongs to [PodcastRepository]: this service only holds the foreground
 * notification (its own id and channel, like the two playback services) and a wake lock, then stops
 * itself as soon as nothing is downloading.
 */
class PodcastDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            applicationContext.podcastRepository.downloads.cancelAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(emptyList(), null))
        acquireWakeLock()
        watch()
        return START_NOT_STICKY
    }

    /** Redraws the notification as the queue and the progress move, and ends the service when done. */
    private fun watch() {
        if (watchJob?.isActive == true) return
        val repo = applicationContext.podcastRepository
        watchJob = scope.launch {
            combine(repo.downloads.active, repo.downloads.progress) { queue, progress -> queue to progress }
                .collect { (queue, progress) ->
                    if (queue.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification(queue, progress[queue.first().id]))
                }
        }
    }

    private fun buildNotification(queue: List<PodcastEpisode>, progress: Float?): Notification {
        val current = queue.firstOrNull()
        val remaining = (queue.size - 1).coerceAtLeast(0)
        val text = when {
            current == null -> "Préparation…"
            remaining > 0 -> "${current.podcastTitle} · $remaining en attente"
            else -> current.podcastTitle
        }
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PodcastDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, "deezer"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(current?.title ?: "Téléchargement d'épisode")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            // Indeterminate until the server announces the episode's size.
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (queue.size > 1) "Tout annuler" else "Annuler",
                cancelIntent
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myapp:podcastDownload")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.podcast_download_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CANCEL = "com.example.myapp.podcasts.CANCEL_DOWNLOADS"

        // Must differ from the two playback services', see PodcastPlaybackService.onCreate.
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "podcast_downloads"

        /** A safety net, not a budget: the service releases the lock as soon as the queue empties. */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PodcastDownloadService::class.java)
            )
        }
    }
}
