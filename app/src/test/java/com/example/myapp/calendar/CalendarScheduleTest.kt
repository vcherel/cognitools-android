package com.example.myapp.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarScheduleTest {
    private fun birthday(date: String, wishedYear: Int? = null) =
        CalendarItem(id = "b", kind = CalendarKind.BIRTHDAY, title = "Marie", date = date, wishedYear = wishedYear)

    @Test
    fun easterAndItsHolidays() {
        assertEquals(LocalDate.of(2026, 4, 5), easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), easterSunday(2027))
        val holidays = publicHolidays(2026)
        assertEquals(11, holidays.size)
        assertEquals("Ascension", holidays[LocalDate.of(2026, 5, 14)])
        assertEquals("Lundi de Pentecôte", holidays[LocalDate.of(2026, 5, 25)])
    }

    @Test
    fun birthdayHasThreeRemindersAndWishedSkipsTheMorning() {
        val from = LocalDateTime.of(2026, 1, 1, 0, 0)
        val to = LocalDateTime.of(2026, 12, 31, 23, 59)
        val all = remindersBetween(birthday("1996-11-10"), from, to).map { it.slot to it.at }
        assertEquals(
            listOf(
                ReminderSlot.WEEK_BEFORE to LocalDateTime.of(2026, 11, 3, 9, 0),
                ReminderSlot.EVE to LocalDateTime.of(2026, 11, 9, 23, 59),
                ReminderSlot.MORNING to LocalDateTime.of(2026, 11, 10, 9, 0)
            ),
            all
        )
        val wished = remindersBetween(birthday("1996-11-10", wishedYear = 2026), from, to)
        assertTrue(wished.none { it.slot == ReminderSlot.MORNING })
    }

    @Test
    fun earlyJanuaryBirthdayRemindsInDecember() {
        val now = LocalDateTime.of(2026, 12, 20, 12, 0)
        val next = nextReminder(listOf(birthday("2000-01-02")), now)!!
        assertEquals(LocalDateTime.of(2026, 12, 26, 9, 0), next.at)
    }

    @Test
    fun leapDayBirthdayFallsOnThe28th() {
        assertEquals(LocalDate.of(2027, 2, 28), birthday("2000-02-29").birthdayIn(2027))
    }

    @Test
    fun doneTaskAndEventWithoutReminderStayQuiet() {
        val now = LocalDateTime.of(2026, 11, 1, 8, 0)
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Garage", date = "2026-11-01", done = true)
        val event = CalendarItem(kind = CalendarKind.EVENT, title = "Concert", date = "2026-11-05", time = "20:30")
        assertEquals(null, nextReminder(listOf(task, event), now))
        val reminded = event.copy(reminderMinutes = 60)
        assertEquals(LocalDateTime.of(2026, 11, 5, 19, 30), nextReminder(listOf(reminded), now)!!.at)
    }

    @Test
    fun pendingTaskRemindsAgainEveryDayAndSnoozeMovesTheFirst() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Poubelles", date = "2026-11-02", time = "19:00")
        val from = LocalDateTime.of(2026, 11, 2, 0, 0)
        val to = LocalDateTime.of(2026, 11, 4, 23, 0)
        assertEquals(
            listOf(2, 3, 4).map { LocalDateTime.of(2026, 11, it, 19, 0) },
            remindersBetween(task, from, to).map { it.at }
        )
        val snoozed = task.copy(snoozedUntil = "2026-11-02T20:05")
        assertEquals(
            listOf(LocalDateTime.of(2026, 11, 2, 20, 5), LocalDateTime.of(2026, 11, 3, 19, 0)),
            remindersBetween(snoozed, LocalDateTime.of(2026, 11, 2, 19, 30), LocalDateTime.of(2026, 11, 3, 20, 0)).map { it.at }
        )
    }

    @Test
    fun weeklyTaskComesBackAWeekAfterItIsDone() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Poubelles", date = "2026-11-02", weekly = true)
        val done = task.copy(snoozedUntil = "2026-11-04T10:00").completed(LocalDate.of(2026, 11, 4))
        assertEquals("2026-11-11", done.date)
        assertEquals(false, done.done)
        assertEquals(null, done.snoozedUntil)
        assertTrue(task.copy(weekly = false).completed(LocalDate.of(2026, 11, 4)).done)
    }

    @Test
    fun lateTaskShowsOnlyOnToday() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Poubelles", date = "2026-11-02", weekly = true)
        val today = LocalDate.of(2026, 11, 5)
        assertTrue(task.occursOn(today, today))
        assertTrue(!task.occursOn(LocalDate.of(2026, 11, 2), today))
        assertTrue(!task.occursOn(LocalDate.of(2026, 11, 9), today))
        assertEquals(3L, task.daysLate(today))
        assertTrue(task.occursOn(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun schoolHolidaysSkipTeachersAndMarkers() {
        val json = """
            {"results": [
              {"description": "Vacances de la Toussaint", "population": "-", "start_date": "2025-10-17T22:00:00+00:00", "end_date": "2025-11-02T23:00:00+00:00"},
              {"description": "Vacances d'Été", "population": "Élèves", "start_date": "2026-07-03T22:00:00+00:00", "end_date": "2026-08-31T22:00:00+00:00"},
              {"description": "Vacances d'Été", "population": "Enseignants", "start_date": "2026-07-03T22:00:00+00:00", "end_date": "2026-08-30T22:00:00+00:00"},
              {"description": "Début des Vacances d'Été", "population": "-", "start_date": "2028-07-03T22:00:00+00:00", "end_date": "2028-07-03T22:00:00+00:00"}
            ]}
        """
        val holidays = parseSchoolHolidays(json)
        assertEquals(2, holidays.size)
        assertEquals(LocalDate.of(2025, 10, 18), holidays[0].start)
        assertEquals(LocalDate.of(2025, 11, 3), holidays[0].end)
        assertTrue(holidays[0].contains(LocalDate.of(2025, 11, 2)))
        assertTrue(!holidays[0].contains(LocalDate.of(2025, 11, 3)))
    }
}
