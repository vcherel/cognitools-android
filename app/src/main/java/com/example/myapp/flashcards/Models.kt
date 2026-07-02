package com.example.myapp.flashcards

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID


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

fun isDue(card: FlashcardElement, now: Long = System.currentTimeMillis()): Boolean {
    val intervalMs = card.interval * 60 * 1000L // interval is in minutes
    return (now - card.lastReview) >= intervalMs
}

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
    val scoreBuckets: List<Pair<Int, Int>>
) {
    val winRate: Float get() =
        if (totalWins + totalLosses > 0) totalWins.toFloat() / (totalWins + totalLosses) else -1f
}