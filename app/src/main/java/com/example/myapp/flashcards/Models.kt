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
    var easeFactor: Double = 2.5,
    var interval: Int = 0,
    var repetitions: Int = 0,
    var lastReview: Long = System.currentTimeMillis(),
    var totalWins: Int = 0,
    var totalLosses: Int = 0,
    var score: Double = 0.0,
    var randomSide: Boolean = true
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