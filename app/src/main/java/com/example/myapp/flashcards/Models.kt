package com.example.myapp.flashcards

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID
import kotlin.math.roundToLong


@Entity(tableName = "lists")
data class FlashcardList(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val order: Int = 0
) {
    companion object {
        fun fromJson(json: JSONObject): FlashcardList {
            return FlashcardList(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                order = json.optInt("order", 0)
            )
        }

        fun listFromJsonString(jsonString: String): List<FlashcardList> {
            return try {
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

fun String.normalizeForSearch(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase()
}

@Entity(
    tableName = "cards",
    foreignKeys = [ForeignKey(
        entity = FlashcardList::class,
        parentColumns = ["id"],
        childColumns = ["listId"],
        onDelete = ForeignKey.CASCADE          // deleting a list deletes its cards
    )],
    indices = [Index("listId")]
)
data class FlashcardElement(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val listId: String,
    val name: String,
    val definition: String,
    val normalizedName: String = name.normalizeForSearch(),
    val normalizedDefinition: String = definition.normalizeForSearch(),
    val easeFactor: Double = 2.5,
    val interval: Int = 0,
    val repetitions: Int = 0,
    val lastReview: Long = System.currentTimeMillis(),
    val totalWins: Int = 0,
    val totalLosses: Int = 0,
    val score: Double = 0.0,
    val randomSide: Boolean = true
) {
    companion object {
        fun fromJson(json: JSONObject): FlashcardElement {
            val name = json.optString("name", "")
            val definition = json.optString("definition", "")

            return FlashcardElement(
                id = json.optString("id", UUID.randomUUID().toString()),
                listId = json.optString("listId", ""),
                name = name,
                definition = definition,
                normalizedName = name.normalizeForSearch(),
                normalizedDefinition = definition.normalizeForSearch(),
                easeFactor = json.optDouble("easeFactor", 2.5),
                interval = json.optInt("interval", 0),
                repetitions = json.optInt("repetitions", 0),
                lastReview = json.optLong("lastReview", System.currentTimeMillis()),
                totalWins = json.optInt("totalWins", 0),
                totalLosses = json.optInt("totalLosses", 0),
                score = json.optDouble("score", 0.0),
                randomSide = json.optBoolean("randomSide", true)
            )
        }

        fun listFromJsonString(jsonString: String): List<FlashcardElement> {
            return try {
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/** Moment where the card becomes due again. [FlashcardElement.interval] is in minutes. */
val FlashcardElement.nextReviewAt: Long
    get() = lastReview + interval * 60_000L

fun isDue(card: FlashcardElement, now: Long = System.currentTimeMillis()): Boolean {
    return now >= card.nextReviewAt
}

/** Copy with all learning progress cleared, as if the card had just been created. */
fun FlashcardElement.resetProgress(now: Long = System.currentTimeMillis()): FlashcardElement = copy(
    easeFactor = 2.5,
    interval = 0,
    repetitions = 0,
    lastReview = now,
    totalWins = 0,
    totalLosses = 0,
    score = 0.0
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val MONTH_MS = 30 * DAY_MS

/** Formats a duration in French: compact keeps the largest unit, detailed spells them all out. */
fun formatDuration(ms: Long, detailed: Boolean = false): String {
    if (ms <= 0) return "Maintenant"
    if (!detailed) return when {
        ms < HOUR_MS -> "${ms / MINUTE_MS}min"
        ms < DAY_MS -> "${ms / HOUR_MS}h"
        ms < MONTH_MS -> "${ms / DAY_MS}j"
        else -> "${ms / MONTH_MS}mois"
    }
    val months = ms / MONTH_MS
    val days = ms % MONTH_MS / DAY_MS
    val hours = ms % DAY_MS / HOUR_MS
    val minutes = ms % HOUR_MS / MINUTE_MS
    return when {
        ms < MINUTE_MS -> "${ms / 1000}s"
        ms < HOUR_MS -> "${minutes}min"
        ms < DAY_MS -> "${hours}h ${minutes}min"
        ms < MONTH_MS -> "${days}j ${hours}h ${minutes}min"
        else -> "${months}mois ${days}j ${hours}h ${minutes}min"
    }
}

/** One bar of the wait time histogram: a contiguous range of remaining time and how many cards fall in it. */
data class WaitTimeBucket(val label: String, val count: Int)

/**
 * Splits [waitTimesMs] into up to [maxBuckets] equal-width ranges spanning the actual min to max of the
 * data, so bin width adapts to whatever time scale the deck is currently at (all cards a few hours apart,
 * or spread over months) instead of assuming one fixed scale.
 */
fun computeWaitTimeBuckets(waitTimesMs: List<Long>, maxBuckets: Int = 6): List<WaitTimeBucket> {
    if (waitTimesMs.isEmpty()) return emptyList()
    val min = waitTimesMs.min()
    val max = waitTimesMs.max()
    if (min == max) return listOf(WaitTimeBucket(formatDuration(min), waitTimesMs.size))

    val width = (max - min).toDouble() / maxBuckets
    val edges = (0..maxBuckets).map { i -> min + (width * i).roundToLong() }.distinct()
    val bucketCount = edges.size - 1
    if (bucketCount <= 0) return listOf(WaitTimeBucket(formatDuration(min), waitTimesMs.size))

    return (0 until bucketCount).map { i ->
        val low = edges[i]
        val high = edges[i + 1]
        val isLast = i == bucketCount - 1
        val count = waitTimesMs.count { if (isLast) it in low..high else it in low until high }
        WaitTimeBucket(formatDuration(low), count)
    }
}

fun FlashcardList.toJson(): JSONObject = JSONObject().also {
    it.put("id", id)
    it.put("name", name)
    it.put("order", order)
}

fun FlashcardElement.toJson(): JSONObject = JSONObject().also {
    it.put("id", id)
    it.put("listId", listId)
    it.put("name", name)
    it.put("definition", definition)
    it.put("easeFactor", easeFactor)
    it.put("interval", interval)
    it.put("repetitions", repetitions)
    it.put("lastReview", lastReview)
    it.put("totalWins", totalWins)
    it.put("totalLosses", totalLosses)
    it.put("score", score)
    it.put("randomSide", randomSide)
}

data class FlashcardStats(
    val totalCards: Int,
    val dueCards: Int,
    val totalWins: Int,
    val totalLosses: Int,
    val scoreBuckets: List<Pair<Int, Int>>,
    /** Mean of the time remaining before each card is due again (due cards count as 0), -1 if no cards. */
    val meanTimeUntilNextReviewMs: Long,
    val waitTimeBuckets: List<WaitTimeBucket>
) {
    val winRate: Float get() =
        if (totalWins + totalLosses > 0) totalWins.toFloat() / (totalWins + totalLosses) else -1f
}