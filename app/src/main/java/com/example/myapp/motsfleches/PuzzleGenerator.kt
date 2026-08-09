package com.example.myapp.motsfleches

import kotlin.random.Random

/** The shape of a grid: which cells hold a definition. The words are filled in afterwards. */
class Layout(val width: Int, val height: Int, val definitionCells: Set<Int>)

/** Where a word goes and how long it is, before it has an answer. */
private class SlotShape(val row: Int, val col: Int, val across: Boolean, val length: Int) {
    fun cell(width: Int, position: Int): Int =
        if (across) row * width + col + position else (row + position) * width + col
}

/**
 * Fills a layout with real words, which is what makes the grids unlimited: 143 layouts times a
 * different fill every time.
 *
 * The fill is a backtracking search: it always continues with the word that has the fewest
 * remaining candidates, so a dead end shows up early rather than after twenty placements.
 */
object PuzzleGenerator {
    /** Shortest word a slot can hold; matches what the layout generator produces. */
    private const val MIN_WORD = 3
    private const val CANDIDATES_PER_SLOT = 25
    private const val NODE_BUDGET = 120_000
    private const val UNASSIGNED = -1

    /** Keeps the fill from always picking the single most common word that fits. */
    private const val RANK_NOISE = 4_000

    /** Parses the bundled `motsfleches_layouts.txt`: blocks of "width height" then the rows. */
    fun parseLayouts(text: String): List<Layout> = text.split("\n\n").mapNotNull { block ->
        val lines = block.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 2) return@mapNotNull null
        val (width, height) = lines[0].trim().split(" ").let {
            (it.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to
                (it.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null)
        }
        val rows = lines.drop(1)
        if (rows.size != height || rows.any { it.length != width }) return@mapNotNull null
        val definitions = buildSet {
            rows.forEachIndexed { row, line ->
                line.forEachIndexed { col, cell -> if (cell == '#') add(row * width + col) }
            }
        }
        Layout(width, height, definitions)
    }

    /**
     * A filled grid, or null when this layout could not be filled within the budget. Callers retry
     * with another layout; a failed fill is cheap compared to a bad grid.
     *
     * [maxRank] is how far down the frequency list the fill may reach: a low ceiling keeps the
     * grid on everyday words, at the cost of failing more often on the tighter layouts.
     */
    fun generate(
        layout: Layout,
        dictionary: ClueDictionary,
        rng: Random,
        maxRank: Int = Int.MAX_VALUE
    ): Puzzle? {
        val shapes = shapesOf(layout)
        if (shapes.isEmpty()) return null

        val letters = CharArray(layout.width * layout.height) { Puzzle.EMPTY }
        val answers = IntArray(shapes.size) { UNASSIGNED }
        val used = HashSet<String>()
        var nodes = 0

        fun patternOf(shape: SlotShape): CharArray =
            CharArray(shape.length) { letters[shape.cell(layout.width, it)] }

        fun fill(): Boolean {
            if (nodes++ > NODE_BUDGET) return false

            var target = -1
            var fewest = Int.MAX_VALUE
            for (index in shapes.indices) {
                if (answers[index] != UNASSIGNED) continue
                val count = dictionary.matchCount(patternOf(shapes[index]), maxRank)
                if (count == 0) return false
                if (count < fewest) {
                    fewest = count
                    target = index
                }
            }
            if (target < 0) return true

            val shape = shapes[target]
            val pattern = patternOf(shape)
            // The noise is drawn once per candidate: a key recomputed during the sort would make
            // the comparator inconsistent.
            val candidates = dictionary.matches(pattern, maxRank)
                .map { it to dictionary.rank(it) + rng.nextInt(RANK_NOISE) }
                .sortedBy { (_, key) -> key }
                .take(CANDIDATES_PER_SLOT)
                .map { (id, _) -> id }

            for (candidate in candidates) {
                val word = dictionary.word(candidate)
                if (!used.add(word)) continue
                for (position in 0 until shape.length) {
                    letters[shape.cell(layout.width, position)] = word[position]
                }
                answers[target] = candidate
                if (fill()) return true
                answers[target] = UNASSIGNED
                used.remove(word)
                // Only the cells this word alone had filled go back to empty.
                for (position in 0 until shape.length) {
                    if (pattern[position] == Puzzle.EMPTY) {
                        letters[shape.cell(layout.width, position)] = Puzzle.EMPTY
                    }
                }
            }
            return false
        }

        if (!fill()) return null

        val slots = shapes.mapIndexed { index, shape ->
            val wordId = answers[index]
            val clue = dictionary.clues(wordId).random(rng)
            Slot(
                row = shape.row,
                col = shape.col,
                across = shape.across,
                answer = dictionary.word(wordId),
                clue = clue.text,
                explanation = clue.explanation
            )
        }
        return Puzzle(layout.width, layout.height, layout.definitionCells, slots)
    }

    /** Every run of 3 or more letter cells; runs of one belong to the crossing word only. */
    private fun shapesOf(layout: Layout): List<SlotShape> {
        val shapes = mutableListOf<SlotShape>()
        fun scan(across: Boolean) {
            val outer = if (across) layout.height else layout.width
            val inner = if (across) layout.width else layout.height
            for (line in 0 until outer) {
                var run = 0
                for (position in 0..inner) {
                    val row = if (across) line else position
                    val col = if (across) position else line
                    val isLetter = position < inner && (row * layout.width + col) !in layout.definitionCells
                    if (isLetter) {
                        run++
                    } else {
                        if (run >= MIN_WORD) {
                            val startRow = if (across) line else position - run
                            val startCol = if (across) position - run else line
                            shapes.add(SlotShape(startRow, startCol, across, run))
                        }
                        run = 0
                    }
                }
            }
        }
        scan(across = true)
        scan(across = false)
        return shapes
    }
}
