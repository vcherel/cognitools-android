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
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.plural
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

const val WAITING_SECTION = "Attente"

/** The unchecked items of the Todo list: the active block above the first separator, and the "Attente" section. */
data class TodoItems(val toDo: List<String>, val waiting: List<String>)

fun todoItems(content: String): TodoItems {
    val toDo = mutableListOf<String>()
    val waiting = mutableListOf<String>()
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
        }
    }
    return TodoItems(toDo, waiting)
}

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

enum class TodoReminder(val mean: Duration, val notificationId: Int, val action: String) {
    TO_DO(Duration.ofHours(6), 1005, "com.example.myapp.notes.REMIND_TODO"),
    WAITING(Duration.ofDays(3), 1006, "com.example.myapp.notes.REMIND_WAITING");

    private val key = longPreferencesKey("next_$name")

    private fun items(all: TodoItems) = if (this == TO_DO) all.toDo else all.waiting

    private fun title(count: Int) =
        if (this == TO_DO) "$count tâche${plural(count)} à faire" else "$count tâche${plural(count)} en attente"

    companion object {
        private const val CHANNEL_ID = "todo"

        /** Arms both alarms again at app start and after a reboot; a reminder missed meanwhile is drawn anew. */
        suspend fun rescheduleAll(context: Context) {
            val prefs = context.todoReminderStore.data.first()
            val now = System.currentTimeMillis()
            entries.forEach { kind ->
                val stored = prefs[kind.key]
                if (stored != null && stored > now) kind.setAlarm(context, stored) else kind.scheduleNext(context)
            }
        }

        internal suspend fun onAlarm(context: Context, action: String?) {
            val kind = entries.firstOrNull { it.action == action } ?: return
            kind.scheduleNext(context)
            val note = findTodoNote(AppDatabase.get(context).noteDao()) ?: return
            val items = kind.items(todoItems(note.content))
            if (items.isNotEmpty()) kind.notify(context, note.id, items)
        }
    }

    private suspend fun scheduleNext(context: Context) {
        val at = nextTodoReminder(LocalDateTime.now(), mean, Random.Default)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.todoReminderStore.edit { it[key] = at }
        setAlarm(context, at)
    }

    private fun setAlarm(context: Context, atMillis: Long) {
        val intent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, TodoReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
    }

    private fun notify(context: Context, noteId: String, items: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.todo_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, notificationId,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, "note/$noteId"),
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

/** The two reminder alarms, and the reboot or app update that re-arms them. */
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
