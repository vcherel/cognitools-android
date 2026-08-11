package com.example.myapp.motsfleches

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.random.Random

/** The two languages a grid can be played in. Each has its own word list and its own grid in progress. */
enum class MotsFlechesLang(val code: String, val label: String, val asset: String, val saveFile: String) {
    FR("fr", "FR", "motsfleches_dict_fr.txt", "motsfleches.json"),
    EN("en", "EN", "motsfleches_dict_en.txt", "motsfleches_en.json");

    companion object {
        fun fromCode(code: String?): MotsFlechesLang = entries.firstOrNull { it.code == code } ?: FR
    }
}

val Context.motsFlechesDataStore by preferencesDataStore("motsfleches_prefs")

private val KEY_LANG = stringPreferencesKey("lang")

/**
 * Holds the one grid being played per language. There is no library of grids: finishing one hands
 * over the next, so the only thing to remember is where the current one stands. Switching language
 * leaves the other one's grid exactly where it was.
 *
 * The dictionaries and the layouts are read from the assets once per process; the layouts are the
 * same shapes whatever the language, and none of it ever changes.
 */
object MotsFlechesStore {
    private const val LAYOUTS_ASSET = "motsfleches_layouts.txt"

    /** Words rarer than this are only used when a grid cannot be filled without them. */
    private const val COMMON_WORDS = 20_000

    /**
     * What a grid is built from first: the everyday part of the language. About a third of the
     * layouts fill from it, which a dozen attempts turn into an easy grid nearly every time.
     */
    private const val EASY_WORDS = 10_000

    /** 8x10 and 10x13 fit here, 12x15 does not: fewer words to find, and bigger cells to read. */
    private const val SMALL_GRID_CELLS = 130

    private const val LAYOUT_ATTEMPTS = 12

    /** How many grids are kept ready per language, so a new one never waits on a fill. */
    private const val PREFETCH = 2

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val dictionaries = mutableMapOf<MotsFlechesLang, ClueDictionary>()
    private var layouts: List<Layout>? = null

    /** Generated ahead of time while a grid is being played, so the next ones open instantly. */
    private val upcoming = mutableMapOf<MotsFlechesLang, ArrayDeque<Puzzle>>()

    /** The language the tool opens in, remembered across launches. */
    fun language(context: Context): Flow<MotsFlechesLang> =
        context.motsFlechesDataStore.data.map { MotsFlechesLang.fromCode(it[KEY_LANG]) }

    suspend fun setLanguage(context: Context, lang: MotsFlechesLang) {
        context.motsFlechesDataStore.edit { it[KEY_LANG] = lang.code }
    }

    /** The grid in progress, or a fresh one on the very first run. */
    suspend fun current(context: Context, lang: MotsFlechesLang): PuzzleState = withContext(Dispatchers.IO) {
        read(context, lang) ?: newGrid(context, lang)
    }

    suspend fun newGrid(context: Context, lang: MotsFlechesLang): PuzzleState = withContext(Dispatchers.IO) {
        val puzzle = mutex.withLock { upcoming[lang]?.removeFirstOrNull() } ?: generate(context, lang)
        PuzzleState.blank(puzzle).also { save(context, lang, it) }
    }

    suspend fun save(context: Context, lang: MotsFlechesLang, state: PuzzleState) = withContext(Dispatchers.IO) {
        File(context.filesDir, lang.saveFile).writeText(encode(state))
    }

    /**
     * Tops the reserve back up to [PREFETCH] grids, so finishing one never waits on a fill. The
     * fill itself runs outside the lock: it takes far longer than handing a ready grid over.
     */
    suspend fun prepareNext(context: Context, lang: MotsFlechesLang) = withContext(Dispatchers.IO) {
        while (mutex.withLock { upcoming[lang]?.size ?: 0 } < PREFETCH) {
            val puzzle = runCatching { generate(context, lang) }.getOrNull() ?: break
            mutex.withLock { upcoming.getOrPut(lang) { ArrayDeque() }.addLast(puzzle) }
        }
    }

