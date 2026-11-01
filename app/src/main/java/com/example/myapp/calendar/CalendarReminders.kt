package com.example.myapp.calendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.AppSnackbar
import com.example.myapp.MainActivity
import com.example.myapp.R
import com.example.myapp.deaccented
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Context.calendarStore by preferencesDataStore("calendar")
private val LAST_CHECK = longPreferencesKey("last_check")

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/** "d'Anaïs", "de Marie". */
fun birthdayOf(name: String): String =
    if (name.deaccented().firstOrNull()?.let { it in "aeiouy" } == true) "d'$name" else "de $name"

fun birthdayTitle(item: CalendarItem) = "Anniversaire ${birthdayOf(item.title)}"

/**
 * Every write to the calendar goes through here, so the one alarm always points at the next
 * reminder due. Each check posts what came due since the previous one (at most [CATCH_UP_HOURS] old,
 * for a reboot or a dead alarm), then arms the alarm for the next.
 */
object CalendarReminders {
    private const val CHANNEL_ID = "calendar"
    private const val CATCH_UP_HOURS = 12L
    private val mutex = Mutex()

    private fun dao(context: Context) = AppDatabase.get(context).calendarDao()

    suspend fun save(context: Context, item: CalendarItem) {
        dao(context).upsert(item)
        refresh(context, notifyDue = false)
    }

    suspend fun deleteWithUndo(context: Context, item: CalendarItem) {
        dao(context).delete(item.id)
        refresh(context, notifyDue = false)
        AppSnackbar.show("Supprimé", "Annuler") { save(context, item) }
    }

    /** At app start and on boot: posts what an alarm lost to a reboot missed, then arms the next one. */
    suspend fun refresh(context: Context, notifyDue: Boolean = true) = mutex.withLock {
        val now = LocalDateTime.now()
        val items = dao(context).getAll()
        if (notifyDue) {
            val stored = context.calendarStore.data.first()[LAST_CHECK]?.toLocalDateTime()
            val from = maxOf(stored ?: now, now.minusHours(CATCH_UP_HOURS))
            items.flatMap { remindersBetween(it, from, now) }.forEach { notify(context, it) }
        }
        context.calendarStore.edit { it[LAST_CHECK] = now.toMillis() }
        val next = nextReminder(items, now)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = receiverIntent(context, ACTION_REMIND, null)
        if (next == null) {
            alarms.cancel(intent)
        } else if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toMillis(), intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toMillis(), intent)
        }
    }

    internal suspend fun onAction(context: Context, action: String, itemId: String, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
        val item = dao(context).get(itemId) ?: return
        val changed = when (action) {
            ACTION_DONE -> item.completed(LocalDate.now())
            ACTION_LATER -> item.copy(snoozedUntil = LocalDateTime.now().plusMinutes(TASK_SNOOZE_MINUTES).withNano(0).toString())
            ACTION_TOMORROW -> item.copy(date = LocalDate.now().plusDays(1).toString(), snoozedUntil = null)
            // Tapped on the eve, so the birthday is tomorrow's year (a 1 January one is next year's).
            ACTION_WISHED -> item.copy(wishedYear = LocalDate.now().plusDays(1).year)
            else -> return
        }
        save(context, changed)
    }

    private fun notify(context: Context, reminder: Reminder) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.calendar_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val item = reminder.item
        val id = notificationId(reminder)
        val day = when (item.kind) {
            CalendarKind.BIRTHDAY -> reminder.at.toLocalDate().let {
                if (reminder.slot == ReminderSlot.WEEK_BEFORE) it.plusDays(7) else if (reminder.slot == ReminderSlot.EVE) it.plusDays(1) else it
            }
            else -> item.localDate()
        }
        val (title, text) = texts(reminder, day)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, "calendar?day=$day"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (item.kind == CalendarKind.EVENT) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
        text?.let { builder.setContentText(it) }
        when {
            item.kind == CalendarKind.TASK -> builder
                .addAction(0, "Fait", receiverIntent(context, ACTION_DONE, item.id, id))
                .addAction(0, "Dans 1 h", receiverIntent(context, ACTION_LATER, item.id, id))
                .addAction(0, "Demain", receiverIntent(context, ACTION_TOMORROW, item.id, id))
            reminder.slot == ReminderSlot.EVE ->
                builder.addAction(0, "Souhaité", receiverIntent(context, ACTION_WISHED, item.id, id))
        }
        manager.notify(id, builder.build())
    }

    private fun texts(reminder: Reminder, day: LocalDate): Pair<String, String?> {
        val item = reminder.item
        val age = if (item.kind == CalendarKind.BIRTHDAY) item.ageIn(day.year) else null
        return when (reminder.slot) {
            ReminderSlot.WEEK_BEFORE ->
                "${birthdayTitle(item)} dans une semaine" to
                    listOfNotNull(dayFormat.format(day).replaceFirstChar { it.uppercase() }, age?.let { "$it ans" })
                        .joinToString(", ") + ". Pense au cadeau."
            ReminderSlot.EVE -> "${birthdayTitle(item)} à minuit" to age?.let { "$it ans demain" }
            ReminderSlot.MORNING -> "C'est l'anniversaire ${birthdayOf(item.title)}" to age?.let { "$it ans aujourd'hui" }
            ReminderSlot.AT_TIME -> item.title to itemSummary(item, LocalDate.now()).ifEmpty { null }
        }
    }

    private fun notificationId(reminder: Reminder) =
        2_000_000 + ((reminder.item.id + reminder.slot.name).hashCode() and 0x7fffffff) % 1_000_000

    // The data uri keeps one pending intent per item and button apart, since extras do not.
    private fun receiverIntent(context: Context, action: String, itemId: String?, notificationId: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, CalendarReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("cognitools-calendar://${itemId.orEmpty()}/$action"))
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

/**
 * "Demain de 20:30 à 22:00" or "Demain à 20:00 jusqu'au dimanche 9 novembre": when, the first day
 * said relative to [today].
 */
fun itemSummary(item: CalendarItem, today: LocalDate): String {
    val day = when (item.localDate()) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        else -> dayFormat.format(item.localDate()).replaceFirstChar { it.uppercase() }
    }
    val start = item.localTime()?.let(timeFormat::format)
    val end = item.localEndTime()?.let(timeFormat::format)
    val lastDay = item.endDate?.let { dayFormat.format(item.lastDate()) }
    val time = when {
        lastDay != null -> listOfNotNull(start?.let { "à $it" }, "jusqu'au $lastDay", end?.let { "à $it" }).joinToString(" ")
        start != null && end != null -> "de $start à $end"
        start != null -> "à $start"
        else -> null
    }
    return listOfNotNull(day, time).joinToString(" ")
}

private fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

private fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private const val ACTION_REMIND = "com.example.myapp.calendar.REMIND"
private const val ACTION_DONE = "com.example.myapp.calendar.DONE"
private const val ACTION_LATER = "com.example.myapp.calendar.LATER"
private const val ACTION_TOMORROW = "com.example.myapp.calendar.TOMORROW"
private const val ACTION_WISHED = "com.example.myapp.calendar.WISHED"
private const val EXTRA_ITEM_ID = "itemId"
private const val EXTRA_NOTIFICATION_ID = "notificationId"

/** The alarm, the notification buttons, and the reboot or app update that re-arms the alarm. */
class CalendarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (val action = intent.action) {
                    ACTION_REMIND, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                        CalendarReminders.refresh(app)
                    else -> {
                        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return@launch
                        CalendarReminders.onAction(app, action ?: return@launch, itemId, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
