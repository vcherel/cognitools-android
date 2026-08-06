package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.IBinder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

const val GAIN_STEP_POSITIVE = 1000
const val GAIN_STEP_NEGATIVE = 250

data class BoostState(val running: Boolean = false, val gain: Int = 0)

// Positive gain moves in big steps, negative gain in fine steps.
private fun gainStep(gain: Int, increase: Boolean): Int {
    val aboveZero = if (increase) gain >= 0 else gain > 0
    return if (aboveZero) GAIN_STEP_POSITIVE else GAIN_STEP_NEGATIVE
}

private fun formatGain(gain: Int): String {
    val displayValue = if (gain >= 0) gain / GAIN_STEP_POSITIVE else -gain / GAIN_STEP_NEGATIVE
    return when {
        gain > 0 -> "+$displayValue"
        gain < 0 -> "-$displayValue"
        else -> "0"
    }
}

class VolumeBoosterService : Service() {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentGain = 0

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "volume_booster"
        const val ACTION_STOP = "com.myapp.STOP_BOOST"
        const val ACTION_INCREASE_GAIN = "com.myapp.INCREASE_GAIN"
        const val ACTION_DECREASE_GAIN = "com.myapp.DECREASE_GAIN"

        // Single source of truth for the UI, updated by the service.
        val state = MutableStateFlow(BoostState())
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBoost()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_INCREASE_GAIN -> {
                setGain(currentGain + gainStep(currentGain, increase = true))
                updateNotification()
            }
            ACTION_DECREASE_GAIN -> {
                setGain(currentGain - gainStep(currentGain, increase = false))
                updateNotification()
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
            val enhancer = loudnessEnhancer ?: LoudnessEnhancer(0).also { loudnessEnhancer = it }
            enhancer.enabled = true
            enhancer.setTargetGain(currentGain)
            state.value = BoostState(running = true, gain = currentGain)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setGain(gain: Int) {
        currentGain = gain
        loudnessEnhancer?.setTargetGain(currentGain)
        state.value = BoostState(running = true, gain = currentGain)
    }

    private fun stopBoost() {
        loudnessEnhancer?.enabled = false
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        currentGain = 0
        state.value = BoostState()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Volume Booster",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Volume boost is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, VolumeBoosterService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Booster Actif")
            .setContentText("Gain : ${formatGain(currentGain)}")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Every +/- tap reposts this notification; without this it replays the notification
            // sound on each gain change instead of only on the initial post.
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "-",
                actionIntent(ACTION_DECREASE_GAIN, 1)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "+",
                actionIntent(ACTION_INCREASE_GAIN, 2)
            )
            .addAction(
                android.R.drawable.ic_delete,
                "Arrêter",
                actionIntent(ACTION_STOP, 0)
            )
            .build()
    }

    override fun onDestroy() {
        stopBoost()
        super.onDestroy()
    }
}

private fun sendServiceAction(context: Context, action: String) {
    val intent = Intent(context, VolumeBoosterService::class.java).apply { this.action = action }
    context.startService(intent)
}

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val boostState by VolumeBoosterService.state.collectAsState()

    ScreenTopBar(title = "Volume Booster", onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (boostState.running) "Activé" else "Désactivé",
            style = MaterialTheme.typography.headlineLarge,
            color = if (boostState.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Gain : ",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = formatGain(boostState.gain),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        MySwitch(
            text = "Boost",
            isEnabled = boostState.running,
            onToggle = { enabled ->
                if (enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, VolumeBoosterService::class.java)
                    )
                } else {
                    sendServiceAction(context, VolumeBoosterService.ACTION_STOP)
                }
            }
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Gain control buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MyButton(
                text = "-",
                modifier = Modifier.width(100.dp),
                fontSize = 32.sp,
                enabled = boostState.running,
                onClick = { sendServiceAction(context, VolumeBoosterService.ACTION_DECREASE_GAIN) }
            )

            MyButton(
                text = "+",
                modifier = Modifier.width(100.dp),
                fontSize = 32.sp,
                enabled = boostState.running,
                onClick = { sendServiceAction(context, VolumeBoosterService.ACTION_INCREASE_GAIN) }
            )
        }
    }
}
