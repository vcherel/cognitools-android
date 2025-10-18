package com.example.myapp.flashcards

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream

class FlashcardRepository(private val context: Context) {

    private val listsKey = stringPreferencesKey("lists")
    private val listsTimestampKey = stringPreferencesKey("lists_timestamp")

    fun observeLists(): Flow<List<FlashcardList>> {
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[listsKey] ?: "[]"
            val lists = FlashcardList.listFromJsonString(jsonString)
            lists
        }
    }

    fun observeListsWithCounts(): Flow<Pair<List<FlashcardList>, Map<String, Pair<Int, Int>>>> {
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[listsKey] ?: "[]"
            val lists = FlashcardList.listFromJsonString(jsonString)

            // Map of listId -> (totalCount, dueCount)
            val counts = lists.associate { list ->
                val key = stringPreferencesKey("elements_${list.id}")
                val cardsJson = prefs[key] ?: "[]"
                val elements = FlashcardElement.listFromJsonString(cardsJson)
                val due = elements.count { isDue(it) }
                list.id to (elements.size to due)
            }

            lists to counts
        }
    }

    suspend fun getLists(): List<FlashcardList> {
        val result = observeLists().first()
        return result
    }

    suspend fun saveLists(lists: List<FlashcardList>) {
        val sortedLists = lists.sortedBy { it.order }
        val jsonString = FlashcardList.listToJsonString(sortedLists)

        context.dataStore.edit { prefs ->
            prefs[listsKey] = jsonString
            // Force DataStore emit even if jsonString is identical
            prefs[listsTimestampKey] = System.currentTimeMillis().toString()
        }
    }

    suspend fun addList(list: FlashcardList) {
        val current = getLists()
        val nextOrder = (current.maxOfOrNull { it.order } ?: 0) + 1
        val newList = list.copy(order = nextOrder)
        saveLists(current + newList)
    }

    suspend fun reorderLists(newOrder: List<FlashcardList>) {
        val reordered = newOrder.mapIndexed { index, list -> list.copy(order = index) }
        saveLists(reordered)
    }

    suspend fun updateList(listId: String, newName: String) {
        val current = getLists()
        val updated = current.map { if (it.id == listId) it.copy(name = newName) else it }
        saveLists(updated)
    }

    suspend fun deleteList(listId: String) {
        val current = getLists()
        saveLists(current.filterNot { it.id == listId })
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("elements_$listId"))
            // also update timestamp so observers see something changed
            prefs[listsTimestampKey] = System.currentTimeMillis().toString()
        }
    }

    fun observeElements(listId: String): Flow<List<FlashcardElement>> {
        val key = stringPreferencesKey("elements_$listId")
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[key] ?: "[]"
            val elements = FlashcardElement.listFromJsonString(jsonString)
            elements
        }
    }

    suspend fun getElements(listId: String): List<FlashcardElement> {
        val result = observeElements(listId).first()
        return result
    }

    suspend fun saveElements(listId: String, elements: List<FlashcardElement>) {
        val key = stringPreferencesKey("elements_$listId")
        val timestampKey = stringPreferencesKey("elements_timestamp_$listId")
        context.dataStore.edit { prefs ->
            prefs[key] = FlashcardElement.listToJsonString(elements)
            prefs[timestampKey] = System.currentTimeMillis().toString()
        }
    }

    suspend fun addElement(listId: String, element: FlashcardElement) {
        val current = getElements(listId)
        saveElements(listId, listOf(element) + current)
    }

    suspend fun addElements(listId: String, elements: List<FlashcardElement>) {
        val current = getElements(listId)
        saveElements(listId, elements + current)
    }

    suspend fun updateElement(listId: String, element: FlashcardElement) {
        val current = getElements(listId)
        val updated = current.map { if (it.id == element.id) element else it }
        saveElements(listId, updated)
    }

    suspend fun deleteElement(listId: String, elementId: String) {
        val current = getElements(listId)
        saveElements(listId, current.filterNot { it.id == elementId })
    }

    suspend fun getAllElements(): List<FlashcardElement> {
        val lists = getLists()
        val result = lists.flatMap { list -> getElements(list.id) }
        return result
    }

    suspend fun getExportData(): Pair<List<FlashcardList>, List<FlashcardElement>> {
        val lists = getLists()
        val allElements = getAllElements()
        return lists to allElements
    }

    suspend fun resetElement(listId: String, elementId: String) {
        val current = getElements(listId)
        val updated = current.map { element ->
            if (element.id == elementId) {
                element.copy(
                    easeFactor = 2.5,
                    interval = 0,
                    repetitions = 0,
                    lastReview = System.currentTimeMillis(),
                    totalWins = 0,
                    totalLosses = 0,
                    score = 0.0
                )
            } else element
        }
        saveElements(listId, updated)
    }
}

val Context.dataStore by preferencesDataStore("flashcards")

fun exportFlashcards(lists: List<FlashcardList>, allFlashcards: List<FlashcardElement>) {
    val flashcardsMap = allFlashcards.groupBy { it.listId }

    val builder = StringBuilder()

    lists.forEach { list ->
        builder.appendLine("* ${list.name}")
        flashcardsMap[list.id]?.forEach { card ->
            builder.appendLine("${card.name} - ${card.definition}")
        }
        builder.appendLine() // Blank line between lists
    }

    val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloadsFolder, "flashcards_export.txt")

    FileOutputStream(file).use { it.write(builder.toString().toByteArray()) }
}
