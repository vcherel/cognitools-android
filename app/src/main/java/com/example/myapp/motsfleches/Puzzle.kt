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

/** A grid plus what has been typed into it. Revealed cells are the ones the player gave up on. */
data class PuzzleState(
    val puzzle: Puzzle,
    val entries: String,
    val revealed: Set<Int> = emptySet()
) {
    fun letterAt(cell: Int): Char = entries.getOrElse(cell) { Puzzle.EMPTY }

    fun withLetter(cell: Int, letter: Char): PuzzleState =
        copy(entries = entries.take(cell) + letter + entries.drop(cell + 1))

    /** Every cell of the word filled in, right or wrong. */
    fun isFilled(slot: Slot): Boolean = (0 until slot.length).all {
        letterAt(puzzle.index(slot.cellRow(it), slot.cellCol(it))) != Puzzle.EMPTY
    }

    fun isCorrect(slot: Slot): Boolean = (0 until slot.length).all {
        letterAt(puzzle.index(slot.cellRow(it), slot.cellCol(it))) == slot.answer[it]
    }

    val isComplete: Boolean
        get() = puzzle.slots.all { isFilled(it) }

    val isSolved: Boolean
        get() = puzzle.slots.all { isCorrect(it) }

    companion object {
        fun blank(puzzle: Puzzle): PuzzleState =
            PuzzleState(puzzle, buildString { repeat(puzzle.cellCount) { append(Puzzle.EMPTY) } })
    }
}
