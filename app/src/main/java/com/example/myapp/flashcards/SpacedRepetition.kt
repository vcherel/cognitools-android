package com.example.myapp.flashcards

import kotlin.math.pow

/**
 * Pure spaced-repetition math, extracted so it can be unit tested without Compose.
 *
 * [random] supplies the [0, 1) values used for interval jitter; it defaults to
 * [Math.random] in production and can be replaced with a deterministic source in tests.
 * [now] is the timestamp written to [FlashcardElement.lastReview].
 */
fun reviewCard(
    card: FlashcardElement,
    quality: Int,
    sawAnswer: Boolean,
    now: Long = System.currentTimeMillis(),
    random: () -> Double = Math::random
): FlashcardElement {
    // Update ease factor
    var newEF = card.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
    if (newEF < 1.3) newEF = 1.3

    // Bonus if didn't see answer
    if (!sawAnswer) {
        newEF *= 1.05
    }

    // Update repetitions
    val newReps = if (quality >= 3) card.repetitions + 1 else 0

    // Update total wins/losses
    val newWins = card.totalWins + if (quality >= 3) 1 else 0
    val newLosses = card.totalLosses + if (quality < 3) 1 else 0

    // Calculate new score
    val newScore = ((newWins.toDouble() / (newWins + newLosses)) * 10).coerceIn(0.0, 10.0)

    // Update interval
    val newInterval = when {
        // If we fail, the card comes again quickly (25% chance to wait a bit depending on score)
        quality < 3 -> { if (random() < 0.25) newScore.coerceAtLeast(1.0) else 0.0 }

        // On the first win we have to wait 6 minutes
        newReps == 1 -> 6.0
        else -> {
            val randomFactor = random().pow(1.0 + ((10 - newScore) / 10.0))
            val randomMultiplier = 0.6 + randomFactor * 1.4
            card.interval * newEF * randomMultiplier
        }
    }

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
