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
        assertEquals("Ascension Day", holidays[LocalDate.of(2026, 5, 14)])
        assertEquals("Whit Monday", holidays[LocalDate.of(2026, 5, 25)])
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
    fun doneTaskStaysQuietAndEventRemindsTheDayBefore() {
        val now = LocalDateTime.of(2026, 11, 1, 8, 0)
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Garage", date = "2026-11-01", done = true)
        assertEquals(null, nextReminder(listOf(task), now))
        val event = CalendarItem(kind = CalendarKind.EVENT, title = "Concert", date = "2026-11-05", time = "20:30")
        assertEquals(LocalDateTime.of(2026, 11, 4, 20, 30), nextReminder(listOf(event), now)!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), nextReminder(listOf(event.copy(time = null)), now)!!.at)
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
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Poubelles", date = "2026-11-02", recurrence = Recurrence.WEEKLY)
        val done = task.copy(snoozedUntil = "2026-11-04T10:00").completed(LocalDate.of(2026, 11, 4))
        assertEquals("2026-11-11", done.date)
        assertEquals(false, done.done)
        assertEquals(null, done.snoozedUntil)
        assertTrue(task.copy(recurrence = Recurrence.NONE).completed(LocalDate.of(2026, 11, 4)).done)
        val monthly = task.copy(recurrence = Recurrence.MONTHLY).completed(LocalDate.of(2026, 11, 4))
        assertEquals("2026-12-04", monthly.date)
        assertEquals(false, monthly.done)
    }

    @Test
    fun repeatEveryStepsSeveralWeeksOrMonths() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Draps", date = "2026-11-02", recurrence = Recurrence.WEEKLY, repeatEvery = 2)
        assertEquals("2026-11-18", task.completed(LocalDate.of(2026, 11, 4)).date)
        assertEquals("2026-11-16", task.copy(recurrence = Recurrence.WEEKLY_ON_DAY).completed(LocalDate.of(2026, 11, 4)).date)
        assertEquals("2027-02-04", task.copy(recurrence = Recurrence.MONTHLY, repeatEvery = 3).completed(LocalDate.of(2026, 11, 4)).date)
        assertEquals("every 2 weeks after checking it", task.repeatText())
    }

    @Test
    fun onDayRepeatsKeepTheirDayAndSkipMissedTimes() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Poubelles", date = "2026-11-02", recurrence = Recurrence.WEEKLY_ON_DAY)
        assertEquals("2026-11-09", task.completed(LocalDate.of(2026, 11, 4)).date)
        assertEquals("2026-11-23", task.completed(LocalDate.of(2026, 11, 17)).date)
        assertEquals("2026-11-09", task.completed(LocalDate.of(2026, 10, 30)).date)
        val monthly = task.copy(recurrence = Recurrence.MONTHLY_ON_DAY).completed(LocalDate.of(2026, 11, 20))
        assertEquals("2026-12-02", monthly.date)
        val monthEnd = task.copy(date = "2027-01-31", recurrence = Recurrence.MONTH_END)
        assertEquals("2027-02-28", monthEnd.completed(LocalDate.of(2027, 2, 1)).date)
        assertEquals("2027-03-31", monthEnd.copy(date = "2027-02-28").completed(LocalDate.of(2027, 2, 28)).date)
    }

    @Test
    fun lateTaskShowsOnlyOnToday() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Poubelles", date = "2026-11-02", recurrence = Recurrence.WEEKLY)
        val today = LocalDate.of(2026, 11, 5)
        assertTrue(task.occursOn(today, today))
        assertTrue(!task.occursOn(LocalDate.of(2026, 11, 2), today))
        assertTrue(!task.occursOn(LocalDate.of(2026, 11, 9), today))
        assertEquals(3L, task.daysLate(today))
        assertTrue(task.occursOn(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun eventOverSeveralDaysShowsOnEachOfThem() {
        val event = CalendarItem(kind = CalendarKind.EVENT, title = "Festival", date = "2026-11-06", endDate = "2026-11-08")
        val today = LocalDate.of(2026, 11, 1)
        assertTrue(!event.occursOn(LocalDate.of(2026, 11, 5), today))
        assertTrue(event.occursOn(LocalDate.of(2026, 11, 6), today))
        assertTrue(event.occursOn(LocalDate.of(2026, 11, 7), today))
        assertTrue(event.occursOn(LocalDate.of(2026, 11, 8), today))
        assertTrue(!event.occursOn(LocalDate.of(2026, 11, 9), today))
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

    @Test
    fun digestListsTomorrowsPendingTasksAndEventsAt20() {
        val items = listOf(
            CalendarItem(id = "t", kind = CalendarKind.TASK, title = "Dentiste", date = "2026-11-05", time = "10:00"),
            CalendarItem(id = "e", kind = CalendarKind.EVENT, title = "Concert", date = "2026-11-05", time = "08:00"),
            CalendarItem(id = "d", kind = CalendarKind.TASK, title = "Fait", date = "2026-11-05", done = true),
            CalendarItem(id = "l", kind = CalendarKind.TASK, title = "Plus tard", date = "2026-11-08"),
            birthday("1996-11-05")
        )
        assertEquals(listOf("e", "t"), digestItems(items, LocalDate.of(2026, 11, 5)).map { it.id })
        val now = LocalDateTime.of(2026, 11, 4, 12, 0)
        assertEquals(LocalDateTime.of(2026, 11, 4, 20, 0), nextDigest(items, now))
        assertEquals(
            listOf(LocalDate.of(2026, 11, 5)),
            digestsBetween(items, now, LocalDateTime.of(2026, 11, 6, 0, 0))
        )
        assertEquals(LocalDateTime.of(2026, 11, 7, 20, 0), nextDigest(items, LocalDateTime.of(2026, 11, 4, 20, 0)))
    }

    @Test
    fun pushingATaskKeepsTheDayItWasFirstSetFor() {
        val task = CalendarItem(kind = CalendarKind.TASK, title = "Appel", date = "2026-11-02")
        val twice = task.movedTo(LocalDate.of(2026, 11, 3)).movedTo(LocalDate.of(2026, 11, 4))
        assertEquals("2026-11-02", twice.plannedDate)
        assertEquals(2L, twice.daysPushed())
        assertEquals(null, twice.movedTo(LocalDate.of(2026, 11, 2)).plannedDate)
        assertEquals(0L, task.daysPushed())
    }
}
