package com.example.myapp.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

class TodoRemindersTest {
    @Test
    fun countsActiveBlockAndWaitingSectionOnly() {
        val content = listOf(
            "[ ] Appeler Paul",
            "[x] Payer loyer",
            "Note libre",
            "[ ] Banque",
            "--- Attente",
            "[ ] Passeport (12/09)",
            "[x] Colis",
            "--- Week-end",
            "[ ] Concert Ubu",
            "--- Plus tard",
            "[ ] Ranger cave"
        ).joinToString("\n")
        val items = todoItems(content)
        assertEquals(listOf("Appeler Paul", "Banque"), items.toDo)
        assertEquals(listOf("Passeport (12/09)"), items.waiting)
        assertEquals(listOf("Concert Ubu"), items.weekend)
    }

    @Test
    fun weekendReminderIsTheNextFridayEvening() {
        val sunday = LocalDateTime.of(2026, 11, 1, 10, 0)
        assertEquals(LocalDateTime.of(2026, 11, 6, 18, 0), nextWeekendReminder(sunday))
        val fridayMorning = LocalDateTime.of(2026, 11, 6, 9, 0)
        assertEquals(LocalDateTime.of(2026, 11, 6, 18, 0), nextWeekendReminder(fridayMorning))
        val fridayAtSix = LocalDateTime.of(2026, 11, 6, 18, 0)
        assertEquals(LocalDateTime.of(2026, 11, 13, 18, 0), nextWeekendReminder(fridayAtSix))
    }

    @Test
    fun missingSectionsAreAddedInOrder() {
        assertEquals("[ ] A\n--- Attente\n--- Week-end", withTodoSections("[ ] A"))
        assertEquals(
            "[ ] A\n--- Attente\n[ ] B\n--- Week-end\n--- Plus tard\n[ ] C",
            withTodoSections("[ ] A\n--- Attente\n[ ] B\n--- Plus tard\n[ ] C")
        )
        val complete = "[ ] A\n--- Attente\n--- Week-end\n[ ] D"
        assertEquals(complete, withTodoSections(complete))
    }

    @Test
    fun drawStaysWithinHalfToOneAndAHalfMeanDuringTheDay() {
        val after = LocalDateTime.of(2026, 11, 2, 9, 0)
        repeat(200) {
            val next = nextTodoReminder(after, Duration.ofHours(6), Random(it))
            assertTrue(Duration.between(after, next).toMinutes() in 180..540)
        }
    }

    @Test
    fun nightDrawMovesToTheMorning() {
        val after = LocalDateTime.of(2026, 11, 2, 21, 0)
        repeat(200) {
            val next = nextTodoReminder(after, Duration.ofHours(6), Random(it))
            assertEquals(after.toLocalDate().plusDays(1), next.toLocalDate())
            assertTrue(!next.toLocalTime().isBefore(LocalTime.of(9, 0)) && next.toLocalTime().isBefore(LocalTime.of(12, 0)))
        }
    }
}
