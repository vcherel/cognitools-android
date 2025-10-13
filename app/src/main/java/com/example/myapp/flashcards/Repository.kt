package com.example.myapp.flashcards

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class FlashcardRepository(private val context: Context) {

    private val listsKey = stringPreferencesKey("lists")

    // Observe all lists
    fun observeLists(): Flow<List<FlashcardList>> {
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[listsKey] ?: "[]"
            FlashcardList.listFromJsonString(jsonString)
        }
    }

    // Observe all lists with due counts (computed together)
    fun observeListsWithDueCounts(): Flow<Pair<List<FlashcardList>, Map<String, Int>>> {
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[listsKey] ?: "[]"
            val lists = FlashcardList.listFromJsonString(jsonString)

            // Compute due counts for all lists in one go
            val dueCounts = lists.associate { list ->
                val key = stringPreferencesKey("elements_${list.id}")
                val cardsJson = prefs[key] ?: "[]"
                val elements = FlashcardElement.listFromJsonString(cardsJson)
                list.id to elements.count { isDue(it) }
            }

            lists to dueCounts
        }
    }

    // Get lists once (suspending)
    suspend fun getLists(): List<FlashcardList> {
        return observeLists().first()
    }

    // Save lists
    suspend fun saveLists(lists: List<FlashcardList>) {
        context.dataStore.edit { prefs ->
            prefs[listsKey] = FlashcardList.listToJsonString(lists)
        }
    }

    // Add a new list
    suspend fun addList(list: FlashcardList) {
        val current = getLists()
        saveLists(listOf(list) + current)
    }

    // Update a list
    suspend fun updateList(listId: String, newName: String) {
        val current = getLists()
        val updated = current.map {
            if (it.id == listId) it.copy(name = newName) else it
        }
        saveLists(updated)
    }

    // Delete a list (and its elements)
    suspend fun deleteList(listId: String) {
        val current = getLists()
        saveLists(current.filterNot { it.id == listId })

        // Also delete the elements
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("elements_$listId"))
        }
    }

    // Observe elements for a specific list
    fun observeElements(listId: String): Flow<List<FlashcardElement>> {
        val key = stringPreferencesKey("elements_$listId")
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[key] ?: "[]"
            FlashcardElement.listFromJsonString(jsonString)
        }
    }

    // Get elements once (suspending)
    suspend fun getElements(listId: String): List<FlashcardElement> {
        return observeElements(listId).first()
    }

    // Save elements for a list
    suspend fun saveElements(listId: String, elements: List<FlashcardElement>) {
        val key = stringPreferencesKey("elements_$listId")
        context.dataStore.edit { prefs ->
            prefs[key] = FlashcardElement.listToJsonString(elements)
        }
    }

    // Add a new element to a list
    suspend fun addElement(listId: String, element: FlashcardElement) {
        val current = getElements(listId)
        saveElements(listId, listOf(element) + current)
    }

    // Add multiple elements to a list
    suspend fun addElements(listId: String, elements: List<FlashcardElement>) {
        val current = getElements(listId)
        saveElements(listId, elements + current)
    }

    // Update an element
    suspend fun updateElement(listId: String, element: FlashcardElement) {
        val current = getElements(listId)
        val updated = current.map {
            if (it.id == element.id) element else it
        }
        saveElements(listId, updated)
    }

    // Delete an element
    suspend fun deleteElement(listId: String, elementId: String) {
        val current = getElements(listId)
        saveElements(listId, current.filterNot { it.id == elementId })
    }

    // Get all elements across all lists (for export/stats)
    suspend fun getAllElements(): List<FlashcardElement> {
        val lists = getLists()
        return lists.flatMap { list -> getElements(list.id) }
    }

    // Get due count for a list
    suspend fun getDueCount(listId: String): Int {
        val elements = getElements(listId)
        return elements.count { isDue(it) }
    }

    // Get due counts for all lists
    suspend fun getAllDueCounts(): Map<String, Int> {
        val lists = getLists()
        return lists.associate { list ->
            list.id to getDueCount(list.id)
        }
    }

    // Import data (replaces importFlashcardsData)
    suspend fun importData(jsonString: String): Boolean {
        return try {
            val json = org.json.JSONObject(jsonString)
            val listsJsonArray = json.getJSONArray("lists")

            val existingLists = getLists()
            val newLists = mutableListOf<FlashcardList>()

            for (i in 0 until listsJsonArray.length()) {
                val listJson = listsJsonArray.getJSONObject(i)
                val list = FlashcardList(name = listJson.getString("name"))
                newLists.add(list)

                val flashcardsJsonArray = listJson.getJSONArray("flashcards")
                val newElements = mutableListOf<FlashcardElement>()

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

                saveElements(list.id, newElements)
            }

            saveLists(existingLists + newLists)
            true
        } catch (_: Exception) {
            false
        }
    }

    // Export data (works with existing exportFlashcards function)
    suspend fun getExportData(): Pair<List<FlashcardList>, List<FlashcardElement>> {
        val lists = getLists()
        val allElements = getAllElements()
        return lists to allElements
    }
}