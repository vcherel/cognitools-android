package com.example.myapp.flashcards

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class FlashcardRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).flashcardDao()

    fun observeLists(): Flow<List<FlashcardList>> = dao.observeLists()

    fun observeListsWithCounts(): Flow<Pair<List<FlashcardList>, Map<String, Pair<Int, Int>>>> {
        val now = System.currentTimeMillis()
        return combine(
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
    }

    fun observeDueCount(now: Long): Flow<Int> = dao.observeDueCounts(now).map { rows -> rows.sumOf { it.c } }

    suspend fun addList(list: FlashcardList) {
        val current = dao.getLists()
        val nextOrder = (current.maxOfOrNull { it.order } ?: 0) + 1
        dao.upsertList(list.copy(order = nextOrder))
    }

    suspend fun reorderLists(newOrder: List<FlashcardList>) {
        dao.upsertLists(newOrder.mapIndexed { index, list -> list.copy(order = index) })
    }

    suspend fun updateList(listId: String, newName: String) {
        dao.getList(listId)?.let { dao.upsertList(it.copy(name = newName)) }
    }

    suspend fun deleteList(listId: String) = dao.deleteList(listId)

    /**
     * Marks a whole list as reviewed front side only. Turning it on rewrites every card it already
     * holds; turning it off only stops applying it to the cards added next, since a card put back on
     * a random side would be a different card to learn.
     */
    suspend fun setListFixedSide(listId: String, fixedSide: Boolean) {
        val list = dao.getList(listId) ?: return
        dao.upsertList(list.copy(fixedSide = fixedSide))
        if (!fixedSide) return
        val toFix = dao.getElements(listId).filter { it.randomSide }
        if (toFix.isNotEmpty()) dao.upsertElements(toFix.map { it.copy(randomSide = false) })
    }

    fun observeElements(listId: String): Flow<List<FlashcardElement>> = dao.observeElements(listId)

    suspend fun addElement(listId: String, element: FlashcardElement) = addElements(listId, listOf(element))

    /** A card entering a fixed-side list is fixed side too, whatever it asked for. */
    suspend fun addElements(listId: String, elements: List<FlashcardElement>) {
        val fixedSide = dao.getList(listId)?.fixedSide == true
        dao.upsertElements(
            elements.map {
                val moved = it.copy(listId = listId)
                if (fixedSide) moved.copy(randomSide = false) else moved
            }
        )
    }

    suspend fun updateElement(element: FlashcardElement) = dao.upsertElement(element)

    suspend fun deleteElement(elementId: String) = dao.deleteElement(elementId)

    fun observeAllElements(): Flow<List<FlashcardElement>> = dao.observeAllElements()

    suspend fun getStats(listId: String? = null): FlashcardStats {
        val elements = if (listId != null) dao.getElements(listId) else dao.getAllElements()
        val now = System.currentTimeMillis()
        val waitTimes = elements.map { maxOf(0L, it.nextReviewAt - now) }
        return FlashcardStats(
            totalCards = elements.size,
            dueCards = elements.count { isDue(it, now) },
            totalWins = elements.sumOf { it.totalWins },
            totalLosses = elements.sumOf { it.totalLosses },
            scoreBuckets = (0..10).map { score ->
                score to elements.count { it.score.toInt().coerceIn(0, 10) == score }
            },
            meanTimeUntilNextReviewMs = if (waitTimes.isEmpty()) -1L else waitTimes.average().toLong(),
            waitTimeBuckets = computeWaitTimeBuckets(waitTimes)
        )
    }

    suspend fun createBackupJson(): String {
        val lists = dao.getLists()
        val cards = dao.getAllElements()
        val jsonLists = JSONArray().also { arr -> lists.forEach { arr.put(it.toJson()) } }
        val jsonCards = JSONArray().also { arr ->
            cards.forEach { card ->
                arr.put(card.toJson().also { json ->
                    context.userImageBase64(card.image)?.let { json.put("imageData", it) }
                })
            }
        }
        return JSONObject().apply {
            put("version", 1)
            put("lists", jsonLists)
            put("cards", jsonCards)
        }.toString(2)
    }

    suspend fun importFromJson(json: String) = upsertBundle(JSONObject(json))

    // Parses a { "lists": [...], "cards": [...] } bundle into the DB, dropping any
    // card whose list isn't part of the same bundle. A picked picture travels in the card as "imageData".
    private suspend fun upsertBundle(obj: JSONObject) {
        val lists = FlashcardList.listFromJsonString(obj.getJSONArray("lists").toString())
        val jsonCards = obj.getJSONArray("cards")
        val cards = FlashcardElement.listFromJsonString(jsonCards.toString())
        for (i in 0 until jsonCards.length()) {
            val json = jsonCards.getJSONObject(i)
            val data = json.optString("imageData")
            if (data.isNotEmpty()) context.restoreUserImage(json.optString("image"), data)
        }
        val validListIds = lists.map { it.id }.toSet()
        if (lists.isNotEmpty()) dao.upsertLists(lists)
        val validCards = cards.filter { it.listId in validListIds }
        if (validCards.isNotEmpty()) dao.upsertElements(validCards)
    }

    suspend fun resetElement(elementId: String) {
        dao.getElement(elementId)?.let { dao.upsertElement(it.resetProgress()) }
    }

    suspend fun getListNameById(listId: String): String = dao.getList(listId)?.name ?: ""

    /**
     * Brings the card library up to date at app start: seeds the builtin lists this install doesn't
     * have yet, drops mastered cards outside them (interval above 6 months), then the pictures no
     * card points at any more.
     */
    suspend fun seedAndPurge() {
        val seeded = context.flashcardDataStore.data.first()[seedVersionKey] ?: 0
        if (seeded < SEEDS.last().first) {
            SEEDS.filter { (version, _) -> version > seeded }.forEach { (_, file) -> seedAssets(file) }
            context.flashcardDataStore.edit { it[seedVersionKey] = SEEDS.last().first }
        }
        val reset = context.flashcardDataStore.data.first()[resetVersionKey] ?: 0
        if (reset < RESETS.last().first) {
            RESETS.filter { (version, _) -> version > reset }.forEach { (_, id) -> resetElement(id) }
            context.flashcardDataStore.edit { it[resetVersionKey] = RESETS.last().first }
        }
        dao.purgeMasteredCards(SIX_MONTHS_MINUTES, builtinListIds)
        context.sweepCardImages(dao.getImages())
    }

    private suspend fun seedAssets(filename: String) {
        try {
            val json = context.assets.open(filename).bufferedReader().readText()
            upsertBundle(JSONObject(json))
        } catch (e: Exception) {
            Log.w("FlashcardRepository", "Failed to seed from $filename", e)
        }
    }

    companion object {
        private val seedVersionKey = intPreferencesKey("seed_version")
        // Each file with the seed version that shipped it. Seeding upserts the cards, wiping their
        // progress, so a new list gets the next version and leaves the older ones alone.
        private val SEEDS = listOf(
            4 to "seed_capitals.json",
            4 to "seed_prefectures.json",
            4 to "seed_dept_numbers.json",
            5 to "seed_flags.json"
        )
        private val resetVersionKey = intPreferencesKey("reset_version")
        // Builtin cards whose text or picture was edited, with the version that shipped the edit:
        // their progress is wiped once, since they may have been learned from the old version.
        private val RESETS = listOf(
            1 to "flag-yt"
        )
        private const val SIX_MONTHS_MINUTES = 6 * 30 * 24 * 60
        private val builtinListIds = listOf(
            "builtin-capitals-v1", "builtin-prefectures-v1", "builtin-dept-numbers-v1",
            "builtin-flags-v1"
        )
    }
}

val Context.flashcardDataStore by preferencesDataStore("flashcards")
