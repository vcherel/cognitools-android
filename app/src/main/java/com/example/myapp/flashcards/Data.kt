package com.example.myapp.flashcards

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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

fun importFlashcards(context: Context, repository: FlashcardRepository) {
    val jsonString = context.assets.open("flashcards_export.json").bufferedReader().use { it.readText() }

    CoroutineScope(Dispatchers.IO).launch {
        val success = repository.importData(jsonString)
        withContext(Dispatchers.Main) {
            if (success) {
                Toast.makeText(context, "Flashcards imported successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to import flashcards", Toast.LENGTH_SHORT).show()
            }
        }
    }
}