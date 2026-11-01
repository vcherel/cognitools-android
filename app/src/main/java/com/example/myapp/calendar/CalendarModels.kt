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
 * One task, birthday or event. [date] is ISO; a birthday's is the birth date, its year 2000 (a leap
 * year, so 29 February stays valid) when [yearKnown] is false. [time] and [endTime] are "HH:mm".
 * [reminderMinutes] is how long before an event its reminder goes off, none when null. [wishedYear]
 * is the last year a birthday was marked wished, which skips that year's morning reminder.
 */
@Entity(tableName = "calendar_items")
data class CalendarItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: CalendarKind,
    val title: String,
    val date: String,
    val time: String? = null,
    val endTime: String? = null,
    val place: String? = null,
    val yearKnown: Boolean = true,
    val reminderMinutes: Int? = null,
    val done: Boolean = false,
    val wishedYear: Int? = null
)

const val UNKNOWN_BIRTH_YEAR = 2000

fun CalendarItem.localDate(): LocalDate = LocalDate.parse(date)

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
                .put("time", item.time)
                .put("endTime", item.endTime)
                .put("place", item.place)
                .put("yearKnown", item.yearKnown)
                .put("reminderMinutes", item.reminderMinutes)
                .put("done", item.done)
                .put("wishedYear", item.wishedYear)
        )
    }
}