    private fun read(context: Context, lang: MotsFlechesLang): PuzzleState? {
        val file = File(context.filesDir, lang.saveFile)
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }.getOrNull()
    }

    private fun generate(context: Context, lang: MotsFlechesLang): Puzzle {
        val dictionary = dictionary(context, lang)
        val all = layouts(context)
        val small = all.filter { it.width * it.height <= SMALL_GRID_CELLS }.ifEmpty { all }
        val rng = Random(System.nanoTime())
        // Easiest first: a small grid of everyday words. Each step down only happens when the one
        // before it could not be filled at all.
        val steps = listOf(
            small to EASY_WORDS,
            small to COMMON_WORDS,
            all to COMMON_WORDS,
            all to Int.MAX_VALUE
        )
        for ((pool, maxRank) in steps) {
            repeat(LAYOUT_ATTEMPTS) {
                PuzzleGenerator.generate(pool.random(rng), dictionary, rng, maxRank)?.let { return it }
            }
        }
        error("Aucune grille n'a pu être générée")
    }

    private fun dictionary(context: Context, lang: MotsFlechesLang): ClueDictionary = synchronized(this) {
        dictionaries.getOrPut(lang) { context.assets.open(lang.asset).use { ClueDictionary.load(it) } }
    }

    /** Only the shapes whose words all hang together; a grid split into islands is no fun to solve. */
    private fun layouts(context: Context): List<Layout> = layouts ?: synchronized(this) {
        layouts ?: context.assets.open(LAYOUTS_ASSET).use {
            PuzzleGenerator.parseLayouts(it.bufferedReader().readText())
        }.filter { PuzzleGenerator.isFullyLinked(it) }.also { layouts = it }
    }

    // Hand written with short keys, the same style as the Deezer caches, so the app stays off the
    // kotlinx-serialization gradle plugin.
    private fun encode(state: PuzzleState): String {
        val puzzle = state.puzzle
        return buildJsonObject {
            put("w", puzzle.width)
            put("h", puzzle.height)
            put("d", buildJsonArray { puzzle.definitionCells.sorted().forEach { add(it) } })
            put("e", state.entries)
            put("r", buildJsonArray { state.revealed.sorted().forEach { add(it) } })
            put("k", buildJsonArray { state.confirmed.sorted().forEach { add(it) } })
            put("s", buildJsonArray {
                puzzle.slots.forEach { slot ->
                    add(buildJsonObject {
                        put("r", slot.row)
                        put("c", slot.col)
                        put("a", slot.across)
                        put("w", slot.answer)
                        put("q", slot.clue)
                        put("x", slot.explanation)
                    })
                }
            })
        }.toString()
    }

    private fun decode(text: String): PuzzleState {
        val root = json.parseToJsonElement(text).jsonObject
        val width = root.getValue("w").jsonPrimitive.content.toInt()
        val height = root.getValue("h").jsonPrimitive.content.toInt()
        val definitions = root.getValue("d").jsonArray.map { it.jsonPrimitive.content.toInt() }.toSet()
        val slots = root.getValue("s").jsonArray.map { element ->
            val slot = element.jsonObject
            val clue = slot.getValue("q").jsonPrimitive.content
            Slot(
                row = slot.getValue("r").jsonPrimitive.content.toInt(),
                col = slot.getValue("c").jsonPrimitive.content.toInt(),
                across = slot.getValue("a").jsonPrimitive.content.toBoolean(),
                answer = slot.getValue("w").jsonPrimitive.content,
                clue = clue,
                // A grid saved while the full definition was not kept falls back to the short one.
                explanation = slot["x"]?.jsonPrimitive?.content ?: clue
            )
        }
        val puzzle = Puzzle(width, height, definitions, slots)
        val entries = root.getValue("e").jsonPrimitive.content
        val revealed = root["r"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content.toInt() }.toSet()
        val confirmed = root["k"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content.toInt() }.toSet()
        require(entries.length == puzzle.cellCount) { "grille sauvegardée incohérente" }
        return PuzzleState(puzzle, entries, revealed, confirmed)
    }
}
