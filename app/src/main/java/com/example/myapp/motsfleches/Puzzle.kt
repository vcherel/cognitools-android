package com.example.myapp.motsfleches

/**
 * One word to find: where its first letter sits, which way it runs, and the definition that leads
 * to it. The definition is printed in the cell just before the word: left of an across word, above
 * a down word.
 */
data class Slot(
    val row: Int,
    val col: Int,
    val across: Boolean,
    val answer: String,
    val clue: String,
    /** The definition in full; the clue is that same sentence cut down to fit the cell. */
    val explanation: String
) {
    val length: Int get() = answer.length
    val clueRow: Int get() = if (across) row else row - 1
    val clueCol: Int get() = if (across) col - 1 else col

    fun cellRow(position: Int): Int = if (across) row else row + position
    fun cellCol(position: Int): Int = if (across) col + position else col
}

/** A filled grid: which cells hold definitions, and every word placed in it. */
class Puzzle(
    val width: Int,
    val height: Int,
    val definitionCells: Set<Int>,
    val slots: List<Slot>
) {
    val cellCount: Int get() = width * height

    /** The answer letters, indexed like the grid; definition cells hold [EMPTY]. */
    val solution: CharArray = CharArray(cellCount) { EMPTY }

    // Which word runs through each cell, so tapping a cell knows what it is part of.
    private val acrossAt = IntArray(cellCount) { -1 }
    private val downAt = IntArray(cellCount) { -1 }

    init {
        slots.forEachIndexed { slotIndex, slot ->
            for (position in 0 until slot.length) {
                val cell = index(slot.cellRow(position), slot.cellCol(position))
                solution[cell] = slot.answer[position]
                if (slot.across) acrossAt[cell] = slotIndex else downAt[cell] = slotIndex
            }
        }
    }

    fun index(row: Int, col: Int): Int = row * width + col

    fun isDefinition(row: Int, col: Int): Boolean = index(row, col) in definitionCells

    /** Every cell a word runs through, in reading order. */
    fun cellsOf(slot: Slot): List<Int> =
        (0 until slot.length).map { index(slot.cellRow(it), slot.cellCol(it)) }

    /** The letter that belongs in a cell; [Puzzle.EMPTY] on a definition cell. */
    fun answerAt(cell: Int): Char = solution[cell]

    fun slotIndexAt(row: Int, col: Int, across: Boolean): Int? {
        val cell = index(row, col)
        val slotIndex = if (across) acrossAt[cell] else downAt[cell]
        return slotIndex.takeIf { it >= 0 }
    }

    /** The word a definition cell introduces in one direction, if it carries one. */
    fun slotFromDefinition(row: Int, col: Int, across: Boolean): Slot? {
        val startRow = if (across) row else row + 1
        val startCol = if (across) col + 1 else col
        if (startRow !in 0 until height || startCol !in 0 until width) return null
        val slotIndex = slotIndexAt(startRow, startCol, across) ?: return null
        val slot = slots[slotIndex]
        return slot.takeIf { it.row == startRow && it.col == startCol }
    }

    companion object {
        const val EMPTY = ' '
    }
}

/**
 * A grid plus what has been typed into it. Revealed cells are the ones the player gave up on,
 * confirmed cells the ones a check found right; both are marked so they read differently from an
 * ordinary entry.
 */
data class PuzzleState(
    val puzzle: Puzzle,
    val entries: String,
    val revealed: Set<Int> = emptySet(),
    val confirmed: Set<Int> = emptySet()
) {
    fun letterAt(cell: Int): Char = entries.getOrElse(cell) { Puzzle.EMPTY }

    fun withLetter(cell: Int, letter: Char): PuzzleState =
        copy(entries = entries.take(cell) + letter + entries.drop(cell + 1))

    private fun unmarked(cell: Int): PuzzleState =
        copy(revealed = revealed - cell, confirmed = confirmed - cell)

    /**
     * Typing over a cell. Typing the letter that is already there changes nothing at all, so a
     * revealed or checked cell keeps its mark instead of turning back into an ordinary entry.
     */
    fun typed(cell: Int, letter: Char): PuzzleState =
        if (letterAt(cell) == letter) this else withLetter(cell, letter).unmarked(cell)

    fun cleared(cell: Int): PuzzleState = withLetter(cell, Puzzle.EMPTY).unmarked(cell)

    /** Wipes a whole word, crossing letters included: what the "effacer le mot" action does. */
    fun clearSlot(slot: Slot): PuzzleState =
        puzzle.cellsOf(slot).fold(this) { state, cell -> state.cleared(cell) }

    /** Writes the answer in one cell and marks it as given away. */
    fun revealCell(cell: Int): PuzzleState =
        withLetter(cell, puzzle.answerAt(cell))
            .copy(revealed = revealed + cell, confirmed = confirmed - cell)

    fun revealSlot(slot: Slot): PuzzleState =
        puzzle.cellsOf(slot).fold(this) { state, cell -> state.revealCell(cell) }

    fun filledIn(cells: List<Int>): List<Int> = cells.filter { letterAt(it) != Puzzle.EMPTY }

    /** The cells among [cells] that hold a letter, and the wrong one. */
    fun wrongIn(cells: List<Int>): List<Int> =
        filledIn(cells).filter { letterAt(it) != puzzle.answerAt(it) }

    /** Keeps the filled cells that are right marked as checked from now on. */
    fun confirming(cells: List<Int>): PuzzleState =
        copy(confirmed = confirmed + filledIn(cells).filter { letterAt(it) == puzzle.answerAt(it) })

    /** Every cell of the word filled in, right or wrong. */
    fun isFilled(slot: Slot): Boolean =
        puzzle.cellsOf(slot).all { letterAt(it) != Puzzle.EMPTY }

    fun isCorrect(slot: Slot): Boolean =
        puzzle.cellsOf(slot).all { letterAt(it) == puzzle.answerAt(it) }

    val isComplete: Boolean
        get() = puzzle.slots.all { isFilled(it) }

    val isSolved: Boolean
        get() = puzzle.slots.all { isCorrect(it) }

    companion object {
        fun blank(puzzle: Puzzle): PuzzleState =
            PuzzleState(puzzle, buildString { repeat(puzzle.cellCount) { append(Puzzle.EMPTY) } })
    }
}
