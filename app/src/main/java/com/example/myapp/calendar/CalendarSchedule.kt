package com.example.myapp.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/** When a task or an event without a time is reminded (an event the day before), and when birthdays are. */
val MORNING: LocalTime = LocalTime.of(9, 0)
private val EVE: LocalTime = LocalTime.of(23, 59)

enum class ReminderSlot { AT_TIME, WEEK_BEFORE, EVE, MORNING }

data class Reminder(val item: CalendarItem, val slot: ReminderSlot, val at: LocalDateTime)

/** A birthday's day in [year]; 29 February falls on the 28th outside leap years. */
fun CalendarItem.birthdayIn(year: Int): LocalDate =
    localDate().let { MonthDay.of(it.month, it.dayOfMonth).atYear(year) }

fun CalendarItem.ageIn(year: Int): Int? = if (yearKnown) year - localDate().year else null

/** A task left unchecked on an earlier day, shown on today instead until it is done. */
fun CalendarItem.isOverdue(today: LocalDate): Boolean =
    kind == CalendarKind.TASK && !done && localDate().isBefore(today)

/**
 * The days [item] shows on: a late task leaves its own day for today, a repeating one shows only its
 * next time, an event over several days shows on each of them.
 */
fun CalendarItem.occursOn(day: LocalDate, today: LocalDate): Boolean = when {
    kind == CalendarKind.BIRTHDAY -> birthdayIn(day.year) == day
    isOverdue(today) -> day == today
    else -> !day.isBefore(localDate()) && !day.isAfter(lastDate())
}

fun CalendarItem.daysLate(today: LocalDate): Long = ChronoUnit.DAYS.between(localDate(), today)

/** The days a task has been pushed past the day it was first set for. */
fun CalendarItem.daysPushed(): Long =
    plannedDate?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), localDate()) }?.coerceAtLeast(0) ?: 0

/** The task moved to [newDate], keeping the day it was first set for unless it moves back to or before it. */
fun CalendarItem.movedTo(newDate: LocalDate): CalendarItem {
    val planned = plannedDate?.let(LocalDate::parse) ?: localDate()
    return copy(date = newDate.toString(), plannedDate = planned.takeIf { newDate.isAfter(it) }?.toString())
}

fun LocalDate.endOfMonth(): LocalDate = withDayOfMonth(lengthOfMonth())

/**
 * A task checked on [today]; a repeating one comes back instead of staying done. One that keeps its day
 * moves to its first time after today, skipping the ones a late check missed.
 */
fun CalendarItem.completed(today: LocalDate): CalendarItem {
    fun after(step: (LocalDate) -> LocalDate) = generateSequence(step(localDate()), step).first { it.isAfter(today) }
    val every = repeatEvery.coerceAtLeast(1).toLong()
    val next = when (recurrence) {
        Recurrence.NONE -> return copy(done = true, snoozedUntil = null)
        Recurrence.WEEKLY -> today.plusWeeks(every)
        Recurrence.MONTHLY -> today.plusMonths(every)
        Recurrence.WEEKLY_ON_DAY -> after { it.plusWeeks(every) }
        Recurrence.MONTHLY_ON_DAY -> after { it.plusMonths(every) }
        Recurrence.MONTH_END -> after { it.plusMonths(1).endOfMonth() }
    }
    return copy(date = next.toString(), snoozedUntil = null, plannedDate = null)
}

/** How long a task's "Dans 1 h" puts its reminder off. */
const val TASK_SNOOZE_MINUTES = 60L

/**
 * A pending task reminds at its time (or a snooze's), then again every day at that time until it is
 * checked, like the Muscu reminder.
 */
private fun taskReminders(item: CalendarItem, to: LocalDateTime): Sequence<LocalDateTime> {
    val time = item.localTime() ?: MORNING
    val first = item.snoozedUntil?.let(LocalDateTime::parse) ?: item.localDate().atTime(time)
    val daily = generateSequence(first.toLocalDate().plusDays(1).atTime(time)) { it.plusDays(1) }
    return (sequenceOf(first) + daily).takeWhile { !it.isAfter(to) }
}

/** Every reminder of [item] in (from, to]. */
fun remindersBetween(item: CalendarItem, from: LocalDateTime, to: LocalDateTime): List<Reminder> {
    val candidates = when (item.kind) {
        CalendarKind.TASK ->
            if (item.done) emptyList()
            else taskReminders(item, to).filter { it.isAfter(from) }.map { Reminder(item, ReminderSlot.AT_TIME, it) }.toList()
        CalendarKind.EVENT ->
            listOf(Reminder(item, ReminderSlot.AT_TIME, item.localDate().minusDays(1).atTime(item.localTime() ?: MORNING)))
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

/** When the evening digest announces the next day's tasks and events. */
val DIGEST: LocalTime = LocalTime.of(20, 0)

/** What the digest on the eve of [day] lists: its pending tasks and the events starting on it, by time. */
fun digestItems(items: List<CalendarItem>, day: LocalDate): List<CalendarItem> =
    items.filter {
        (it.kind == CalendarKind.EVENT || it.kind == CalendarKind.TASK && !it.done) && it.localDate() == day
    }.sortedBy { it.time ?: "" }

private fun digestTimes(items: List<CalendarItem>): List<LocalDateTime> =
    items.filter { it.kind == CalendarKind.EVENT || it.kind == CalendarKind.TASK && !it.done }
        .map { it.localDate().minusDays(1).atTime(DIGEST) }
        .distinct()

/** The days whose digest falls in (from, to]. */
fun digestsBetween(items: List<CalendarItem>, from: LocalDateTime, to: LocalDateTime): List<LocalDate> =
    digestTimes(items).filter { it.isAfter(from) && !it.isAfter(to) }.map { it.toLocalDate().plusDays(1) }.sorted()

/** The first digest after [now]. */
fun nextDigest(items: List<CalendarItem>, now: LocalDateTime): LocalDateTime? =
    digestTimes(items).filter { it.isAfter(now) }.minOrNull()

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
        LocalDate.of(year, 1, 1) to "New Year's Day",
        easter.plusDays(1) to "Easter Monday",
        LocalDate.of(year, 5, 1) to "Labour Day",
        LocalDate.of(year, 5, 8) to "Victory in Europe Day",
        easter.plusDays(39) to "Ascension Day",
        easter.plusDays(50) to "Whit Monday",
        LocalDate.of(year, 7, 14) to "Bastille Day",
        LocalDate.of(year, 8, 15) to "Assumption Day",
        LocalDate.of(year, 11, 1) to "All Saints' Day",
        LocalDate.of(year, 11, 11) to "Armistice Day",
        LocalDate.of(year, 12, 25) to "Christmas"
    )
}
