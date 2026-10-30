package com.example.myapp.flashcards

import kotlin.math.pow

/**
 * Pure spaced-repetition math, extracted so it can be unit tested without Compose.
 *
 * [random] supplies the [0, 1) values used for interval jitter; it defaults to
 * [Math.random] in production and can be replaced with a deterministic source in tests.
 * [now] is the timestamp written to [FlashcardElement.lastReview].
 * A [golden] card doubles its interval on a win, and on a miss loses everything: ease factor
 * floored, interval and repetitions wiped, the loss counted twice.
 */
fun reviewCard(
    card: FlashcardElement,
    quality: Int,
    sawAnswer: Boolean,
    now: Long = System.currentTimeMillis(),
    random: () -> Double = Math::random,
    golden: Boolean = false
): FlashcardElement {
    val goldenMiss = golden && quality < 3
    var newEF = card.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
    if (newEF < 1.3 || goldenMiss) newEF = 1.3

    // Bonus if didn't see answer
    if (!sawAnswer && !goldenMiss) {
        newEF *= 1.05
    }

    val newReps = if (quality >= 3) card.repetitions + 1 else 0

    val newWins = card.totalWins + if (quality >= 3) 1 else 0
    val newLosses = card.totalLosses + if (goldenMiss) 2 else if (quality < 3) 1 else 0

    val newScore = ((newWins.toDouble() / (newWins + newLosses)) * 10).coerceIn(0.0, 10.0)

    val newInterval = when {
        goldenMiss -> 0.0
        // If we fail, the card comes again quickly (25% chance to wait a bit depending on score)
        quality < 3 -> { if (random() < 0.25) newScore.coerceAtLeast(1.0) else 0.0 }

        // On the first win we have to wait 6 minutes
        newReps == 1 -> 6.0
        else -> {
            val randomFactor = random().pow(1.0 + ((10 - newScore) / 10.0))
            val randomMultiplier = 0.6 + randomFactor * 1.4
            card.interval * newEF * randomMultiplier
        }
    } * if (golden && !goldenMiss) 2 else 1

    return card.copy(
        easeFactor = newEF,
        interval = newInterval.toInt(),
        repetitions = newReps,
        lastReview = now,
        totalWins = newWins,
        totalLosses = newLosses,
        score = newScore
    )
}

/** Copy due again in half the time it still had to wait, [lastReview][FlashcardElement.lastReview] kept. */
fun halveRemainingWait(card: FlashcardElement, now: Long = System.currentTimeMillis()): FlashcardElement {
    val remaining = card.nextReviewAt - now
    if (remaining <= 0) return card
    val newNextReviewAt = now + remaining / 2
    return card.copy(interval = ((newNextReviewAt - card.lastReview) / 60_000L).toInt())
}
