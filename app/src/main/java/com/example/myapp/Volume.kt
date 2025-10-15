package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Binder
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class VolumeBoosterService : Service() {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val binder = LocalBinder()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "volume_booster_channel"
        const val ACTION_STOP = "com.myapp.STOP_BOOST"
    }

    inner class LocalBinder : Binder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBoost()
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startBoost()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBoost() {
        try {
            if (loudnessEnhancer == null) {
                loudnessEnhancer = LoudnessEnhancer(0).apply {
                    enabled = true
                    setTargetGain(4000)
                }
            } else {
                loudnessEnhancer?.apply {
                    enabled = true
                    setTargetGain(4000)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopBoost() {
        loudnessEnhancer?.enabled = false
        loudnessEnhancer?.release()
        loudnessEnhancer = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Volume Booster",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Volume boost is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, VolumeBoosterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Booster Actif")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                "Arrêter",
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        stopBoost()
        super.onDestroy()
    }
}

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isBoostEnabled by remember { mutableStateOf(false) }

    BackHandler {
        onBack()
    }

    // Top bar
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text(
                "Volume Booster",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isBoostEnabled) "Activé" else "Désactivé",
            style = MaterialTheme.typography.headlineLarge,
            color = if (isBoostEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(48.dp))

        MySwitch(
            isBoostEnabled = isBoostEnabled,
            onToggle = { enabled ->
                isBoostEnabled = enabled
                if (enabled) {
                    val intent = Intent(context, VolumeBoosterService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    val intent = Intent(context, VolumeBoosterService::class.java).apply {
                        action = VolumeBoosterService.ACTION_STOP
                    }
                    context.startService(intent)
                }
            }
        )
    }
}