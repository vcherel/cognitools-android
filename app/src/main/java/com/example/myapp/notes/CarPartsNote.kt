package com.example.myapp.notes

import com.example.myapp.deaccented
import com.example.myapp.matchNormalized

// The Car Mechanic Simulator note: the spare parts held in the game, as plain text under
// separators. A part line is "Name +N xQ": the game's quality bonus (only some parts carry one)
// and a quantity, both optional. The quantity is never the "(Q)" of the other notes because part
// names carry their own parentheses ("Bolt (12)"). Two lines are the same part when their names
// fold to the same key; a +3 and an unrated one stay separate lines.
//
// The header star switches between two jobs sharing one stock: repairs (no star) use the unrated
// parts only, and the rated ones are hidden; tuning (star) sees the whole stock and takes the best
// bonus first. Each job keeps its own Pris du stock / À acheter pair, the other one hidden.

const val CAR_STOCK_SECTION = "Stock"
// The star lists keep the plain names: the lists the note held before the two jobs were star ones.
const val CAR_TAKEN_SECTION = "Pris du stock"
const val CAR_TO_BUY_SECTION = "À acheter"
const val CAR_TAKEN_REPAIR_SECTION = "Pris du stock (réparations)"
const val CAR_TO_BUY_REPAIR_SECTION = "À acheter (réparations)"

private val TAKEN_KEY = CAR_TAKEN_SECTION.matchNormalized()
private val TO_BUY_KEY = CAR_TO_BUY_SECTION.matchNormalized()
private val TAKEN_REPAIR_KEY = CAR_TAKEN_REPAIR_SECTION.matchNormalized()
private val TO_BUY_REPAIR_KEY = CAR_TO_BUY_REPAIR_SECTION.matchNormalized()

data class CarPart(
    val name: String,
    val score: Int? = null,
    val quantity: Int = 1,
    val checked: Boolean = false
) {
    val key: String get() = name.matchNormalized()

    /** The name with its bonus, what a message calls the part. */
    val label: String get() = if (score == null) name else "$name +$score"

    fun render(): String = if (quantity > 1) "$label x$quantity" else label
}

/** One job's shopping: the units taken from stock and what is left to buy. */
data class CarShopping(val taken: List<CarPart> = emptyList(), val toBuy: List<CarPart> = emptyList()) {
    val isEmpty: Boolean get() = taken.isEmpty() && toBuy.isEmpty()
}

