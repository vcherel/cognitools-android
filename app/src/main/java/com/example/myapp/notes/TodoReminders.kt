package com.example.myapp.notes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.MainActivity
import com.example.myapp.R
import com.example.myapp.calendar.pendingTasksOn
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.plural
import com.example.myapp.workout.ReminderTimes
import com.example.myapp.workout.WorkoutReminder
import com.example.myapp.workout.nextSlotAfter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

const val WAITING_SECTION = "Attente"
const val WEEKEND_SECTION = "Week-end"

/** The unchecked items of the Todo list: the active block above the first separator, and the "Attente" and "Week-end" sections. */
data class TodoItems(val toDo: List<String>, val waiting: List<String>, val weekend: List<String>)

fun todoItems(content: String): TodoItems {
    val toDo = mutableListOf<String>()
    val waiting = mutableListOf<String>()
    val weekend = mutableListOf<String>()
    var section: String? = null
    for (line in content.split("\n")) {
        if (line.isSeparatorLine()) {
            section = line.separatorName()
            continue
        }
        if (!line.isCheckboxLine() || line.isCheckedLine()) continue
        val text = line.checkboxText().trim()
        when {
            section == null -> toDo += text
            section.equals(WAITING_SECTION, ignoreCase = true) -> waiting += text
            section.equals(WEEKEND_SECTION, ignoreCase = true) -> weekend += text
        }
    }
    return TodoItems(toDo, waiting, weekend)
}

private val WEEKEND_START = LocalTime.of(18, 0)

/** The next Friday at 18:00 strictly after [after]. */
fun nextWeekendReminder(after: LocalDateTime): LocalDateTime {
    val friday = after.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).atTime(WEEKEND_START)
    return if (friday.isAfter(after)) friday else friday.plusWeeks(1)
}

private const val AFTER_WORKOUT_HOURS = 1L

/** An hour after the workout slot (whether or not the workout reminder goes off that day), strictly after [after]. */
fun nextDailyTodoReminder(after: LocalDateTime, times: ReminderTimes): LocalDateTime =
    nextSlotAfter(after.minusHours(AFTER_WORKOUT_HOURS), times).plusHours(AFTER_WORKOUT_HOURS)

private val DAY_START = LocalTime.of(9, 0)
private val DAY_END = LocalTime.of(22, 0)
private const val MORNING_SPREAD_MINUTES = 180L

/**
 * The next reminder, drawn uniformly between half and one and a half times [mean] after [after],
 * and moved to a random time in the next morning when it lands at night.
 */
fun nextTodoReminder(after: LocalDateTime, mean: Duration, random: Random): LocalDateTime {
    val minutes = mean.toMinutes()
    val drawn = after.plusMinutes(random.nextLong(minutes / 2, minutes * 3 / 2 + 1))
    val time = drawn.toLocalTime()
    if (!time.isBefore(DAY_START) && time.isBefore(DAY_END)) return drawn
    val day = if (time.isBefore(DAY_START)) drawn.toLocalDate() else drawn.toLocalDate().plusDays(1)
    return day.atTime(DAY_START).plusMinutes(random.nextLong(MORNING_SPREAD_MINUTES))
}

private val Context.todoReminderStore by preferencesDataStore("todo_reminders")

enum class TodoReminder(val notificationId: Int, val action: String) {
    TO_DO(1005, "com.example.myapp.notes.REMIND_TODO"),
    WAITING(1006, "com.example.myapp.notes.REMIND_WAITING"),
    WEEKEND(1007, "com.example.myapp.notes.REMIND_WEEKEND");

    private val key = longPreferencesKey("next_$name")

    private fun items(all: TodoItems) = when (this) {
        TO_DO -> all.toDo
        WAITING -> all.waiting
        WEEKEND -> all.weekend
    }

    private fun title(count: Int) = when (this) {
        TO_DO -> "$count task${plural(count)} to do"
        WAITING -> "$count task${plural(count)} waiting"
        WEEKEND -> "$count idea${plural(count)} for the weekend"
    }

    private suspend fun nextAt(context: Context, now: LocalDateTime): LocalDateTime = when (this) {
        TO_DO -> nextDailyTodoReminder(now, WorkoutReminder.state(context).first().times)
        WAITING -> nextTodoReminder(now, Duration.ofDays(3), Random.Default)
        WEEKEND -> nextWeekendReminder(now)
    }

    companion object {
        private const val CHANNEL_ID = "todo"

        /**
         * Arms every alarm again at app start and after a reboot; a reminder missed meanwhile is drawn anew.
         * The daily one is always recomputed, so it follows the workout times.
         */
        suspend fun rescheduleAll(context: Context) {
            val prefs = context.todoReminderStore.data.first()
            val now = System.currentTimeMillis()
            entries.forEach { kind ->
                val stored = prefs[kind.key]
                if (kind != TO_DO && stored != null && stored > now) kind.setAlarm(context, stored) else kind.scheduleNext(context)
            }
        }

        /** Moves the daily reminder after the workout times change. */
        suspend fun rescheduleDaily(context: Context) = TO_DO.scheduleNext(context)

        /** The daily one also lists the calendar tasks still to do today. */
        internal suspend fun onAlarm(context: Context, action: String?) {
            val kind = entries.firstOrNull { it.action == action } ?: return
            kind.scheduleNext(context)
            val db = AppDatabase.get(context)
            val note = findTodoNote(db.noteDao())
            val listed = note?.let { kind.items(todoItems(it.content)) }.orEmpty()
            val today = LocalDate.now()
            val tasks = if (kind == TO_DO) pendingTasksOn(db.calendarDao().getAll(), today).map { it.title } else emptyList()
            val items = (listed + tasks).distinct()
            if (items.isEmpty()) return
            val route = if (note != null && listed.isNotEmpty()) "note/${note.id}" else "calendar?day=$today"
            kind.notify(context, route, items)
        }
    }

    private suspend fun scheduleNext(context: Context) {
        val at = nextAt(context, LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.todoReminderStore.edit { it[key] = at }
        setAlarm(context, at)
    }

    private fun setAlarm(context: Context, atMillis: Long) {
        val intent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, TodoReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (this == TO_DO && alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
    }

    private fun notify(context: Context, route: String, items: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.todo_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, notificationId,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = items.joinToString(", ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_checklist)
            .setContentTitle(title(items.size))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(items.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        manager.notify(notificationId, notification)
    }
}

/** The reminder alarms, and the reboot or app update that re-arms them. */
class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> TodoReminder.rescheduleAll(app)
                    else -> TodoReminder.onAlarm(app, intent.action)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
