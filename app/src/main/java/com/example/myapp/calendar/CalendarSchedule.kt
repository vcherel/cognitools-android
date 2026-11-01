package com.example.myapp.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay

/** When a task or an event without a time is reminded, and when birthdays are. */
val MORNING: LocalTime = LocalTime.of(9, 0)
private val EVE: LocalTime = LocalTime.of(23, 59)

enum class ReminderSlot { AT_TIME, WEEK_BEFORE, EVE, MORNING }

data class Reminder(val item: CalendarItem, val slot: ReminderSlot, val at: LocalDateTime)

/** A birthday's day in [year]; 29 February falls on the 28th outside leap years. */
fun CalendarItem.birthdayIn(year: Int): LocalDate =
    localDate().let { MonthDay.of(it.month, it.dayOfMonth).atYear(year) }

fun CalendarItem.ageIn(year: Int): Int? = if (yearKnown) year - localDate().year else null

fun CalendarItem.occursOn(day: LocalDate): Boolean =
    if (kind == CalendarKind.BIRTHDAY) birthdayIn(day.year) == day else localDate() == day

/** A task left unchecked on an earlier day, shown again on today until it is done. */
fun CalendarItem.isOverdue(today: LocalDate): Boolean =
    kind == CalendarKind.TASK && !done && localDate().isBefore(today)

/** Every reminder of [item] in (from, to]. */
fun remindersBetween(item: CalendarItem, from: LocalDateTime, to: LocalDateTime): List<Reminder> {
    val candidates = when (item.kind) {
        CalendarKind.TASK ->
            if (item.done) emptyList()
            else listOf(Reminder(item, ReminderSlot.AT_TIME, item.localDate().atTime(item.localTime() ?: MORNING)))
        CalendarKind.EVENT -> item.reminderMinutes?.let { minutes ->
            val start = item.localDate().atTime(item.localTime() ?: MORNING)
            listOf(Reminder(item, ReminderSlot.AT_TIME, start.minusMinutes(minutes.toLong())))
        }.orEmpty()
        CalendarKind.BIRTHDAY -> (from.year..to.year + 1).flatMap { year ->
            val day = item.birthdayIn(year)
            listOfNotNull(
                Reminder(item, ReminderSlot.WEEK_BEFORE, day.minusDays(7).atTime(MORNING)),
                Reminder(item, ReminderSlot.EVE, day.minusDays(1).atTime(EVE)),
                if (item.wishedYear == year) null else Reminder(item, ReminderSlot.MORNING, day.atTime(MORNING))
            )
        }
    }
    return candidates.filter { it.at.isAfter(from) && !it.at.isAfter(to) }
}

/** The first reminder of any item after [now], within the next two years. */
fun nextReminder(items: List<CalendarItem>, now: LocalDateTime): Reminder? =
    items.flatMap { remindersBetween(it, now, now.plusYears(2)) }.minByOrNull { it.at }

/** Western Easter Sunday, by the anonymous Gregorian algorithm. */
fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}

/** The eleven French public holidays of [year]. */
fun publicHolidays(year: Int): Map<LocalDate, String> {
    val easter = easterSunday(year)
    return mapOf(
        LocalDate.of(year, 1, 1) to "Jour de l'an",
        easter.plusDays(1) to "Lundi de Pâques",
        LocalDate.of(year, 5, 1) to "Fête du Travail",
        LocalDate.of(year, 5, 8) to "Victoire 1945",
        easter.plusDays(39) to "Ascension",
        easter.plusDays(50) to "Lundi de Pentecôte",
        LocalDate.of(year, 7, 14) to "Fête nationale",
        LocalDate.of(year, 8, 15) to "Assomption",
        LocalDate.of(year, 11, 1) to "Toussaint",
        LocalDate.of(year, 11, 11) to "Armistice 1918",
        LocalDate.of(year, 12, 25) to "Noël"
    )
}