data class CarPartsNote(
    val stock: List<CarPart> = emptyList(),
    val repair: CarShopping = CarShopping(),
    val tuning: CarShopping = CarShopping()
) {
    fun shopping(star: Boolean): CarShopping = if (star) tuning else repair

    fun hasShopping(star: Boolean): Boolean = !shopping(star).isEmpty

    private fun withShopping(star: Boolean, shopping: CarShopping): CarPartsNote =
        if (star) copy(tuning = shopping) else copy(repair = shopping)

    /** The stock lines held for [name] that [star] shows: all of them, or the unrated ones. */
    fun stockOf(name: String, star: Boolean = true): List<CarPart> {
        val key = name.matchNormalized()
        return stock.filter { it.key == key && (star || it.score == null) }
    }

    fun withStock(part: CarPart): CarPartsNote = copy(stock = merged(stock + part))

    /** The stock A to Z, same-name lines with the same bonus folded together. */
    fun sorted(): CarPartsNote = copy(stock = merged(stock))

    /**
     * One part of the in-game shopping list entered, into [star]'s lists. Units the stock can
     * supply move to Pris du stock, the rest goes to À acheter. With [star] the highest bonus goes
     * first and an unrated part is the fallback; without it only unrated parts are taken.
     */
    fun request(name: String, quantity: Int, star: Boolean): CarRequest {
        val key = name.matchNormalized()
        val remainingStock = stock.toMutableList()
        val takenNow = mutableListOf<CarPart>()
        var remaining = quantity
        while (remaining > 0) {
            val (index, candidate) = remainingStock.withIndex()
                .filter { (_, part) -> part.key == key && (star || part.score == null) }
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
        val shopping = shopping(star)
        return CarRequest(
            note = copy(stock = remainingStock).withShopping(
                star,
                CarShopping(
                    taken = merged(shopping.taken + takenNow),
                    toBuy = if (buy == null) shopping.toBuy else merged(shopping.toBuy + buy)
                )
            ),
            taken = merged(takenNow),
            toBuy = buy
        )
    }

    /** [star]'s shopping done: what was bought went into the car, what was taken from stock too. */
    fun finishShopping(star: Boolean): CarPartsNote = withShopping(star, CarShopping())

    /** The shopping lists on top, repairs then tuning, then the stock. */
    fun render(): String = listOfNotNull(
        section(CAR_TO_BUY_REPAIR_SECTION, repair.toBuy.map { it.renderChecked() }),
        section(CAR_TAKEN_REPAIR_SECTION, repair.taken.map { it.render() }),
        section(CAR_TO_BUY_SECTION, tuning.toBuy.map { it.renderChecked() }),
        section(CAR_TAKEN_SECTION, tuning.taken.map { it.render() }),
        (listOf(SEPARATOR_PREFIX + CAR_STOCK_SECTION) + stock.map { it.render() }).joinToString("\n")
    ).joinToString("\n\n")
}

private fun CarPart.renderChecked(): String = (if (checked) CHECKED_PREFIX else UNCHECKED_PREFIX) + render()

private fun section(name: String, lines: List<String>): String? =
    if (lines.isEmpty()) null else (listOf(SEPARATOR_PREFIX + name) + lines).joinToString("\n")

/**
 * The indices of the note's lines [star] hides: the other job's shopping sections (separator,
 * lines and the blank after them), and without the star the rated stock lines.
 */
fun carHiddenLines(content: String, star: Boolean): Set<Int> {
    val hidden = mutableSetOf<Int>()
    var section = CAR_STOCK_SECTION.matchNormalized()
    content.split("\n").forEachIndexed { index, line ->
        if (line.isSeparatorLine()) section = line.separatorName().matchNormalized()
        val hide = when (section) {
            TAKEN_REPAIR_KEY, TO_BUY_REPAIR_KEY -> star
            TAKEN_KEY, TO_BUY_KEY -> !star
            else -> !star && parseCarPart(line)?.score != null
        }
        if (hide) hidden += index
    }
    return hidden
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
    return parseCarPartInput(text)
}

/**
 * A part line or what was typed in the bar: the name, then "+3" and "x2" in any order at the
 * end, so "camshaft v8 x2 +3" and "camshaft v8 +3 x2" both read as two +3 camshafts.
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
        val bonus = SCORE_SUFFIX.find(rest)
        if (bonus != null) {
            score = bonus.groupValues[1].toInt()
            rest = rest.removeRange(bonus.range).trimEnd()
            continue
        }
        break
    }
    val name = rest.cleanCarPartName()
    if (name.isEmpty()) return null
    return CarPart(name, score, quantity.coerceAtLeast(1))
}

/** A part name without the comma some lines were typed with before the bonus ("Bougie, +3"). */
fun String.cleanCarPartName(): String = trim().trimEnd(',', ' ')

/** The note's text read into its sections. Lines under no known separator count as stock. */
fun parseCarPartsNote(content: String): CarPartsNote {
    val stock = mutableListOf<CarPart>()
    val sections = mutableMapOf<String, MutableList<CarPart>>()
    var section = CAR_STOCK_SECTION.matchNormalized()
    content.lineSequence().forEach { line ->
        if (line.isSeparatorLine()) {
            section = line.separatorName().matchNormalized()
            return@forEach
        }
        val part = parseCarPart(line) ?: return@forEach
        when (section) {
            TAKEN_KEY, TAKEN_REPAIR_KEY -> sections.getOrPut(section) { mutableListOf() } += part
            TO_BUY_KEY, TO_BUY_REPAIR_KEY ->
                sections.getOrPut(section) { mutableListOf() } += part.copy(checked = line.isCheckedLine())
            else -> stock += part
        }
    }
    fun of(key: String) = merged(sections[key].orEmpty())
    return CarPartsNote(
        stock = merged(stock),
        repair = CarShopping(of(TAKEN_REPAIR_KEY), of(TO_BUY_REPAIR_KEY)),
        tuning = CarShopping(of(TAKEN_KEY), of(TO_BUY_KEY))
    )
}

// Same part, same bonus (and same tick, in the buy list) folded into one line, first spelling kept.
// Sorted by name, then unrated before rated, then rising bonus.
private fun merged(parts: List<CarPart>): List<CarPart> =
    parts.groupBy { Triple(it.key, it.score, it.checked) }
        .values
        .map { group -> group.first().copy(quantity = group.sumOf { it.quantity }) }
        .sortedWith(compareBy({ it.name.deaccented() }, { it.score ?: -1 }))

/**
 * The names to propose for what is being typed: every name ever entered plus what the stock holds.
 * The ones starting with the query come first, then the ones containing it, then, from 4 letters
 * typed, the ones within a typo or two of it, each group by use. An empty query proposes the most
 * used names.
 */
fun suggestCarPartNames(
    query: String,
    counts: Map<String, Int>,
    stockNames: List<String>,
    limit: Int = 30
): List<String> {
    val known = LinkedHashMap<String, Pair<String, Int>>()
    counts.forEach { (name, count) -> known[name.matchNormalized()] = name to count }
    stockNames.forEach { name -> known.putIfAbsent(name.matchNormalized(), name to 0) }
    val folded = query.trim().deaccented()
    val compact = query.matchNormalized()
    val maxTypos = if (compact.length < 4) 0 else compact.length / 4
    // 0 prefix, 1 contains, 2 + typos for a near miss; null leaves the name out.
    fun rank(name: String): Int? {
        val candidate = name.deaccented()
        return when {
            folded.isEmpty() || candidate.startsWith(folded) -> 0
            candidate.contains(folded) -> 1
            maxTypos == 0 -> null
            else -> substringDistance(compact, name.matchNormalized()).takeIf { it <= maxTypos }?.plus(2)
        }
    }
    return known.values
        .mapNotNull { (name, count) -> rank(name)?.let { Triple(name, count, it) } }
        .sortedWith(
            compareBy<Triple<String, Int, Int>> { it.third }
                .thenByDescending { it.second }
                .thenBy { it.first.deaccented() }
        )
        .take(limit)
        .map { it.first }
}

/** The fewest edits turning [pattern] into some stretch of [text] (Sellers' approximate match). */
private fun substringDistance(pattern: String, text: String): Int {
    var previous = IntArray(text.length + 1)
    for (i in 1..pattern.length) {
        val current = IntArray(text.length + 1)
        current[0] = i
        for (j in 1..text.length) {
            val substitution = previous[j - 1] + if (pattern[i - 1] == text[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        previous = current
    }
    return previous.min()
}
