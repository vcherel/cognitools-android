package com.example.myapp.notes

import android.content.Context
import com.example.myapp.flashcards.AppDatabase

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
 * Adds "[ ] [text]" as the last item of the Todo list's active block (above its first separator),
 * which is what the pinned widget shows, or of the section named [section]. Creates the note and its
 * sections if they don't exist yet, and leaves it alone when an item with that text is already there,
 * checked or not. Returns true when the note changed.
 */
suspend fun addToTodoNote(context: Context, text: String, section: String? = null): Boolean {
    val item = text.trim()
    if (item.isEmpty()) return false
    val dao = AppDatabase.get(context).noteDao()
    val note = findTodoNote(dao) ?: Note(title = TODO_LIST_TITLE)
    val lines = withTodoSections(note.content).split("\n").toMutableList()
    if (lines.any { it.isCheckboxLine() && it.checkboxText().trim().equals(item, ignoreCase = true) }) return false

    val from = section?.let { name ->
        lines.indexOfFirst { it.isSeparatorLine() && it.separatorName().equals(name, ignoreCase = true) } + 1
    } ?: 0
    lines.add(blockAppendIndex(lines, from), UNCHECKED_PREFIX + item)
    dao.upsertNote(note.copy(content = lines.joinToString("\n"), updatedAt = System.currentTimeMillis()))
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
