package com.example.myapp.flashcards

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

class FlashcardRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).flashcardDao()

    fun observeLists(): Flow<List<FlashcardList>> = flow {
        ensureMigrated()
        emitAll(dao.observeLists())
    }

    fun observeListsWithCounts(): Flow<Pair<List<FlashcardList>, Map<String, Pair<Int, Int>>>> = flow {
        ensureMigrated()
        val now = System.currentTimeMillis()
        emitAll(
            combine(
                dao.observeLists(),
                dao.observeTotalCounts(),
                dao.observeDueCounts(now)
            ) { lists, totals, dues ->
                val totalById = totals.associate { it.listId to it.c }
                val dueById = dues.associate { it.listId to it.c }
                val counts = lists.associate { list ->
                    list.id to ((totalById[list.id] ?: 0) to (dueById[list.id] ?: 0))
                }
                lists to counts
            }
        )
    }

    suspend fun getLists(): List<FlashcardList> {
        ensureMigrated()
        return dao.getLists()
    }

    suspend fun addList(list: FlashcardList) {
        val current = getLists()
        val nextOrder = (current.maxOfOrNull { it.order } ?: 0) + 1
        dao.upsertList(list.copy(order = nextOrder))
    }

    suspend fun reorderLists(newOrder: List<FlashcardList>) {
        ensureMigrated()
        dao.upsertLists(newOrder.mapIndexed { index, list -> list.copy(order = index) })
    }

    suspend fun updateList(listId: String, newName: String) {
        val current = getLists()
        current.find { it.id == listId }?.let { dao.upsertList(it.copy(name = newName)) }
    }

    suspend fun deleteList(listId: String) {
        val current = getLists()
        // Cards are removed by the ON DELETE CASCADE foreign key.
        current.find { it.id == listId }?.let { dao.deleteList(it) }
    }

    fun observeElements(listId: String): Flow<List<FlashcardElement>> = flow {
        ensureMigrated()
        emitAll(dao.observeElements(listId))
    }

    suspend fun getElements(listId: String): List<FlashcardElement> {
        ensureMigrated()
        return dao.getElements(listId)
    }

    suspend fun addElement(listId: String, element: FlashcardElement) {
        ensureMigrated()
        dao.upsertElement(element.copy(listId = listId))
    }

    suspend fun addElements(listId: String, elements: List<FlashcardElement>) {
        ensureMigrated()
        dao.upsertElements(elements.map { it.copy(listId = listId) })
    }

    suspend fun updateElement(listId: String, element: FlashcardElement) {
        ensureMigrated()
        dao.upsertElement(element)
    }

    suspend fun deleteElement(listId: String, elementId: String) {
        ensureMigrated()
        dao.deleteElement(elementId)
    }

    suspend fun getAllElements(): List<FlashcardElement> {
        ensureMigrated()
        return dao.getAllElements()
    }

    suspend fun getExportData(): Pair<List<FlashcardList>, List<FlashcardElement>> {
        ensureMigrated()
        return dao.getLists() to dao.getAllElements()
    }

    suspend fun resetElement(listId: String, elementId: String) {
        ensureMigrated()
        dao.getElement(elementId)?.let { element ->
            dao.upsertElement(
                element.copy(
                    easeFactor = 2.5,
                    interval = 0,
                    repetitions = 0,
                    lastReview = System.currentTimeMillis(),
                    totalWins = 0,
                    totalLosses = 0,
                    score = 0.0
                )
            )
        }
    }

    suspend fun getListNameById(listId: String): String {
        ensureMigrated()
        return dao.getLists().find { it.id == listId }?.name ?: ""
    }

    suspend fun updateRandomSide(listId: String, elementId: String, randomSide: Boolean) {
        ensureMigrated()
        dao.getElement(elementId)?.let { dao.upsertElement(it.copy(randomSide = randomSide)) }
    }

    /**
     * One-time copy of the old JSON-in-DataStore data into Room. Idempotent: guarded by a
     * flag and a non-empty check, so it runs at most once. The old DataStore file is left
     * untouched so the first run stays reversible.
     */
    private suspend fun ensureMigrated() {
        if (migrated) return
        migrationLock.withLock {
            if (migrated) return
            val prefs = context.flashcardDataStore.data.first()
            val alreadyDone = prefs[migratedKey] == true || dao.listCount() > 0
            if (!alreadyDone) {
                val lists = FlashcardList.listFromJsonString(prefs[listsKey] ?: "[]")
                if (lists.isNotEmpty()) {
                    dao.upsertLists(lists)
                    val allCards = lists.flatMap { list ->
                        val key = stringPreferencesKey("elements_${list.id}")
                        FlashcardElement.listFromJsonString(prefs[key] ?: "[]")
                    }
                    if (allCards.isNotEmpty()) dao.upsertElements(allCards)
                }
            }
            context.flashcardDataStore.edit { it[migratedKey] = true }
            migrated = true
        }
    }

    companion object {
        private val listsKey = stringPreferencesKey("lists")
        private val migratedKey = booleanPreferencesKey("migrated_to_room")
        private val migrationLock = Mutex()
        @Volatile private var migrated = false
    }
}

val Context.flashcardDataStore by preferencesDataStore("flashcards")

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
