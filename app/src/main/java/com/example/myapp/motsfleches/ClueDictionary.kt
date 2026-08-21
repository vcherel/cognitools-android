package com.example.myapp.motsfleches

import java.io.InputStream

/**
 * A definition: the short form printed in the grid cell, and the whole sentence it was cut from,
 * which is what the clue bar shows. [related] marks the "Synonyme de ..." / "Contraire de ..."
 * clues, which the generator uses sparingly.
 */
data class Clue(val text: String, val explanation: String, val related: Boolean = false)

/**
 * The words the grids are filled with, indexed so the generator can ask "which words of length 5
 * have an A in third position" without scanning the list. One instance per language.
 *
 * Built from `motsfleches_dict_<lang>.txt`, one line per definition:
 *   `WORD<tab>rank<tab>pos<tab>clue<tab>full definition`
 * where rank is the word's frequency order, used to keep common words in front.
 */
class ClueDictionary private constructor(
    private val words: Array<String>,
    private val ranks: IntArray,
    private val clues: Array<List<Clue>>,
    private val lengths: Map<Int, LengthIndex>
) {
    val size: Int get() = words.size

    private val idByWord: Map<String, Int> by lazy { words.indices.associateBy { words[it] } }

    fun word(id: Int): String = words[id]
    fun rank(id: Int): Int = ranks[id]
    fun clues(id: Int): List<Clue> = clues[id]

    /** How common a word is, by its position in the frequency list. Unknown words rank last. */
    fun rankOf(word: String): Int = idByWord[word]?.let { ranks[it] } ?: Int.MAX_VALUE

    /**
     * Words matching a pattern, as ids. [pattern] holds the letters already fixed by crossing
     * words, [Puzzle.EMPTY] where anything goes. [maxRank] keeps the answer inside the most common
     * part of the language: everything rarer than that rank is ignored.
     */
    fun matches(pattern: CharArray, maxRank: Int = Int.MAX_VALUE): IntArray {
        val index = lengths[pattern.size] ?: return IntArray(0)
        return index.matches(pattern, maxRank)
    }

    /** How many words match, without building the list: what the generator orders its slots by. */
    fun matchCount(pattern: CharArray, maxRank: Int = Int.MAX_VALUE): Int {
        val index = lengths[pattern.size] ?: return 0
        return index.matchCount(pattern, maxRank)
    }

    /**
     * Word ids of one length, plus a bitset per (position, letter) to intersect them fast. The ids
     * stay in frequency order, so a rank ceiling is just a prefix of the bitset.
     */
    private class LengthIndex(
        val ids: IntArray,
        val ranks: IntArray,
        val masks: Array<LongArray?>,
        val length: Int
    ) {
        private val wordCount = ids.size
        private val chunks = (wordCount + 63) / 64

        private fun maskFor(position: Int, letter: Char): LongArray? =
            masks[position * ALPHABET + (letter - 'A')]

        /** How many of the words are at most [maxRank]; the ids are sorted by rank. */
        private fun bound(maxRank: Int): Int {
            if (maxRank == Int.MAX_VALUE) return wordCount
            var low = 0
            var high = wordCount
            while (low < high) {
                val middle = (low + high) / 2
                if (ranks[middle] <= maxRank) low = middle + 1 else high = middle
            }
            return low
        }

        /** Bits of this chunk that are inside [limit]; also drops the trailing chunk's padding. */
        private fun boundChunk(chunk: Int, limit: Int): Long {
            val remaining = limit - chunk * 64
            return when {
                remaining >= 64 -> -1L
                remaining <= 0 -> 0L
                else -> (1L shl remaining) - 1L
            }
        }

        private inline fun withIntersection(
            pattern: CharArray,
            maxRank: Int,
            action: (chunk: Int, bits: Long) -> Unit
        ): Boolean {
            for (position in 0 until length) {
                val letter = pattern[position]
                if (letter == Puzzle.EMPTY) continue
                if (letter !in 'A'..'Z' || maskFor(position, letter) == null) return false
            }
            val limit = bound(maxRank)
            for (chunk in 0 until chunks) {
                var bits = boundChunk(chunk, limit)
                if (bits == 0L) continue
                for (position in 0 until length) {
                    val letter = pattern[position]
                    if (letter == Puzzle.EMPTY) continue
                    bits = bits and maskFor(position, letter)!![chunk]
                    if (bits == 0L) break
                }
                if (bits != 0L) action(chunk, bits)
            }
            return true
        }

        fun matchCount(pattern: CharArray, maxRank: Int): Int {
            var total = 0
            val ok = withIntersection(pattern, maxRank) { _, bits -> total += java.lang.Long.bitCount(bits) }
            return if (ok) total else 0
        }

        fun matches(pattern: CharArray, maxRank: Int): IntArray {
            val found = ArrayList<Int>()
            val ok = withIntersection(pattern, maxRank) { chunk, bits ->
                var rest = bits
                while (rest != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(rest)
                    found.add(ids[chunk * 64 + bit])
                    rest = rest and (rest - 1)
                }
            }
            if (!ok) return IntArray(0)
            return found.toIntArray()
        }
    }

    companion object {
        private const val ALPHABET = 26

        /** The French asset's crossword style clues, the only ones not written as a definition. */
        private fun isRelatedClue(text: String): Boolean =
            text.startsWith("Synonyme ") || text.startsWith("Contraire ")

        /** Reads the asset. Cheap enough to do once on a background thread when the tool opens. */
        fun load(input: InputStream): ClueDictionary {
            val words = ArrayList<String>()
            val ranks = ArrayList<Int>()
            val clues = ArrayList<MutableList<Clue>>()
            var lastWord: String? = null

            input.bufferedReader().forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@forEachLine
                val word = parts[0]
                val clue = Clue(parts[3], parts[4], related = isRelatedClue(parts[3]))
                // The asset lists a word's definitions on consecutive lines.
                if (word == lastWord) {
                    clues.last().add(clue)
                } else {
                    words.add(word)
                    ranks.add(parts[1].toIntOrNull() ?: Int.MAX_VALUE)
                    clues.add(mutableListOf(clue))
                    lastWord = word
                }
            }

            // The asset is written in frequency order, so the ids of a length come out sorted too.
            val byLength = words.indices.groupBy { words[it].length }
            val lengths = byLength.mapValues { (length, ids) ->
                val idArray = ids.toIntArray()
                val rankArray = IntArray(idArray.size) { ranks[idArray[it]] }
                val chunks = (idArray.size + 63) / 64
                val masks = arrayOfNulls<LongArray>(length * ALPHABET)
                idArray.forEachIndexed { position, id ->
                    val word = words[id]
                    for (letterIndex in 0 until length) {
                        val slot = letterIndex * ALPHABET + (word[letterIndex] - 'A')
                        val mask = masks[slot] ?: LongArray(chunks).also { masks[slot] = it }
                        mask[position / 64] = mask[position / 64] or (1L shl (position % 64))
                    }
                }
                LengthIndex(idArray, rankArray, masks, length)
            }

            return ClueDictionary(
                words = words.toTypedArray(),
                ranks = ranks.toIntArray(),
                clues = Array(clues.size) { clues[it].toList() },
                lengths = lengths
            )
        }
    }
}
