package com.example.myapp.flashcards

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class FlashcardRepository(private val context: Context) {

    private val listsKey = stringPreferencesKey("lists")

    fun observeLists(): Flow<List<FlashcardList>> {
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[listsKey] ?: "[]"
            FlashcardList.listFromJsonString(jsonString)
        }
    }

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

    suspend fun getLists(): List<FlashcardList> {
        return observeLists().first()
    }

    suspend fun saveLists(lists: List<FlashcardList>) {
        val sortedLists = lists.sortedBy { it.order }

        context.dataStore.edit { prefs ->
            prefs[listsKey] = FlashcardList.listToJsonString(sortedLists)
        }
    }

    suspend fun addList(list: FlashcardList) {
        val current = getLists()

        val nextOrder = (current.maxOfOrNull { it.order } ?: 0) + 1

        val newList = list.copy(order = nextOrder)
        saveLists(current + newList)
    }

    suspend fun reorderLists(newOrder: List<FlashcardList>) {
        val reordered = newOrder.mapIndexed { index, list ->
            list.copy(order = index)
        }
        saveLists(reordered)
    }

    suspend fun updateList(listId: String, newName: String) {
        val current = getLists()
        val updated = current.map {
            if (it.id == listId) it.copy(name = newName) else it
        }
        saveLists(updated)
    }

    suspend fun deleteList(listId: String) {
        val current = getLists()
        saveLists(current.filterNot { it.id == listId })

        // Also delete the elements
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("elements_$listId"))
        }
    }

    fun observeElements(listId: String): Flow<List<FlashcardElement>> {
        val key = stringPreferencesKey("elements_$listId")
        return context.dataStore.data.map { prefs ->
            val jsonString = prefs[key] ?: "[]"
            FlashcardElement.listFromJsonString(jsonString)
        }
    }

    suspend fun getElements(listId: String): List<FlashcardElement> {
        return observeElements(listId).first()
    }

    suspend fun saveElements(listId: String, elements: List<FlashcardElement>) {
        val key = stringPreferencesKey("elements_$listId")
        val timestampKey = stringPreferencesKey("elements_timestamp_$listId")
        context.dataStore.edit { prefs ->
            prefs[key] = FlashcardElement.listToJsonString(elements)
            // Add a useless variable to force update
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
        val updated = current.map {
            if (it.id == element.id) element else it
        }
        saveElements(listId, updated)
    }

    suspend fun deleteElement(listId: String, elementId: String) {
        val current = getElements(listId)
        saveElements(listId, current.filterNot { it.id == elementId })
    }

    suspend fun getAllElements(): List<FlashcardElement> {
        val lists = getLists()
        return lists.flatMap { list -> getElements(list.id) }
    }

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

    suspend fun getExportData(): Pair<List<FlashcardList>, List<FlashcardElement>> {
        val lists = getLists()
        val allElements = getAllElements()
        return lists to allElements
    }
}