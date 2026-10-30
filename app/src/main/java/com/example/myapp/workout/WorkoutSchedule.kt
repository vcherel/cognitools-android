package com.example.myapp.workout

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

fun LocalDate.isWeekend() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

fun slotOn(date: LocalDate, times: ReminderTimes): LocalDateTime =
    date.atTime(if (date.isWeekend()) times.weekend else times.weekday)

/** Today's slot while it is still ahead, tomorrow's otherwise. */
fun nextSlotAfter(now: LocalDateTime, times: ReminderTimes): LocalDateTime {
    val today = slotOn(now.toLocalDate(), times)
    return if (today.isAfter(now)) today else slotOn(now.toLocalDate().plusDays(1), times)
}

fun reminderAfterSession(now: LocalDateTime, times: ReminderTimes): LocalDateTime =
    slotOn(now.toLocalDate().plusDays(RECOVERY_DAYS + 1), times)

fun reminderSnoozedHour(now: LocalDateTime): LocalDateTime = now.plusHours(1)

fun reminderSnoozedDay(now: LocalDateTime, times: ReminderTimes): LocalDateTime =
    slotOn(now.toLocalDate().plusDays(1), times)

/**
 * The reminder moved along when the times change: one sitting on its day's old slot takes the new
 * slot, one set by a one hour snooze stays where it is.
 */
fun reminderWithNewTimes(remindAt: LocalDateTime, old: ReminderTimes, new: ReminderTimes): LocalDateTime =
    if (remindAt == slotOn(remindAt.toLocalDate(), old)) slotOn(remindAt.toLocalDate(), new) else remindAt

/** Whole calendar days between the last session and [today], null without any session. */
fun daysSinceSession(lastDone: LocalDateTime?, today: LocalDate): Long? =
    lastDone?.let { ChronoUnit.DAYS.between(it.toLocalDate(), today) }

fun sessionsInMonth(sessions: List<LocalDateTime>, today: LocalDate) =
    sessions.count { it.year == today.year && it.month == today.month }

fun sessionsInYear(sessions: List<LocalDateTime>, today: LocalDate) =
    sessions.count { it.year == today.year }
