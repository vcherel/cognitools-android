package com.example.myapp.notes

import com.example.myapp.deaccented
import com.example.myapp.matchNormalized

// The Car Mechanic Simulator note: the spare parts held in the game, as plain text under three
// separators (Stock, Pris du stock, À acheter). A part line is "Name +N (Q)": the game's quality
// bonus (only some parts carry one) and a quantity, both optional. Two lines are the
// same part when their names fold to the same key; a +3 and an unrated one stay separate lines.

const val CAR_STOCK_SECTION = "Stock"
const val CAR_TAKEN_SECTION = "Pris du stock"
const val CAR_TO_BUY_SECTION = "À acheter"

data class CarPart(
    val name: String,
    val score: Int? = null,
    val quantity: Int = 1,
    val checked: Boolean = false
) {
    val key: String get() = name.matchNormalized()

    /** The name with its bonus, what a message calls the part. */
    val label: String get() = if (score == null) name else "$name +$score"

    fun render(): String = if (quantity > 1) "$label ($quantity)" else label
}

data class CarPartsNote(
    val stock: List<CarPart> = emptyList(),
    val taken: List<CarPart> = emptyList(),
    val toBuy: List<CarPart> = emptyList()
) {
    val hasShopping: Boolean get() = taken.isNotEmpty() || toBuy.isNotEmpty()

    fun stockOf(name: String): List<CarPart> {
        val key = name.matchNormalized()
        return stock.filter { it.key == key }
    }

    fun withStock(part: CarPart): CarPartsNote = copy(stock = merged(stock + part))

    /**
     * One part of the in-game shopping list entered. Units the stock can supply move to Pris du
     * stock, the rest goes to À acheter. [bestRated] takes the highest bonus first and falls back
     * to an unrated part; without it only unrated parts are taken and the rated ones stay put.
     */
    fun request(name: String, quantity: Int, bestRated: Boolean): CarRequest {
        val key = name.matchNormalized()
        val remainingStock = stock.toMutableList()
        val takenNow = mutableListOf<CarPart>()
        var remaining = quantity
        while (remaining > 0) {
            val (index, candidate) = remainingStock.withIndex()
                .filter { (_, part) -> part.key == key && (bestRated || part.score == null) }
                .maxByOrNull { (_, part) -> part.score ?: -1 } ?: break
            val units = minOf(candidate.quantity, remaining)
            remaining -= units
            if (units == candidate.quantity) remainingStock.removeAt(index)
            else remainingStock[index] = candidate.copy(quantity = candidate.quantity - units)
            takenNow += candidate.copy(quantity = units)
        }
        // Keep the spelling the stock already uses, so the buy list and the stock agree.
        val spelling = stock.firstOrNull { it.key == key }?.name ?: name
        val buy = if (remaining > 0) CarPart(spelling, quantity = remaining) else null
        return CarRequest(
            note = copy(
                stock = remainingStock,
                taken = merged(taken + takenNow),
                toBuy = if (buy == null) toBuy else merged(toBuy + buy)
            ),
            taken = merged(takenNow),
            toBuy = buy
        )
    }

    /** Shopping done: what was bought went into the car, what was taken from stock too. */
    fun finishShopping(): CarPartsNote = copy(taken = emptyList(), toBuy = emptyList())

    fun render(): String = buildString {
        appendLine(SEPARATOR_PREFIX + CAR_STOCK_SECTION)
        stock.forEach { appendLine(it.render()) }
        if (taken.isNotEmpty()) {
            appendLine()
            appendLine(SEPARATOR_PREFIX + CAR_TAKEN_SECTION)
            taken.forEach { appendLine(it.render()) }
        }
        if (toBuy.isNotEmpty()) {
            appendLine()
            appendLine(SEPARATOR_PREFIX + CAR_TO_BUY_SECTION)
            toBuy.forEach {
                appendLine((if (it.checked) CHECKED_PREFIX else UNCHECKED_PREFIX) + it.render())
            }
        }
    }.trimEnd()
}

/** What one [CarPartsNote.request] did, for the message under the bar. */
data class CarRequest(val note: CarPartsNote, val taken: List<CarPart>, val toBuy: CarPart?)

private val SCORE_SUFFIX = Regex("""\s*\+(\d+)$""")
private val TIMES_SUFFIX = Regex("""\s+[x×](\d+)$""")

