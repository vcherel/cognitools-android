package com.example.myapp.flashcards

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.myapp.deaccented
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID


@Entity(tableName = "lists")
data class FlashcardList(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val order: Int = 0,
    /** The whole list is reviewed front side only: every card added to it comes in fixed side. */
    val fixedSide: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): FlashcardList {
            return FlashcardList(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                order = json.optInt("order", 0),
                fixedSide = json.optBoolean("fixedSide", false)
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
    val normalizedName: String = name.deaccented(),
    val normalizedDefinition: String = definition.deaccented(),
    val easeFactor: Double = 2.5,
    val interval: Int = 0,
    val repetitions: Int = 0,
    val lastReview: Long = System.currentTimeMillis(),
    val totalWins: Int = 0,
    val totalLosses: Int = 0,
    val score: Double = 0.0,
    val randomSide: Boolean = true,
    /** Shown on the front side, above [name] (which a picture-only card leaves blank). See CardImage.kt. */
    val image: String? = null
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
                normalizedName = name.deaccented(),
                normalizedDefinition = definition.deaccented(),
                easeFactor = json.optDouble("easeFactor", 2.5),
                interval = json.optInt("interval", 0),
                repetitions = json.optInt("repetitions", 0),
                lastReview = json.optLong("lastReview", System.currentTimeMillis()),
                totalWins = json.optInt("totalWins", 0),
                totalLosses = json.optInt("totalLosses", 0),
                score = json.optDouble("score", 0.0),
                randomSide = json.optBoolean("randomSide", true),
                image = json.optString("image").ifEmpty { null }
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

/** Past this interval a card is mastered, and dropped at app start unless its list is builtin. */
const val MASTERED_MINUTES = 6 * 30 * 24 * 60

enum class Maturity(val label: String) {
    New("New"), Learning("Learning"), Young("Young"), Mature("Mature"), Mastered("Mastered")
}

val FlashcardElement.maturity: Maturity
    get() = when {
        totalWins + totalLosses == 0 -> Maturity.New
        interval < 24 * 60 -> Maturity.Learning
        interval < 21 * 24 * 60 -> Maturity.Young
        interval <= MASTERED_MINUTES -> Maturity.Mature
        else -> Maturity.Mastered
    }

/** Cards coming due on each of the next [days] days, today first and holding the overdue ones too. */
fun dueForecast(
    cards: List<FlashcardElement>,
    now: Long,
    days: Int = 7,
    zone: ZoneId = ZoneId.systemDefault()
): List<Int> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val counts = IntArray(days)
    cards.forEach { card ->
        val day = Instant.ofEpochMilli(card.nextReviewAt).atZone(zone).toLocalDate()
        val index = ChronoUnit.DAYS.between(today, day).coerceAtLeast(0)
        if (index < days) counts[index.toInt()]++
    }
    return counts.toList()
}

fun FlashcardList.toJson(): JSONObject = JSONObject().also {
    it.put("id", id)
    it.put("name", name)
    it.put("order", order)
    it.put("fixedSide", fixedSide)
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
    it.put("image", image)
}

/** What names the card in a title: its front text, or its back for a picture-only card. */
val FlashcardElement.label: String
    get() = name.ifBlank { definition }

data class FlashcardStats(
    val totalCards: Int,
    val dueCards: Int,
    val totalWins: Int,
    val totalLosses: Int,
    val scoreBuckets: List<Pair<Int, Int>>,
    /** Mean of the time remaining before each card is due again (due cards count as 0), -1 if no cards. */
    val meanTimeUntilNextReviewMs: Long,
    val maturity: Map<Maturity, Int>,
    /** See [dueForecast]. */
    val forecast: List<Int>
) {
    val winRate: Float get() =
        if (totalWins + totalLosses > 0) totalWins.toFloat() / (totalWins + totalLosses) else -1f
}