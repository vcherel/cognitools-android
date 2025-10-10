package com.example.myapp.models

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID


data class FlashcardList(val id: String = UUID.randomUUID().toString(), val name: String) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
        }
    }
    companion object {
        private val cache = mutableMapOf<String, List<FlashcardList>>()
        fun fromJson(json: JSONObject): FlashcardList {
            return FlashcardList(
                id = json.getString("id"),
                name = json.getString("name")
            )
        }

        fun listToJsonString(lists: List<FlashcardList>): String {
            val jsonArray = JSONArray()
            lists.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }

        fun listFromJsonString(jsonString: String): List<FlashcardList> {
            return cache.getOrPut(jsonString) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}

data class FlashcardElement(
    val id: String = UUID.randomUUID().toString(),
    val listId: String,
    val name: String,
    val definition: String,
    var easeFactor: Double = 2.5,
    var interval: Int = 0,
    var repetitions: Int = 0,
    var lastReview: Long = System.currentTimeMillis(),
    var totalWins: Int = 0,
    var totalLosses: Int = 0,
    var score: Double? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("listId", listId)
            put("name", name)
            put("definition", definition)
            put("easeFactor", easeFactor)
            put("interval", interval)
            put("repetitions", repetitions)
            put("lastReview", lastReview)
            put("totalWins", totalWins)
            put("totalLosses", totalLosses)
            put("score", score)
        }
    }

    companion object {
        private val cache = mutableMapOf<String, List<FlashcardElement>>()
        fun fromJson(json: JSONObject): FlashcardElement {
            return FlashcardElement(
                id = json.optString("id", UUID.randomUUID().toString()), // optString to handle old data
                listId = json.getString("listId"),
                name = json.getString("name"),
                definition = json.getString("definition"),
                easeFactor = json.optDouble("easeFactor", 2.5),
                interval = json.optInt("interval", 0),
                repetitions = json.optInt("repetitions", 0),
                lastReview = json.optLong("lastReview", System.currentTimeMillis()),
                totalWins = json.optInt("totalWins", 0),
                totalLosses = json.optInt("totalLosses", 0),
                score = json.optDouble("score").takeIf { !json.isNull("score") }
            )
        }

        fun listToJsonString(elements: List<FlashcardElement>): String {
            val jsonArray = JSONArray()
            elements.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }

        fun listFromJsonString(jsonString: String): List<FlashcardElement> {
            return cache.getOrPut(jsonString) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}

fun isDue(card: FlashcardElement): Boolean {
    val now = System.currentTimeMillis()
    val intervalMs = card.interval * 60 * 1000L // interval is in minutes
    return (now - card.lastReview) >= intervalMs
}