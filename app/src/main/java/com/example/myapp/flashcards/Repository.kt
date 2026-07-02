package com.example.myapp.flashcards

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

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

    fun observeAllElements(): Flow<List<FlashcardElement>> = flow {
        ensureMigrated()
        emitAll(dao.observeAllElements())
    }

    suspend fun getAllElements(): List<FlashcardElement> {
        ensureMigrated()
        return dao.getAllElements()
    }

    suspend fun getStats(listId: String? = null): FlashcardStats {
        ensureMigrated()
        val elements = if (listId != null) dao.getElements(listId) else dao.getAllElements()
        val now = System.currentTimeMillis()
        return FlashcardStats(
            totalCards = elements.size,
            dueCards = elements.count { isDue(it, now) },
            totalWins = elements.sumOf { it.totalWins },
            totalLosses = elements.sumOf { it.totalLosses },
            scoreBuckets = (0..10).map { score ->
                score to elements.count { it.score.toInt().coerceIn(0, 10) == score }
            }
        )
    }

    suspend fun createBackupJson(): String {
        ensureMigrated()
        val lists = dao.getLists()
        val cards = dao.getAllElements()
        val jsonLists = JSONArray().also { arr -> lists.forEach { arr.put(it.toJson()) } }
        val jsonCards = JSONArray().also { arr -> cards.forEach { arr.put(it.toJson()) } }
        return JSONObject().apply {
            put("version", 1)
            put("lists", jsonLists)
            put("cards", jsonCards)
        }.toString(2)
    }

    suspend fun importFromJson(json: String) {
        ensureMigrated()
        val obj = JSONObject(json)
        val lists = FlashcardList.listFromJsonString(obj.getJSONArray("lists").toString())
        val cards = FlashcardElement.listFromJsonString(obj.getJSONArray("cards").toString())
        val validListIds = lists.map { it.id }.toSet()
        if (lists.isNotEmpty()) dao.upsertLists(lists)
        val validCards = cards.filter { it.listId in validListIds }
        if (validCards.isNotEmpty()) dao.upsertElements(validCards)
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
            applyBuiltinSeedMigrations()
            migrated = true
        }
    }

    private suspend fun applyBuiltinSeedMigrations() {
        val prefs = context.flashcardDataStore.data.first()

        // Infer version from legacy boolean flags on first upgrade, then persist it
        val currentVersion = prefs[seedVersionKey] ?: run {
            val inferred = when {
                prefs[legacyRenamedV3Key] == true -> 3
                prefs[legacySeededV2Key] == true -> 2
                prefs[legacySeededKey] == true -> 1
                else -> 0
            }
            context.flashcardDataStore.edit { it[seedVersionKey] = inferred }
            inferred
        }

        if (currentVersion >= SEED_VERSION) return

        if (currentVersion < 1) seedAssets("seed_capitals.json")
        if (currentVersion < 2) {
            seedAssets("seed_prefectures.json")
            seedAssets("seed_dept_numbers.json")
        }
        if (currentVersion < 3) {
            val renames = mapOf(
                "builtin-prefectures-v1" to "Préfectures",
                "builtin-dept-numbers-v1" to "Départements",
                "builtin-capitals-v1" to "Capitales"
            )
            val lists = dao.getLists()
            val toUpdate = lists.filter { it.id in renames }.map { it.copy(name = renames[it.id]!!) }
            if (toUpdate.isNotEmpty()) dao.upsertLists(toUpdate)
        }

        context.flashcardDataStore.edit { it[seedVersionKey] = SEED_VERSION }
    }

    private suspend fun seedAssets(filename: String) {
        try {
            val json = context.assets.open(filename).bufferedReader().readText()
            val obj = JSONObject(json)
            val lists = FlashcardList.listFromJsonString(obj.getJSONArray("lists").toString())
            val cards = FlashcardElement.listFromJsonString(obj.getJSONArray("cards").toString())
            val validListIds = lists.map { it.id }.toSet()
            if (lists.isNotEmpty()) dao.upsertLists(lists)
            val validCards = cards.filter { it.listId in validListIds }
            if (validCards.isNotEmpty()) dao.upsertElements(validCards)
        } catch (_: Exception) {
        }
    }

    companion object {
        private val listsKey = stringPreferencesKey("lists")
        private val migratedKey = booleanPreferencesKey("migrated_to_room")
        private val seedVersionKey = intPreferencesKey("seed_version")
        // Legacy keys — read-only, used only to infer the seed version on first upgrade
        private val legacySeededKey = booleanPreferencesKey("seeded_builtin_v1")
        private val legacySeededV2Key = booleanPreferencesKey("seeded_builtin_v2")
        private val legacyRenamedV3Key = booleanPreferencesKey("renamed_builtin_v3")
        private const val SEED_VERSION = 3
        private val migrationLock = Mutex()
        @Volatile private var migrated = false
    }
}

val Context.flashcardDataStore by preferencesDataStore("flashcards")