/** A note line as a part, null for a blank or separator line. Checkbox prefixes are ignored. */
private fun parseCarPart(line: String): CarPart? {
    if (line.isSeparatorLine()) return null
    val text = line.checkboxText().trim()
    if (text.isEmpty()) return null
    val quantity = text.itemQuantity()
    val unquantified = text.withoutQuantitySuffix()
    val score = SCORE_SUFFIX.find(unquantified)?.groupValues?.get(1)?.toIntOrNull()
    val name = SCORE_SUFFIX.replace(unquantified, "").trim()
    if (name.isEmpty()) return null
    return CarPart(name, score, quantity)
}

/**
 * What was typed in the bar: the name, then "+3", "x2" or "(2)" in any order at the end, so
 * "camshaft v8 x2 +3" and "camshaft v8 +3 (2)" both read as two +3 camshafts.
 */
fun parseCarPartInput(text: String): CarPart? {
    var rest = text.trim()
    var score: Int? = null
    var quantity = 1
    while (true) {
        val times = TIMES_SUFFIX.find(rest)
        if (times != null) {
            quantity *= times.groupValues[1].toInt()
            rest = rest.removeRange(times.range).trimEnd()
            continue
        }
        val quantified = rest.withoutQuantitySuffix()
        if (quantified != rest) {
            quantity *= rest.itemQuantity()
            rest = quantified
            continue
        }
        val bonus = SCORE_SUFFIX.find(rest)
        if (bonus != null) {
            score = bonus.groupValues[1].toInt()
            rest = rest.removeRange(bonus.range).trimEnd()
            continue
        }
        break
    }
    if (rest.isEmpty()) return null
    return CarPart(rest, score, quantity.coerceAtLeast(1))
}

/** The note's text read into its three sections. Lines under no known separator count as stock. */
fun parseCarPartsNote(content: String): CarPartsNote {
    val stock = mutableListOf<CarPart>()
    val taken = mutableListOf<CarPart>()
    val toBuy = mutableListOf<CarPart>()
    val takenKey = CAR_TAKEN_SECTION.matchNormalized()
    val toBuyKey = CAR_TO_BUY_SECTION.matchNormalized()
    var section = CAR_STOCK_SECTION.matchNormalized()
    content.lineSequence().forEach { line ->
        if (line.isSeparatorLine()) {
            section = line.separatorName().matchNormalized()
            return@forEach
        }
        val part = parseCarPart(line) ?: return@forEach
        when (section) {
            takenKey -> taken += part
            toBuyKey -> toBuy += part.copy(checked = line.isCheckedLine())
            else -> stock += part
        }
    }
    return CarPartsNote(merged(stock), merged(taken), merged(toBuy))
}

// Same part, same bonus (and same tick, in the buy list) folded into one line, first spelling kept.
// Sorted by name, then unrated before rated, then rising bonus.
private fun merged(parts: List<CarPart>): List<CarPart> =
    parts.groupBy { Triple(it.key, it.score, it.checked) }
        .values
        .map { group -> group.first().copy(quantity = group.sumOf { it.quantity }) }
        .sortedWith(compareBy({ it.name.deaccented() }, { it.score ?: -1 }))

/**
 * The names to propose for what is being typed: every name ever entered plus what the stock holds,
 * the ones starting with the query first, then the ones used most. An empty query proposes the
 * most used names.
 */
fun suggestCarPartNames(
    query: String,
    counts: Map<String, Int>,
    stockNames: List<String>,
    limit: Int = 8
): List<String> {
    val known = LinkedHashMap<String, Pair<String, Int>>()
    counts.forEach { (name, count) -> known[name.matchNormalized()] = name to count }
    stockNames.forEach { name -> known.putIfAbsent(name.matchNormalized(), name to 0) }
    val folded = query.trim().deaccented()
    return known.values
        .filter { (name, _) -> folded.isEmpty() || name.deaccented().contains(folded) }
        .sortedWith(
            compareByDescending<Pair<String, Int>> { (name, _) -> name.deaccented().startsWith(folded) }
                .thenByDescending { (_, count) -> count }
                .thenBy { (name, _) -> name.deaccented() }
        )
        .take(limit)
        .map { it.first }
}
