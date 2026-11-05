package com.example.myapp.flashcards

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import com.example.myapp.calendar.CalendarItem
import com.example.myapp.calendar.CalendarKind
import com.example.myapp.calendar.localDate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class FlashcardRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).flashcardDao()

    fun observeLists(): Flow<List<FlashcardList>> = dao.observeLists()

    /**
     * The last lists and counts seen, so the lists screen's first frame is filled instead of a
     * spinner. The screen's own query replaces it a moment later with fresh due counts.
     */
    @Volatile var lastListsWithCounts: Pair<List<FlashcardList>, Map<String, Pair<Int, Int>>>? = null
        private set

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
        }.onEach { lastListsWithCounts = it }
    }

    suspend fun primeListsWithCounts() {
        observeListsWithCounts().first()
        observeDueCount(System.currentTimeMillis()).first()
    }

    /** The last due total seen, so the menu's first frame shows it instead of 0 until the query lands. */
    @Volatile var lastDueCount: Int? = null
        private set

    fun observeDueCount(now: Long): Flow<Int> =
        dao.observeDueCounts(now).map { rows -> rows.sumOf { it.c } }.onEach { lastDueCount = it }

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

    suspend fun getElements(listId: String): List<FlashcardElement> = dao.getElements(listId)

    suspend fun getAllElements(): List<FlashcardElement> = dao.getAllElements()

    suspend fun getListNames(): Map<String, String> = dao.getLists().associate { it.id to it.name }

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
            maturity = elements.groupingBy { it.maturity }.eachCount(),
            forecast = dueForecast(elements, now)
        )
    }

    suspend fun backupJson(): JSONObject {
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
        }
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

    /**
     * Once a day, halves the remaining wait of one card drawn among those not due for another day,
     * so nothing sits forgotten for months. Returns the drawn card with its wait before and after,
     * or null when today's draw already happened or no card qualifies.
     */
    suspend fun dailyLottery(now: Long = System.currentTimeMillis()): DailyLottery? {
        val today = LocalDate.now().toString()
        if (context.flashcardDataStore.data.first()[lotteryDayKey] == today) return null
        context.flashcardDataStore.edit { it[lotteryDayKey] = today }
        val card = dao.getAllElements().filter { it.nextReviewAt - now > DAY_MS }.randomOrNull() ?: return null
        val halved = halveRemainingWait(card, now)
        dao.upsertElement(halved)
        return DailyLottery(halved, card.nextReviewAt - now, halved.nextReviewAt - now)
    }

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
            val ids = RESETS.filter { (version, _) -> version > reset }.map { it.second }.toSet()
            val shipped = SEEDS.flatMap { (_, file) -> seedCards(file) }
                .filter { it.id in ids || it.listId in ids }
            dao.upsertElements(shipped)
            context.flashcardDataStore.edit { it[resetVersionKey] = RESETS.last().first }
        }
        dao.purgeMasteredCards(MASTERED_MINUTES, builtinListIds)
        context.sweepCardImages(dao.getImages())
    }

    /**
     * Keeps the Birthdays list matched to the calendar's birthdays for as long as the app runs: one
     * card per birthday, name on one side and day on the other. A birthday whose name or day changed
     * starts over, one removed from the calendar leaves the list.
     */
    suspend fun followBirthdays() {
        AppDatabase.get(context).calendarDao().observeAll()
            .map { items -> items.filter { it.kind == CalendarKind.BIRTHDAY } }
            .distinctUntilChanged()
            .collect { syncBirthdays(it) }
    }

    private suspend fun syncBirthdays(birthdays: List<CalendarItem>) {
        if (dao.getList(BIRTHDAYS_LIST_ID) == null) {
            addList(FlashcardList(id = BIRTHDAYS_LIST_ID, name = "Birthdays"))
        }
        val existing = dao.getElements(BIRTHDAYS_LIST_ID).associateBy { it.id }
        val wanted = birthdays.map { item ->
            FlashcardElement(
                id = "birthday-${item.id}",
                listId = BIRTHDAYS_LIST_ID,
                name = item.title,
                definition = item.localDate().format(birthdayFormat)
            )
        }
        val changed = wanted.filter { card ->
            val old = existing[card.id]
            old == null || old.name != card.name || old.definition != card.definition
        }
        if (changed.isNotEmpty()) dao.upsertElements(changed)
        val wantedIds = wanted.map { it.id }.toSet()
        existing.keys.filter { it !in wantedIds }.forEach { dao.deleteElement(it) }
    }

    private suspend fun seedAssets(filename: String) {
        try {
            val json = context.assets.open(filename).bufferedReader().readText()
            upsertBundle(JSONObject(json))
        } catch (e: Exception) {
            Log.w("FlashcardRepository", "Failed to seed from $filename", e)
        }
    }

    private fun seedCards(filename: String): List<FlashcardElement> {
        val json = context.assets.open(filename).bufferedReader().readText()
        return FlashcardElement.listFromJsonString(JSONObject(json).getJSONArray("cards").toString())
    }

    companion object {
        private val seedVersionKey = intPreferencesKey("seed_version")
        // Each file with the seed version that shipped it. Seeding upserts the cards, wiping their
        // progress, so a new list gets the next version and leaves the older ones alone.
        private val SEEDS = listOf(
            4 to "seed_capitals.json",
            4 to "seed_prefectures.json",
            4 to "seed_dept_numbers.json",
            5 to "seed_flags.json",
            6 to "seed_maps.json",
            6 to "seed_dept_maps.json",
            7 to "seed_region_maps.json",
            8 to "seed_arrondissement_maps.json"
        )
        private val lotteryDayKey = stringPreferencesKey("lottery_day")
        private const val DAY_MS = 24 * 60 * 60_000L
        private val resetVersionKey = intPreferencesKey("reset_version")
        // Builtin cards whose text or picture was edited, with the version that shipped the edit:
        // they are replaced once by their shipped version, which also wipes their progress since
        // they may have been learned from the old one. A card added to an already seeded list is
        // listed here too, which inserts it. A list id stands for all its cards. Entries of a version
        // the phone already applied are dropped.
        private val RESETS = listOf(
            7 to "flag-sv",
            7 to "flag-ni",
            7 to "flag-py",
            7 to "flag-yt",
            7 to "flag-gu",
            8 to "builtin-maps-v1",
            8 to "deptmap-971",
            8 to "deptmap-972",
            8 to "deptmap-973",
            8 to "deptmap-974",
            8 to "deptmap-976",
            8 to "regionmap-01",
            8 to "regionmap-02",
            8 to "regionmap-03",
            8 to "regionmap-04",
            8 to "regionmap-06"
        )
        private const val BIRTHDAYS_LIST_ID = "builtin-birthdays-v1"
        private val birthdayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
        private val builtinListIds = listOf(
            "builtin-capitals-v1", "builtin-prefectures-v1", "builtin-dept-numbers-v1",
            "builtin-flags-v1", "builtin-maps-v1", "builtin-dept-maps-v1", "builtin-region-maps-v1",
            "builtin-arrondissement-maps-v1", BIRTHDAYS_LIST_ID
        )
    }
}

data class DailyLottery(val card: FlashcardElement, val waitBeforeMs: Long, val waitAfterMs: Long)

val Context.flashcardDataStore by preferencesDataStore("flashcards")
