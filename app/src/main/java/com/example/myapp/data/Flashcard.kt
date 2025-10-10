package com.example.myapp.data

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.models.FlashcardElement
import com.example.myapp.models.FlashcardList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.collections.forEach

val Context.dataStore by preferencesDataStore("flashcards")

fun exportFlashcards(lists: List<FlashcardList>, allFlashcards: List<FlashcardElement>) {
    val flashcardsMap = allFlashcards.groupBy { it.listId }
    val exportJson = JSONObject().apply {
        put("lists", JSONArray().apply {
            lists.forEach { list ->
                put(JSONObject().apply {
                    put("name", list.name)
                    put("flashcards", JSONArray().apply {
                        flashcardsMap[list.id]?.forEach { card ->
                            put(JSONObject().apply {
                                put("name", card.name)
                                put("definition", card.definition)
                            })
                        }
                    })
                })
            }
        })
    }

    val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloadsFolder, "flashcards_export.json")
    FileOutputStream(file).use { it.write(exportJson.toString().toByteArray()) }
}

suspend fun loadFlashcardData(context: Context): Pair<List<FlashcardList>, List<FlashcardElement>> {
    val prefs = context.dataStore.data.first()
    val listsJson = prefs[stringPreferencesKey("lists")] ?: "[]"
    val lists = FlashcardList.listFromJsonString(JSONArray(listsJson).toString())

    val allFlashcards = lists.flatMap { list ->
        val flashcardsJson = prefs[stringPreferencesKey("elements_${list.id}")] ?: "[]"
        FlashcardElement.listFromJsonString(JSONArray(flashcardsJson).toString())
    }

    return lists to allFlashcards
}

fun importFlashcards(context: Context) {
    val jsonString = context.assets.open("flashcards_export.json").bufferedReader().use { it.readText() }

    CoroutineScope(Dispatchers.IO).launch {
        val success = importFlashcardsData(context, jsonString)
        withContext(Dispatchers.Main) {
            if (success) {
                Toast.makeText(context, "Flashcards imported successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to import flashcards", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

suspend fun importFlashcardsData(context: Context, jsonString: String): Boolean {
    return try {
        val json = JSONObject(jsonString)
        val listsJsonArray = json.getJSONArray("lists")

        // Read existing lists and elements
        val prefs = context.dataStore.data.first()
        val existingListsJson = prefs[stringPreferencesKey("lists")]?.let { JSONArray(it) } ?: JSONArray()
        val existingLists = mutableListOf<FlashcardList>()
        for (i in 0 until existingListsJson.length()) {
            val obj = existingListsJson.getJSONObject(i)
            existingLists.add(FlashcardList(id = obj.getString("id"), name = obj.getString("name")))
        }

        val existingElements = mutableListOf<FlashcardElement>()
        existingLists.forEach { list ->
            val key = stringPreferencesKey("elements_${list.id}")
            val cardsJson = prefs[key]?.let { JSONArray(it) } ?: JSONArray()
            for (i in 0 until cardsJson.length()) {
                val obj = cardsJson.getJSONObject(i)
                existingElements.add(
                    FlashcardElement(
                        listId = obj.getString("listId"),
                        name = obj.getString("name"),
                        definition = obj.getString("definition")
                    )
                )
            }
        }

        // Parse imported lists and cards
        val newLists = mutableListOf<FlashcardList>()
        val newElements = mutableListOf<FlashcardElement>()
        for (i in 0 until listsJsonArray.length()) {
            val listJson = listsJsonArray.getJSONObject(i)
            val list = FlashcardList(name = listJson.getString("name"))
            newLists.add(list)

            val flashcardsJsonArray = listJson.getJSONArray("flashcards")
            for (j in 0 until flashcardsJsonArray.length()) {
                val cardJson = flashcardsJsonArray.getJSONObject(j)
                newElements.add(
                    FlashcardElement(
                        listId = list.id,
                        name = cardJson.getString("name"),
                        definition = cardJson.getString("definition")
                    )
                )
            }
        }

        context.dataStore.edit { store ->
            val updatedListsJson = JSONArray(existingLists.map { JSONObject().apply { put("id", it.id); put("name", it.name) } })
            newLists.forEach { list ->
                updatedListsJson.put(JSONObject().apply { put("id", list.id); put("name", list.name) })
            }
            store[stringPreferencesKey("lists")] = updatedListsJson.toString()

            newLists.forEach { list ->
                val key = stringPreferencesKey("elements_${list.id}")
                val cardsJson = JSONArray()
                newElements.filter { it.listId == list.id }.forEach { card ->
                    cardsJson.put(JSONObject().apply { put("listId", card.listId); put("name", card.name); put("definition", card.definition) })
                }
                store[key] = cardsJson.toString()
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
