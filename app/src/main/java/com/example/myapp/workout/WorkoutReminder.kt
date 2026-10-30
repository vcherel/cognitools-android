package com.example.myapp.workout

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.AppSnackbar
import com.example.myapp.MainActivity
import com.example.myapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val Context.workoutDataStore by preferencesDataStore("workout")

private val REMIND_AT = longPreferencesKey("remind_at")
private val SESSIONS = stringPreferencesKey("sessions")
private val DUE = booleanPreferencesKey("due")
private val WEEKDAY_MINUTES = intPreferencesKey("weekday_minutes")
private val WEEKEND_MINUTES = intPreferencesKey("weekend_minutes")

/**
 * [sessions] is every session marked done, newest first. [due] is true from the moment the
 * reminder went off until the session is done or snoozed.
 */
data class WorkoutState(
    val remindAt: Long?,
    val sessions: List<Long>,
    val due: Boolean,
    val times: ReminderTimes
) {
    val lastDone: Long? get() = sessions.firstOrNull()
}

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

private fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun LocalTime.toMinutes() = hour * 60 + minute

private fun Int.toLocalTime(): LocalTime = LocalTime.of(this / 60, this % 60)

private fun Preferences.toState() = WorkoutState(
    remindAt = this[REMIND_AT],
    sessions = this[SESSIONS].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.sortedDescending(),
    due = this[DUE] ?: false,
    times = ReminderTimes(
        this[WEEKDAY_MINUTES]?.toLocalTime() ?: ReminderTimes.DEFAULT.weekday,
        this[WEEKEND_MINUTES]?.toLocalTime() ?: ReminderTimes.DEFAULT.weekend
    )
)

private fun MutablePreferences.write(state: WorkoutState) {
    state.remindAt?.let { this[REMIND_AT] = it } ?: remove(REMIND_AT)
    this[SESSIONS] = state.sessions.joinToString(",")
    this[DUE] = state.due
    this[WEEKDAY_MINUTES] = state.times.weekday.toMinutes()
    this[WEEKEND_MINUTES] = state.times.weekend.toMinutes()
}

/**
 * The workout reminder: one exact alarm at the next reminder time, and the notification it posts.
 * An ignored reminder comes back at the next day's slot; a session marked done pushes it past the
 * recovery days.
 */
object WorkoutReminder {
    private const val NOTIFICATION_ID = 1004
    private const val CHANNEL_ID = "workout"

    fun state(context: Context): Flow<WorkoutState> = context.workoutDataStore.data.map { it.toState() }

    suspend fun markDone(context: Context) {
        val now = LocalDateTime.now()
        update(context) {
            it.copy(
                remindAt = reminderAfterSession(now, it.times).toMillis(),
                sessions = listOf(now.toMillis()) + it.sessions
            )
        }
    }

    suspend fun snoozeHour(context: Context) =
        update(context) { it.copy(remindAt = reminderSnoozedHour(LocalDateTime.now()).toMillis()) }

    suspend fun snoozeDay(context: Context) =
        update(context) { it.copy(remindAt = reminderSnoozedDay(LocalDateTime.now(), it.times).toMillis()) }

    /** Changes the reminder times, moving a pending reminder that sat on the old slot. */
    suspend fun setTimes(context: Context, times: ReminderTimes) {
        val state = edit(context) { old ->
            old.copy(
                times = times,
                remindAt = old.remindAt?.let { reminderWithNewTimes(it.toLocalDateTime(), old.times, times).toMillis() }
            )
        }
        reschedule(context, state)
    }

    /** Logs a session from the app, with an undo snackbar for a tap made by mistake. */
    suspend fun markDoneWithUndo(context: Context) {
        val previous = state(context).first()
        markDone(context)
        AppSnackbar.show("Séance enregistrée", "Annuler") { reschedule(context, edit(context) { previous }) }
    }

    /**
     * Arms the alarm again, at app start and after a reboot (which drops every alarm). A reminder
     * missed earlier today still goes off now; one missed on an earlier day moves to the next slot.
     */
    suspend fun reschedule(context: Context, current: WorkoutState? = null) {
        val now = LocalDateTime.now()
        val state = current ?: state(context).first()
        val remindAt = state.remindAt?.toLocalDateTime()
        val target = when {
            remindAt == null -> nextSlotAfter(now, state.times)
            remindAt.isBefore(now) && remindAt.toLocalDate() != now.toLocalDate() -> nextSlotAfter(now, state.times)
            else -> remindAt
        }
        if (target != remindAt) edit(context) { it.copy(remindAt = target.toMillis()) }
        setAlarm(context, target.toMillis())
    }

    internal suspend fun onAlarm(context: Context) {
        val state = edit(context) { it.copy(remindAt = nextSlotAfter(LocalDateTime.now(), it.times).toMillis(), due = true) }
        setAlarm(context, state.remindAt!!)
        notify(context, state.lastDone)
    }

    private suspend fun edit(context: Context, change: (WorkoutState) -> WorkoutState): WorkoutState {
        var result: WorkoutState? = null
        context.workoutDataStore.edit { prefs ->
            result = change(prefs.toState()).also { prefs.write(it) }
        }
        return result!!
    }

    private suspend fun update(context: Context, change: (WorkoutState) -> WorkoutState) {
        val state = edit(context) { change(it).copy(due = false) }
        setAlarm(context, state.remindAt!!)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun setAlarm(context: Context, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = receiverIntent(context, ACTION_REMIND, 0)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
    }

    private fun notify(context: Context, lastDone: Long?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.workout_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, "workout"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = lastSessionText(lastDone?.toLocalDateTime(), LocalDate.now())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fitness)
            .setContentTitle("C'est l'heure de la muscu")
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "C'est fait", receiverIntent(context, ACTION_DONE, 1))
            .addAction(0, "Dans 1 h", receiverIntent(context, ACTION_SNOOZE_HOUR, 2))
            .addAction(0, "Demain", receiverIntent(context, ACTION_SNOOZE_DAY, 3))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun receiverIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, WorkoutReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

private const val ACTION_REMIND = "com.example.myapp.workout.REMIND"
private const val ACTION_DONE = "com.example.myapp.workout.DONE"
private const val ACTION_SNOOZE_HOUR = "com.example.myapp.workout.SNOOZE_HOUR"
private const val ACTION_SNOOZE_DAY = "com.example.myapp.workout.SNOOZE_DAY"

/** The alarm, the notification buttons, and the reboot or app update that re-arms the alarm. */
class WorkoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REMIND -> WorkoutReminder.onAlarm(app)
                    ACTION_DONE -> WorkoutReminder.markDone(app)
                    ACTION_SNOOZE_HOUR -> WorkoutReminder.snoozeHour(app)
                    ACTION_SNOOZE_DAY -> WorkoutReminder.snoozeDay(app)
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> WorkoutReminder.reschedule(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
