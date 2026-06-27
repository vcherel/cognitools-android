package com.example.myapp.flashcards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPS = 1e-9

private fun card(
    easeFactor: Double = 2.5,
    interval: Int = 0,
    repetitions: Int = 0,
    lastReview: Long = 0L,
    totalWins: Int = 0,
    totalLosses: Int = 0,
    score: Double = 0.0
) = FlashcardElement(
    id = "test",
    listId = "list",
    name = "name",
    definition = "definition",
    easeFactor = easeFactor,
    interval = interval,
    repetitions = repetitions,
    lastReview = lastReview,
    totalWins = totalWins,
    totalLosses = totalLosses,
    score = score
)

class SpacedRepetitionTest {

    // ---- Ease factor ----

    @Test
    fun easeFactor_perfectRecall_increases() {
        // quality 5 adds 0.1
        val result = reviewCard(card(easeFactor = 2.5), quality = 5, sawAnswer = true)
        assertEquals(2.6, result.easeFactor, EPS)
    }

    @Test
    fun easeFactor_hesitantRecall_unchanged() {
        // quality 4 is the break-even quality: delta is exactly 0
        val result = reviewCard(card(easeFactor = 2.5), quality = 4, sawAnswer = true)
        assertEquals(2.5, result.easeFactor, EPS)
    }

    @Test
    fun easeFactor_failure_decreases() {
        // quality 2: 2.5 + (0.1 - 3*(0.08 + 3*0.02)) = 2.5 - 0.32
        val result = reviewCard(card(easeFactor = 2.5), quality = 2, sawAnswer = true)
        assertEquals(2.18, result.easeFactor, EPS)
    }

    @Test
    fun easeFactor_isFlooredAt1_3() {
        // Starting low with the worst quality would push below 1.3
        val result = reviewCard(card(easeFactor = 1.3), quality = 0, sawAnswer = true)
        assertEquals(1.3, result.easeFactor, EPS)
    }

    @Test
    fun easeFactor_noAnswerBonusAppliesAfterFloor() {
        // Floored to 1.3, then multiplied by the 1.05 "didn't peek" bonus
        val result = reviewCard(card(easeFactor = 1.3), quality = 0, sawAnswer = false)
        assertEquals(1.3 * 1.05, result.easeFactor, EPS)
    }

    @Test
    fun easeFactor_noAnswerBonusMultipliesResult() {
        val result = reviewCard(card(easeFactor = 2.5), quality = 5, sawAnswer = false)
        assertEquals(2.6 * 1.05, result.easeFactor, EPS)
    }

    // ---- Repetitions ----

    @Test
    fun repetitions_incrementOnPass() {
        val result = reviewCard(card(repetitions = 3), quality = 3, sawAnswer = true)
        assertEquals(4, result.repetitions)
    }

    @Test
    fun repetitions_resetOnFail() {
        val result = reviewCard(card(repetitions = 7), quality = 2, sawAnswer = true)
        assertEquals(0, result.repetitions)
    }

    // ---- Wins / losses / score ----

    @Test
    fun score_firstWinIsTen() {
        val result = reviewCard(card(), quality = 4, sawAnswer = true)
        assertEquals(1, result.totalWins)
        assertEquals(0, result.totalLosses)
        assertEquals(10.0, result.score, EPS)
    }

    @Test
    fun score_firstLossIsZero() {
        val result = reviewCard(card(), quality = 2, sawAnswer = true)
        assertEquals(0, result.totalWins)
        assertEquals(1, result.totalLosses)
        assertEquals(0.0, result.score, EPS)
    }

    @Test
    fun score_isWinRatioTimesTen() {
        // 3 prior wins + 1 new win = 4 wins, 1 loss -> 4/5 * 10 = 8
        val result = reviewCard(
            card(totalWins = 3, totalLosses = 1),
            quality = 4,
            sawAnswer = true
        )
        assertEquals(4, result.totalWins)
        assertEquals(1, result.totalLosses)
        assertEquals(8.0, result.score, EPS)
    }

    // ---- Interval ----

    @Test
    fun interval_firstWinIsSixMinutes() {
        // repetitions 0 -> newReps 1, regardless of randomness
        val result = reviewCard(
            card(interval = 0, repetitions = 0),
            quality = 4,
            sawAnswer = true,
            random = { 0.5 }
        )
        assertEquals(6, result.interval)
    }

    @Test
    fun interval_failWithLuckyRollWaitsScoreMinutes() {
        // 9 prior wins, new loss -> wins 9, losses 1, score 9.0
        // random < 0.25 -> coerceAtLeast(1.0) of 9.0 = 9.0
        val result = reviewCard(
            card(totalWins = 9, totalLosses = 0),
            quality = 2,
            sawAnswer = true,
            random = { 0.1 }
        )
        assertEquals(9, result.interval)
    }

    @Test
    fun interval_failWithLuckyRollFloorsAtOne() {
        // Fresh fail -> score 0, but lucky branch coerces to at least 1.0
        val result = reviewCard(
            card(),
            quality = 2,
            sawAnswer = true,
            random = { 0.1 }
        )
        assertEquals(1, result.interval)
    }

    @Test
    fun interval_failWithUnluckyRollResetsToZero() {
        val result = reviewCard(
            card(totalWins = 9, totalLosses = 0),
            quality = 2,
            sawAnswer = true,
            random = { 0.9 }
        )
        assertEquals(0, result.interval)
    }

    @Test
    fun interval_subsequentWinScalesByEaseAndJitter() {
        // repetitions 1 -> newReps 2 -> else branch.
        // 9 prior wins + new win = 10 wins, 0 losses -> score 10.
        // exponent = 1 + (10-10)/10 = 1, so randomFactor = 0.5^1 = 0.5
        // multiplier = 0.6 + 0.5*1.4 = 1.3
        // newEF (quality 4) = 2.5
        // interval = 10 * 2.5 * 1.3 = 32.5 -> toInt 32
        val result = reviewCard(
            card(interval = 10, repetitions = 1, totalWins = 9, totalLosses = 0),
            quality = 4,
            sawAnswer = true,
            random = { 0.5 }
        )
        assertEquals(32, result.interval)
    }

    @Test
    fun lastReview_setToProvidedNow() {
        val result = reviewCard(card(lastReview = 0L), quality = 4, sawAnswer = true, now = 12345L)
        assertEquals(12345L, result.lastReview)
    }

    // ---- isDue ----

    @Test
    fun isDue_zeroIntervalIsAlwaysDue() {
        assertTrue(isDue(card(interval = 0, lastReview = 1000L), now = 1000L))
    }

    @Test
    fun isDue_falseBeforeIntervalElapses() {
        // interval 10 minutes = 600_000 ms
        assertFalse(isDue(card(interval = 10, lastReview = 0L), now = 599_999L))
    }

    @Test
    fun isDue_trueOnceIntervalElapses() {
        assertTrue(isDue(card(interval = 10, lastReview = 0L), now = 600_000L))
    }
}
