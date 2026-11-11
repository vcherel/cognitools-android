package com.example.myapp.workout

import com.example.myapp.calendar.publicHolidays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** When the reminder goes off on a weekday and on the weekend. */
data class ReminderTimes(val weekday: LocalTime, val weekend: LocalTime) {
    companion object {
        val DEFAULT = ReminderTimes(LocalTime.of(19, 30), LocalTime.of(15, 0))
    }
}

/** Days off after a session: done on Monday, the next reminder comes Thursday. */
const val RECOVERY_DAYS = 2L

/** From this many days without a session, the reminder says how long it has been. */
const val LONG_BREAK_DAYS = 4L

/** A French public holiday reminds at the weekend time too. */
fun LocalDate.isDayOff() =
    dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY || this in publicHolidays(year)

fun slotOn(date: LocalDate, times: ReminderTimes): LocalDateTime =
    date.atTime(if (date.isDayOff()) times.weekend else times.weekday)

/** Today's slot while it is still ahead, tomorrow's otherwise. */
fun nextSlotAfter(now: LocalDateTime, times: ReminderTimes): LocalDateTime {
    val today = slotOn(now.toLocalDate(), times)
    return if (today.isAfter(now)) today else slotOn(now.toLocalDate().plusDays(1), times)
}

/** A session given up earns one recovery day less. */
fun reminderAfterSession(now: LocalDateTime, times: ReminderTimes, gaveUp: Boolean = false): LocalDateTime =
    slotOn(now.toLocalDate().plusDays(RECOVERY_DAYS + 1 - if (gaveUp) 1 else 0), times)

/** How long the "Dans 1 h" snooze puts the reminder off. */
const val SNOOZE_MINUTES = 60L

fun reminderSnoozed(now: LocalDateTime): LocalDateTime = now.plusMinutes(SNOOZE_MINUTES)

fun reminderSnoozedDay(now: LocalDateTime, times: ReminderTimes): LocalDateTime =
    slotOn(now.toLocalDate().plusDays(1), times)

/** Whether [date] can be picked as the day the reminder comes back: its slot is still ahead. */
fun canRemindOn(date: LocalDate, now: LocalDateTime, times: ReminderTimes): Boolean =
    slotOn(date, times).isAfter(now)

/**
 * The reminder moved along when the times change: one sitting on its day's old slot takes the new
 * slot, one set by a "Dans 1 h" snooze stays where it is.
 */
fun reminderWithNewTimes(remindAt: LocalDateTime, old: ReminderTimes, new: ReminderTimes): LocalDateTime =
    if (remindAt == slotOn(remindAt.toLocalDate(), old)) slotOn(remindAt.toLocalDate(), new) else remindAt

/** Whole calendar days between the last session and [today], null without any session. */
fun daysSinceSession(lastDone: LocalDateTime?, today: LocalDate): Long? =
    lastDone?.let { ChronoUnit.DAYS.between(it.toLocalDate(), today) }

/**
 * The days the next session sits past the one planned after the last session ([RECOVERY_DAYS] off),
 * by snoozes, a picked day or reminders left unanswered. 0 without any session.
 */
fun daysPushed(lastDone: LocalDateTime?, nextDay: LocalDate): Long =
    lastDone?.let { ChronoUnit.DAYS.between(it.toLocalDate().plusDays(RECOVERY_DAYS + 1), nextDay) }?.coerceAtLeast(0) ?: 0

fun sessionsInMonth(sessions: List<LocalDateTime>, today: LocalDate) =
    sessions.count { it.year == today.year && it.month == today.month }

fun sessionsInYear(sessions: List<LocalDateTime>, today: LocalDate) =
    sessions.count { it.year == today.year }
