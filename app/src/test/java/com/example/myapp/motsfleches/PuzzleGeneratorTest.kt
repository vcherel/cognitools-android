package com.example.myapp.motsfleches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Runs the real generator over the real assets: a grid that cannot be filled, or one whose words
 * do not agree where they cross, is a broken grid on the phone with no way to notice beforehand.
 */
class PuzzleGeneratorTest {
    private val assets = File("src/main/assets")

    /** Both word lists: a grid is generated on the phone in whichever language is selected. */
    private val dictionaries by lazy {
        MotsFlechesLang.entries.associateWith {
            ClueDictionary.load(File(assets, it.asset).inputStream())
        }
    }

    private val dictionary by lazy { dictionaries.getValue(MotsFlechesLang.FR) }

    /** What the app plays: the shapes whose words all hang together. */
    private val layouts by lazy {
        PuzzleGenerator.parseLayouts(File(assets, "motsfleches_layouts.txt").readText())
            .filter { PuzzleGenerator.isFullyLinked(it) }
    }

    @Test
    fun `dictionary indexes every length the layouts need`() {
        for ((lang, dictionary) in dictionaries) {
            assertTrue("$lang is too small", dictionary.size > 10_000)
            for (length in 3..8) {
                val pattern = CharArray(length) { Puzzle.EMPTY }
                assertTrue("$lang has no words of length $length", dictionary.matchCount(pattern) > 100)
            }
        }
        // A fixed letter must narrow the search, and the count must match what is returned.
        val pattern = charArrayOf('C', Puzzle.EMPTY, 'A', 'T')
        assertEquals(dictionary.matchCount(pattern).toLong(), dictionary.matches(pattern).size.toLong())
        assertTrue(dictionary.matches(pattern).all { dictionary.word(it).matches(Regex("C.AT")) })
    }

    @Test
    fun `layouts parse and only hold usable slots`() {
        assertTrue("only ${layouts.size} linked layouts", layouts.size > 50)
        for (layout in layouts) {
            assertTrue(layout.definitionCells.all { it in 0 until layout.width * layout.height })
        }
    }

    /** Every word must be reachable from the others, so no corner of a grid stands on its own. */
    @Test
    fun `the grids played are all in one piece`() {
        for (layout in layouts) {
            val puzzle = fill(layout, seed = 3) ?: continue
            val linked = mutableSetOf(0)
            var grew = true
            while (grew) {
                grew = false
                puzzle.slots.forEachIndexed { index, slot ->
                    if (index in linked) return@forEachIndexed
                    val cells = puzzle.cellsOf(slot).toSet()
                    if (linked.any { puzzle.cellsOf(puzzle.slots[it]).any { cell -> cell in cells } }) {
                        linked.add(index)
                        grew = true
                    }
                }
            }
            assertEquals(puzzle.slots.size.toLong(), linked.size.toLong())
        }
    }

    @Test
    fun `nearly every layout fills, and what it fills is coherent`() {
        for ((lang, dictionary) in dictionaries) {
            var failures = 0
            layouts.forEachIndexed { index, layout ->
                val puzzle = fill(layout, seed = index.toLong(), dictionary = dictionary)
                if (puzzle == null) {
                    failures++
                    return@forEachIndexed
                }
                assertCoherent(puzzle)
            }
            assertTrue(
                "$lang: $failures/${layouts.size} layouts could not be filled",
                failures <= layouts.size / 6
            )
        }
    }

    @Test
    fun `the same layout gives a different grid every time`() {
        val layout = layouts.first { it.width == 10 }
        val grids = (1..5).mapNotNull { fill(layout, seed = it.toLong())?.solution?.concatToString() }
        assertEquals(5, grids.size)
        assertTrue("the fill is not varied enough", grids.toSet().size >= 4)
    }

    @Test
    fun `a rank ceiling keeps the grid on common words`() {
        val ceiling = 8_000
        val layout = layouts.first { fill(it, seed = 7, maxRank = ceiling) != null }
        val easy = fill(layout, seed = 7, maxRank = ceiling)!!
        assertTrue(easy.slots.all { dictionary.rankOf(it.answer) <= ceiling })

        val open = fill(layout, seed = 7)!!
        fun averageRank(puzzle: Puzzle) = puzzle.slots.map { dictionary.rankOf(it.answer) }.average()
        assertTrue(averageRank(easy) < averageRank(open))
    }

    /** What the app does: a layout that resists a few seeds is passed over, never shown half filled. */
    private fun fill(
        layout: Layout,
        seed: Long,
        maxRank: Int = Int.MAX_VALUE,
        dictionary: ClueDictionary = this.dictionary
    ): Puzzle? =
        (0 until 4).firstNotNullOfOrNull { attempt ->
            PuzzleGenerator.generate(layout, dictionary, Random(seed * 10 + attempt), maxRank)
        }

    /** Crossing words must agree, every word must have a definition cell, and no word repeats. */
    private fun assertCoherent(puzzle: Puzzle) {
        val seen = mutableSetOf<String>()
        for (slot in puzzle.slots) {
            // Not just the same word twice: CHAT next to CHATS reads as a repeat too.
            val family = seen.firstOrNull { one ->
                val short = if (one.length <= slot.answer.length) one else slot.answer
                val long = if (one.length <= slot.answer.length) slot.answer else one
                long.length - short.length <= 2 && long.startsWith(short)
            }
            assertTrue("repeated word ${slot.answer} (with $family)", family == null)
            assertTrue("repeated word ${slot.answer}", seen.add(slot.answer))
            assertTrue(slot.clue.isNotBlank())
            assertTrue(slot.explanation.isNotBlank())
            assertTrue(slot.length >= 3)
            val clueCell = puzzle.index(slot.clueRow, slot.clueCol)
            assertTrue("word ${slot.answer} has no definition cell", clueCell in puzzle.definitionCells)
            for (position in 0 until slot.length) {
                val row = slot.cellRow(position)
                val col = slot.cellCol(position)
                assertTrue(row in 0 until puzzle.height && col in 0 until puzzle.width)
                assertEquals(slot.answer[position], puzzle.solution[puzzle.index(row, col)])
            }
        }
        // Every letter cell belongs to at least one word, otherwise it can never be filled in.
        for (cell in 0 until puzzle.cellCount) {
            if (cell in puzzle.definitionCells) continue
            assertTrue("cell $cell is in no word", puzzle.solution[cell] != Puzzle.EMPTY)
        }
    }
}
