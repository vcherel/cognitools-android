package com.example.myapp.notes

import android.content.Context
import com.example.myapp.deaccented
import com.example.myapp.flashcards.AppDatabase
import java.time.LocalDate

/** The named sections the Todo list always carries, in order, after its active block. */
val TODO_SECTIONS = listOf(WAITING_SECTION, WEEKEND_SECTION)

/** The content with every section of [TODO_SECTIONS] it lacks added, each right after the one before it. */
fun withTodoSections(content: String): String {
    val lines = if (content.isEmpty()) mutableListOf() else content.split("\n").toMutableList()
    var previousEnd = -1
    for (name in TODO_SECTIONS) {
        val at = lines.indexOfFirst { it.isSeparatorLine() && it.separatorName().equals(name, ignoreCase = true) }
        if (at != -1) {
            previousEnd = blockEnd(lines, at + 1)
            continue
        }
        val insertAt = if (previousEnd == -1) lines.size else previousEnd
        lines.add(insertAt, SEPARATOR_PREFIX + name)
        previousEnd = insertAt + 1
    }
    return lines.joinToString("\n")
}

/** Index of the separator closing the block that starts at [from], or the line count for the last block. */
internal fun blockEnd(lines: List<String>, from: Int): Int =
    (from until lines.size).firstOrNull { lines[it].isSeparatorLine() } ?: lines.size

/** Where a new item goes at the end of the block starting at [from]: after its last non blank line. */
internal fun blockAppendIndex(lines: List<String>, from: Int): Int {
    val end = blockEnd(lines, from)
    return (end - 1 downTo from).firstOrNull { lines[it].isNotBlank() }?.plus(1) ?: end
}

/**
 * The Todo list as edit mode shows it: an empty checkbox wherever the view has an add placeholder (the
 * end of every block, the top of a non empty active block), unless one is already there, with [caret]
 * moved along with the line it sits on.
 */
internal fun withTodoPlaceholders(text: String, caret: Int): Pair<String, Int> {
    val lines = text.split("\n")
    val at = sortedSetOf<Int>()
    if ((0 until blockEnd(lines, 0)).any { lines[it].isNotBlank() }) at += 0
    var start = 0
    lines.forEachIndexed { i, line ->
        if (line.isSeparatorLine()) {
            at += blockAppendIndex(lines, start)
            start = i + 1
        }
    }
    at += blockAppendIndex(lines, start)
    at.removeAll { lines.getOrNull(it - 1) == UNCHECKED_PREFIX || lines.getOrNull(it) == UNCHECKED_PREFIX }
    if (at.isEmpty()) return text to caret

    val out = StringBuilder()
    var newCaret = caret
    var lineStart = 0
    lines.forEachIndexed { i, line ->
        if (i in at) {
            out.append(UNCHECKED_PREFIX).append('\n')
            if (lineStart <= caret) newCaret += UNCHECKED_PREFIX.length + 1
        }
        out.append(line)
        if (i < lines.lastIndex) out.append('\n')
        lineStart += line.length + 1
    }
    if (lines.size in at) out.append('\n').append(UNCHECKED_PREFIX)
    return out.toString() to newCaret
}

/** The text without its empty unchecked items, which mean nothing in the Todo list. */
internal fun withoutEmptyCheckboxes(text: String): String {
    if (!text.contains(UNCHECKED_PREFIX)) return text
    return text.split("\n").filterNot { it.isEmptyUncheckedLine() }.joinToString("\n")
}

private fun String.isEmptyUncheckedLine() = isCheckboxLine() && !isCheckedLine() && checkboxText().isBlank()

private val FRENCH_MONTHS = listOf(
    "janvier", "fevrier", "mars", "avril", "mai", "juin",
    "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
)

private val FRENCH_DATE = Regex("""\b(\d{1,2})(?:er)?\s+(${FRENCH_MONTHS.joinToString("|")})\b(?:\s+(\d{4}))?""")

/**
 * The last French date an item names ("jusqu'au 2 janvier 2028", "le 14 novembre"), its end date. A
 * date without a year is taken this year, or next year when that falls more than two months before
 * [today].
 */
internal fun itemEndDate(text: String, today: LocalDate): LocalDate? {
    val match = FRENCH_DATE.findAll(text.deaccented()).lastOrNull() ?: return null
    val (day, month, year) = match.destructured
    val monthNumber = FRENCH_MONTHS.indexOf(month) + 1
    fun at(y: Int) = runCatching { LocalDate.of(y, monthNumber, day.toInt()) }.getOrNull()
    if (year.isNotEmpty()) return at(year.toInt())
    val thisYear = at(today.year) ?: return null
    return if (thisYear.isBefore(today.minusMonths(2))) at(today.year + 1) else thisYear
}

/**
 * The Todo list with "[ ] [item]" added as the last item of its active block, or of the section named
 * [section]. In the Week-end section an item with an end date goes before the first one ending later.
 * Null when an item with that text is already there, checked or not.
 */
internal fun withTodoItem(content: String, item: String, section: String?, today: LocalDate): String? {
    val lines = withTodoSections(content).split("\n").toMutableList()
    if (lines.any { it.isCheckboxLine() && it.checkboxText().trim().equals(item, ignoreCase = true) }) return null

    val from = section?.let { name ->
        lines.indexOfFirst { it.isSeparatorLine() && it.separatorName().equals(name, ignoreCase = true) } + 1
    } ?: 0
    val ends = if (section.equals(WEEKEND_SECTION, ignoreCase = true)) itemEndDate(item, today) else null
    val later = ends?.let { date ->
        (from until blockEnd(lines, from)).firstOrNull { i ->
            lines[i].isCheckboxLine() && itemEndDate(lines[i].checkboxText(), today)?.isAfter(date) == true
        }
    }
    lines.add(later ?: blockAppendIndex(lines, from), UNCHECKED_PREFIX + item)
    return lines.joinToString("\n")
}

/**
 * Adds [text] to the Todo list through [withTodoItem]: the active block, which is what the pinned
 * widget shows, or the section named [section]. Creates the note and its sections if they don't exist
 * yet. Returns true when the note changed.
 */
suspend fun addToTodoNote(context: Context, text: String, section: String? = null): Boolean {
    val item = text.trim()
    if (item.isEmpty()) return false
    val dao = AppDatabase.get(context).noteDao()
    val note = findTodoNote(dao) ?: Note(title = TODO_LIST_TITLE)
    val content = withTodoItem(note.content, item, section, LocalDate.now()) ?: return false
    dao.upsertNote(note.copy(content = content, updatedAt = System.currentTimeMillis()))
    return true
}

/** Adds the sections the Todo list lacks, at app start, so the note always shows them. */
suspend fun ensureTodoSections(context: Context) {
    val dao = AppDatabase.get(context).noteDao()
    val note = findTodoNote(dao) ?: return
    val content = withTodoSections(note.content)
    if (content != note.content) dao.upsertNote(note.copy(content = content))
}

suspend fun findTodoNote(dao: NoteDao): Note? =
    dao.getNotes().firstOrNull { noteTitleAndPreview(it).first.equals(TODO_LIST_TITLE, ignoreCase = true) }
