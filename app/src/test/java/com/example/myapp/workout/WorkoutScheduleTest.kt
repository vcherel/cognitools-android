package com.example.myapp.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class WorkoutScheduleTest {
    // 2026-10-26 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val times = ReminderTimes.DEFAULT

    @Test
    fun `weekdays remind at 19h30 and weekends at 15h`() {
        assertEquals(at(26, 19, 30), slotOn(at(26, 0).toLocalDate(), times))
        assertEquals(at(31, 15), slotOn(at(31, 0).toLocalDate(), times))
        assertEquals(LocalDateTime.of(2026, 11, 1, 15, 0), slotOn(at(31, 0).toLocalDate().plusDays(1), times))
    }

    @Test
    fun `the next slot is today until it has passed`() {
        assertEquals(at(26, 19, 30), nextSlotAfter(at(26, 12), times))
        assertEquals(at(27, 19, 30), nextSlotAfter(at(26, 19, 30), times))
        assertEquals(at(31, 15), nextSlotAfter(at(30, 21), times))
    }

    @Test
    fun `a session on Monday leaves Tuesday and Wednesday off`() {
        assertEquals(at(29, 19, 30), reminderAfterSession(at(26, 20), times))
        assertEquals(LocalDateTime.of(2026, 11, 1, 15, 0), reminderAfterSession(at(29, 20), times))
    }

    @Test
    fun `snoozing a day lands on tomorrow's slot`() {
        assertEquals(at(31, 15), reminderSnoozedDay(at(30, 19, 30), times))
        assertEquals(at(26, 20, 45), reminderSnoozedHour(at(26, 19, 45)))
    }

    @Test
    fun `new times move a slot reminder but not a one hour snooze`() {
        val later = times.copy(weekday = LocalTime.of(20, 0))
        assertEquals(at(29, 20), reminderWithNewTimes(at(29, 19, 30), times, later))
        assertEquals(at(29, 18, 10), reminderWithNewTimes(at(29, 18, 10), times, later))
        assertEquals(at(31, 15), reminderWithNewTimes(at(31, 15), times, later))
    }

    @Test
    fun `days since the last session and the counts`() {
        assertNull(daysSinceSession(null, at(30, 0).toLocalDate()))
        assertEquals(4L, daysSinceSession(at(26, 23), at(30, 8).toLocalDate()))
        val sessions = listOf(at(30, 20), at(27, 20), LocalDateTime.of(2026, 9, 30, 20, 0), LocalDateTime.of(2025, 10, 1, 20, 0))
        assertEquals(2, sessionsInMonth(sessions, at(30, 0).toLocalDate()))
        assertEquals(3, sessionsInYear(sessions, at(30, 0).toLocalDate()))
    }
}
