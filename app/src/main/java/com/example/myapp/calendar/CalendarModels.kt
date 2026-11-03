package com.example.myapp.calendar

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class CalendarKind { TASK, BIRTHDAY, EVENT }

/**
 * WEEKLY and MONTHLY count from the day the task was checked, WEEKLY_ON_DAY and MONTHLY_ON_DAY keep
 * the day it was set for, MONTH_END is the last day of every month. [label] is the dialog's menu entry.
 */
enum class Recurrence(val label: String) {
    NONE("Never"),
    WEEKLY_ON_DAY("Every week"),
    WEEKLY("1 week after checking it"),
    MONTHLY_ON_DAY("Every month"),
    MONTHLY("1 month after checking it"),
    MONTH_END("Last day of the month")
}

/**
 * One task, birthday or event. [date] is ISO; a birthday's is the birth date, its year 2000 (a leap
 * year, so 29 February stays valid) when [yearKnown] is false. [endDate] is an event's last day, after
 * [date], null for a one day event. [time] and [endTime] are "HH:mm", the start on the first day and
 * the end on the last. [wishedYear] is the last year a birthday was marked wished, which skips that
 * year's morning reminder.
 * A repeating task ([recurrence]) is never done: checking it moves [date] to its next time.
 * [snoozedUntil] is the ISO date time a task's "Dans 1 h" put its reminder off to, cleared by any edit.
 * [plannedDate] is the day a task was first set for once it has been pushed later, null until then.
 */
@Entity(tableName = "calendar_items")
data class CalendarItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: CalendarKind,
    val title: String,
    val date: String,
    val time: String? = null,
    val endTime: String? = null,
    val yearKnown: Boolean = true,
    val done: Boolean = false,
    val wishedYear: Int? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val snoozedUntil: String? = null,
    val endDate: String? = null,
    val plannedDate: String? = null
)

const val UNKNOWN_BIRTH_YEAR = 2000

fun CalendarItem.localDate(): LocalDate = LocalDate.parse(date)

val CalendarItem.repeats: Boolean get() = recurrence != Recurrence.NONE

/** The item's last day, [date] itself unless it is an event over several days. */
fun CalendarItem.lastDate(): LocalDate = endDate?.let(LocalDate::parse) ?: localDate()

fun CalendarItem.localTime(): LocalTime? = time?.let { LocalTime.parse(it) }

fun CalendarItem.localEndTime(): LocalTime? = endTime?.let { LocalTime.parse(it) }

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_items")
    fun observeAll(): Flow<List<CalendarItem>>

    @Query("SELECT * FROM calendar_items")
    suspend fun getAll(): List<CalendarItem>

    @Query("SELECT * FROM calendar_items WHERE id = :id")
    suspend fun get(id: String): CalendarItem?

    @Upsert suspend fun upsert(item: CalendarItem)

    @Query("DELETE FROM calendar_items WHERE id = :id")
    suspend fun delete(id: String)
}

fun calendarToJson(items: List<CalendarItem>): JSONArray = JSONArray().apply {
    items.forEach { item ->
        put(
            JSONObject()
                .put("id", item.id)
                .put("kind", item.kind.name)
                .put("title", item.title)
                .put("date", item.date)
                .put("endDate", item.endDate)
                .put("time", item.time)
                .put("endTime", item.endTime)
                .put("yearKnown", item.yearKnown)
                .put("done", item.done)
                .put("wishedYear", item.wishedYear)
                .put("recurrence", item.recurrence.name)
                .put("plannedDate", item.plannedDate)
        )
    }
}
