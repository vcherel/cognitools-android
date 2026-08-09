package com.example.myapp.motsfleches

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

/**
 * Holds the one grid being played. There is no library of grids: finishing one hands over the next,
 * so the only thing to remember is where the current one stands.
 *
 * The dictionary and the layouts are read from the assets once per process; both are needed to make
 * a grid and neither changes.
 */
object MotsFlechesStore {
    private const val SAVE_FILE = "motsfleches.json"
    private const val DICTIONARY_ASSET = "motsfleches_dict.txt"
    private const val LAYOUTS_ASSET = "motsfleches_layouts.txt"

    /** Words rarer than this are only used when a grid cannot be filled without them. */
    private const val COMMON_WORDS = 20_000
    private const val LAYOUT_ATTEMPTS = 8

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var dictionary: ClueDictionary? = null
    private var layouts: List<Layout>? = null

    /** Generated ahead of time while a grid is being played, so the next one opens instantly. */
    private var upcoming: Puzzle? = null

    /** The grid in progress, or a fresh one on the very first run. */
    suspend fun current(context: Context): PuzzleState = withContext(Dispatchers.IO) {
        read(context) ?: newGrid(context)
    }

    suspend fun newGrid(context: Context): PuzzleState = withContext(Dispatchers.IO) {
        val puzzle = mutex.withLock { upcoming.also { upcoming = null } } ?: generate(context)
        PuzzleState.blank(puzzle).also { save(context, it) }
    }

    suspend fun save(context: Context, state: PuzzleState) = withContext(Dispatchers.IO) {
        File(context.filesDir, SAVE_FILE).writeText(encode(state))
    }

    /** Builds the grid that comes after this one, so finishing never waits on a fill. */
    suspend fun prepareNext(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (upcoming == null) upcoming = runCatching { generate(context) }.getOrNull()
        }
        Unit
    }

    private fun read(context: Context): PuzzleState? {
        val file = File(context.filesDir, SAVE_FILE)
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }.getOrNull()
    }

    private fun generate(context: Context): Puzzle {
        val dictionary = dictionary(context)
        val layouts = layouts(context)
        val rng = Random(System.nanoTime())
        // Common words first; a layout that will not fill from them gets the whole dictionary.
        for (maxRank in intArrayOf(COMMON_WORDS, Int.MAX_VALUE)) {
            repeat(LAYOUT_ATTEMPTS) {
                val layout = layouts.random(rng)
                PuzzleGenerator.generate(layout, dictionary, rng, maxRank)?.let { return it }
            }
        }
        error("Aucune grille n'a pu être générée")
    }

    private fun dictionary(context: Context): ClueDictionary = dictionary ?: synchronized(this) {
        dictionary ?: context.assets.open(DICTIONARY_ASSET).use { ClueDictionary.load(it) }
            .also { dictionary = it }
    }

    private fun layouts(context: Context): List<Layout> = layouts ?: synchronized(this) {
        layouts ?: context.assets.open(LAYOUTS_ASSET).use {
            PuzzleGenerator.parseLayouts(it.bufferedReader().readText())
        }.also { layouts = it }
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
            Slot(
                row = slot.getValue("r").jsonPrimitive.content.toInt(),
                col = slot.getValue("c").jsonPrimitive.content.toInt(),
                across = slot.getValue("a").jsonPrimitive.content.toBoolean(),
                answer = slot.getValue("w").jsonPrimitive.content,
                clue = slot.getValue("q").jsonPrimitive.content,
                explanation = slot.getValue("x").jsonPrimitive.content
            )
        }
        val puzzle = Puzzle(width, height, definitions, slots)
        val entries = root.getValue("e").jsonPrimitive.content
        val revealed = root["r"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content.toInt() }.toSet()
        require(entries.length == puzzle.cellCount) { "grille sauvegardée incohérente" }
        return PuzzleState(puzzle, entries, revealed)
    }
}
