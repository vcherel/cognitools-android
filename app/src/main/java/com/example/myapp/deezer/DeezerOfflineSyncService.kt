package com.example.myapp.deezer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapp.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the Best pépites offline sync in the foreground, so downloading 50 tracks survives leaving the
 * Deezer screen or the app. Started on entering the tool; stops itself as soon as the mirror is up to
 * date, which after the first run is immediate.
 */
class DeezerOfflineSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Deezer hors ligne", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Téléchargement des titres Best pépites"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val offline = (application as MyApplication).deezerRepository.offline
        startForeground(NOTIFICATION_ID, notification(offline.state.value))
        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            launch {
                offline.state.collect { state ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                }
            }
            offline.sync()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: OfflineState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Best pépites hors ligne")
            .setContentText(
                when {
                    state.total == 0 -> "Vérification…"
                    else -> "${state.downloaded}/${state.total} titres"
                }
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
        if (state.total > 0) builder.setProgress(state.total, state.downloaded, false)
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        private const val CHANNEL_ID = "deezer_offline"

        /** Fire and forget: starts an incremental sync, a no-op once everything is already downloaded. */
        fun start(context: Context) {
            val intent = Intent(context, DeezerOfflineSyncService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
