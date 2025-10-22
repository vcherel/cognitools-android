package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat


const val GAIN_STEP_POSITIVE = 1000
const val GAIN_STEP_NEGATIVE = 250

class VolumeBoosterService : Service() {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val binder = LocalBinder()
    private var currentGain = 0

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "volume_booster"
        const val ACTION_STOP = "com.myapp.STOP_BOOST"
        const val ACTION_SET_GAIN = "com.myapp.SET_GAIN"
        const val ACTION_INCREASE_GAIN = "com.myapp.INCREASE_GAIN"
        const val ACTION_DECREASE_GAIN = "com.myapp.DECREASE_GAIN"
        const val EXTRA_GAIN = "extra_gain"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VolumeBoosterService = this@VolumeBoosterService
    }

    override fun onBind(intent: Intent): IBinder = binder

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
            ACTION_SET_GAIN -> {
                val gain = intent.getIntExtra(EXTRA_GAIN, 0)
                setGain(gain)
            }
            ACTION_INCREASE_GAIN -> {
                val step = if (currentGain >= 0) {
                    GAIN_STEP_POSITIVE
                } else {
                    GAIN_STEP_NEGATIVE
                }
                setGain(currentGain + step)
                updateNotification()
            }
            ACTION_DECREASE_GAIN -> {
                val step = if (currentGain > 0) {
                    GAIN_STEP_POSITIVE
                } else {
                    GAIN_STEP_NEGATIVE
                }
                setGain(currentGain - step)
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
            if (loudnessEnhancer == null) {
                loudnessEnhancer = LoudnessEnhancer(0).apply {
                    enabled = true
                    setTargetGain(currentGain)
                }
            } else {
                loudnessEnhancer?.apply {
                    enabled = true
                    setTargetGain(currentGain)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setGain(gain: Int) {
        currentGain = gain
        loudnessEnhancer?.setTargetGain(currentGain)
    }

    fun stopBoost() {
        loudnessEnhancer?.enabled = false
        loudnessEnhancer?.release()
        loudnessEnhancer = null
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

    private fun formatGainDisplay(): String {
        val displayValue = if (currentGain >= 0) {
            currentGain / GAIN_STEP_POSITIVE
        } else {
            (-currentGain) / GAIN_STEP_NEGATIVE
        }

        return when {
            currentGain > 0 -> "+$displayValue"
            currentGain < 0 -> "-$displayValue"
            else -> "0"
        }
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

        val decreaseIntent = Intent(this, VolumeBoosterService::class.java).apply {
            action = ACTION_DECREASE_GAIN
        }
        val decreasePendingIntent = PendingIntent.getService(
            this,
            1,
            decreaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val increaseIntent = Intent(this, VolumeBoosterService::class.java).apply {
            action = ACTION_INCREASE_GAIN
        }
        val increasePendingIntent = PendingIntent.getService(
            this,
            2,
            increaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Booster Actif")
            .setContentText("Gain : ${formatGainDisplay()}")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "-",
                decreasePendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "+",
                increasePendingIntent
            )
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
    var currentGain by remember { mutableIntStateOf(0) }
    var serviceBinder by remember { mutableStateOf<VolumeBoosterService.LocalBinder?>(null) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceBinder = service as? VolumeBoosterService.LocalBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBinder = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isBoostEnabled) {
                context.unbindService(serviceConnection)
            }
        }
    }

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

        Spacer(modifier = Modifier.height(24.dp))

        // Display current gain
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gain : ",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            val displayValue = if (currentGain >= 0) {
                currentGain / GAIN_STEP_POSITIVE
            } else {
                currentGain / GAIN_STEP_NEGATIVE
            }

            Text(
                text = "${if (currentGain > 0) "+" else if (currentGain < 0) "-" else ""}${kotlin.math.abs(displayValue)}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        MySwitch(
            text = "Boost",
            isEnabled = isBoostEnabled,
            onToggle = { enabled ->
                isBoostEnabled = enabled
                if (enabled) {
                    val intent = Intent(context, VolumeBoosterService::class.java)
                    ContextCompat.startForegroundService(context, intent)

                    // Bind to service to control gain
                    val bindIntent = Intent(context, VolumeBoosterService::class.java)
                    context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                } else {
                    val intent = Intent(context, VolumeBoosterService::class.java).apply {
                        action = VolumeBoosterService.ACTION_STOP
                    }
                    context.startService(intent)
                    context.unbindService(serviceConnection)
                    serviceBinder = null
                    currentGain = 0 // Reset gain when disabled
                }
            }
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Gain control buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minus button
            MyButton(
                text = "-",
                modifier = Modifier.width(100.dp),
                fontSize = 32.sp,
                enabled = isBoostEnabled,
                onClick = {
                    val step = if (currentGain > 0) {
                        GAIN_STEP_POSITIVE
                    } else {
                        GAIN_STEP_NEGATIVE
                    }
                    val newGain = currentGain - step
                    currentGain = newGain
                    serviceBinder?.getService()?.setGain(newGain)
                }
            )

            // Plus button
            MyButton(
                text = "+",
                modifier = Modifier.width(100.dp),
                fontSize = 32.sp,
                enabled = isBoostEnabled,
                onClick = {
                    val step = if (currentGain >= 0) {
                        GAIN_STEP_POSITIVE
                    } else {
                        GAIN_STEP_NEGATIVE
                    }
                    val newGain = currentGain + step
                    currentGain = newGain
                    serviceBinder?.getService()?.setGain(newGain)
                }
            )
        }
    }
}