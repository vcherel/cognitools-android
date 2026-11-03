package com.example.myapp.recipes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.example.myapp.MainActivity
import com.example.myapp.R
import com.example.myapp.recipeRepository
import com.example.myapp.writeAtomically
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One cooking timer, ringing once [endAt] has passed until it is stopped. */
data class CookingTimer(val id: Int, val label: String, val endAt: Long)

/**
 * The recipe screen's countdowns. Each one is an exact alarm plus a silent notification counting down;
 * at the end the notification is replaced by one that rings on the alarm stream until Stop. Kept in
 * `filesDir/recipes/timers.json` so a killed process or a reboot loses none.
 */
class RecipeTimers(private val context: Context, dir: File) {
    private val file = File(dir, "timers.json")
    private val scope = CoroutineScope(Dispatchers.IO)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val _timers = MutableStateFlow(load())
    val timers: StateFlow<List<CookingTimer>> = _timers.asStateFlow()

    private fun load(): List<CookingTimer> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map(array::getJSONObject).map {
            CookingTimer(it.getInt("id"), it.getString("label"), it.getLong("endAt"))
        }
    }.getOrDefault(emptyList())

    private fun save(timers: List<CookingTimer>) {
        val json = JSONArray(timers.map { JSONObject().put("id", it.id).put("label", it.label).put("endAt", it.endAt) })
        scope.launch { file.writeAtomically(json.toString()) }
    }

    fun start(label: String, seconds: Int) {
        val endAt = System.currentTimeMillis() + seconds * 1000L
        var timer: CookingTimer? = null
        val timers = _timers.updateAndGet { list ->
            CookingTimer((list.maxOfOrNull { it.id } ?: 0) + 1, label, endAt).also { timer = it }.let { list + it }
        }
        save(timers)
        val started = timer!!
        setAlarm(started)
        manager.notify(notificationId(started.id), runningNotification(started))
    }

    fun stop(id: Int) {
        save(_timers.updateAndGet { list -> list.filter { it.id != id } })
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(id))
        manager.cancel(notificationId(id))
    }

    internal fun onAlarm(id: Int) {
        val timer = _timers.value.firstOrNull { it.id == id } ?: return
        manager.notify(notificationId(id), ringingNotification(timer))
    }

    /** After a reboot: the alarms are gone, so a timer still running is armed again and one past its end rings. */
    internal fun rearm() {
        val now = System.currentTimeMillis()
        for (timer in _timers.value) {
            if (timer.endAt > now) {
                setAlarm(timer)
                manager.notify(notificationId(timer.id), runningNotification(timer))
            } else onAlarm(timer.id)
        }
    }

    private fun setAlarm(timer: CookingTimer) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAt, alarmIntent(timer.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAt, alarmIntent(timer.id))
        }
    }

    private fun alarmIntent(id: Int): PendingIntent = receiverIntent(ACTION_RING, id, id * 2)

    private fun receiverIntent(action: String, id: Int, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, RecipeTimerReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        context, OPEN_REQUEST_CODE,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, "recipes"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun runningNotification(timer: CookingTimer): Notification {
        manager.createNotificationChannel(
            NotificationChannel(RUNNING_CHANNEL, context.getString(R.string.recipe_timer_running_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(context, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(timer.label)
            .setWhen(timer.endAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent())
            .addAction(0, "Cancel", receiverIntent(ACTION_STOP, timer.id, timer.id * 2 + 1))
            .build()
    }

    private fun ringingNotification(timer: CookingTimer): Notification {
        manager.createNotificationChannel(
            NotificationChannel(RINGING_CHANNEL, context.getString(R.string.recipe_timer_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                enableVibration(true)
            }
        )
        return NotificationCompat.Builder(context, RINGING_CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(timer.label)
            .setContentText("Time is up")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openIntent())
            .addAction(0, "Stop", receiverIntent(ACTION_STOP, timer.id, timer.id * 2 + 1))
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    private fun notificationId(id: Int) = NOTIFICATION_BASE + id

    companion object {
        private const val RUNNING_CHANNEL = "recipe_timer_running"
        private const val RINGING_CHANNEL = "recipe_timer"
        private const val NOTIFICATION_BASE = 3_000_000
        private const val OPEN_REQUEST_CODE = 3_000_000
        internal const val ACTION_RING = "com.example.myapp.recipes.TIMER_RING"
        internal const val ACTION_STOP = "com.example.myapp.recipes.TIMER_STOP"
        internal const val EXTRA_ID = "id"
    }
}

/** The end of a timer, its Stop or Cancel button, and the reboot that drops every alarm. */
class RecipeTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timers = context.applicationContext.recipeRepository.timers
        val id = intent.getIntExtra(RecipeTimers.EXTRA_ID, -1)
        when (intent.action) {
            RecipeTimers.ACTION_RING -> timers.onAlarm(id)
            RecipeTimers.ACTION_STOP -> timers.stop(id)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> timers.rearm()
        }
    }
}

private val DURATION = Regex(
    """(\d+)(?:\s*(?:à|to|-|–)\s*\d+)?\s*(heures?|hours?|h|minutes?|mins?|mn|secondes?|seconds?|secs?)(?:\s*(\d{1,2})(?!\d))?(?!\p{L})""",
    RegexOption.IGNORE_CASE
)

/** The durations a step's text mentions, in seconds: "8 à 10 min" gives the first bound, "1 h 30" an hour and a half. */
fun stepDurations(step: String): List<Int> = DURATION.findAll(step).mapNotNull { match ->
    val amount = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
    val unit = match.groupValues[2].lowercase()
    when {
        unit.startsWith("h") -> amount * 3600 + (match.groupValues[3].toIntOrNull() ?: 0) * 60
        unit.startsWith("m") -> amount * 60
        else -> amount
    }
}.filter { it > 0 }.distinct().toList()

/** "10 min", "1 h 30", "45 s", as a timer chip reads. */
fun formatTimerLength(seconds: Int): String = when {
    seconds >= 3600 -> "${seconds / 3600} h" + (seconds % 3600 / 60).takeIf { it > 0 }?.let { " %02d".format(it) }.orEmpty()
    seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
    seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "$seconds s"
}
