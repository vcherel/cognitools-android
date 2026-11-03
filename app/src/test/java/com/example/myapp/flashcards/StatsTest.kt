package com.example.myapp.flashcards

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

private val zone = ZoneId.of("Europe/Paris")

private fun at(day: Int, hour: Int): Long =
    LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

private fun card(interval: Int = 0, lastReview: Long = 0L, wins: Int = 1) = FlashcardElement(
    listId = "list",
    name = "name",
    definition = "definition",
    interval = interval,
    lastReview = lastReview,
    totalWins = wins
)

class StatsTest {

    @Test
    fun maturity_followsInterval() {
        assertEquals(Maturity.New, card(wins = 0).maturity)
        assertEquals(Maturity.Learning, card(interval = 6).maturity)
        assertEquals(Maturity.Young, card(interval = 3 * 24 * 60).maturity)
        assertEquals(Maturity.Mature, card(interval = 60 * 24 * 60).maturity)
        assertEquals(Maturity.Mastered, card(interval = MASTERED_MINUTES + 1).maturity)
    }

    @Test
    fun dueForecast_countsOverdueTodayAndDropsPastTheWeek() {
        val now = at(3, 10)
        val cards = listOf(
            card(lastReview = at(1, 8)),
            card(interval = 13 * 60, lastReview = now),
            card(interval = 15 * 60, lastReview = now),
            card(interval = 6 * 24 * 60, lastReview = now),
            card(interval = 7 * 24 * 60, lastReview = now)
        )
        assertEquals(listOf(2, 1, 0, 0, 0, 0, 1), dueForecast(cards, now, zone = zone))
    }
}
