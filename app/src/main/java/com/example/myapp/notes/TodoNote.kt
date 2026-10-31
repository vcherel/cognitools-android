package com.example.myapp.notes

import android.content.Context
import com.example.myapp.flashcards.AppDatabase

/**
 * Adds "[ ] [text]" as the last item of the Todo list's active block (above its first separator),
 * which is what the pinned widget shows. Creates the note if it doesn't exist yet, and leaves it
 * alone when an item with that text is already there, checked or not. Returns true when the note changed.
 */
suspend fun addToTodoNote(context: Context, text: String): Boolean {
    val item = text.trim()
    if (item.isEmpty()) return false
    val dao = AppDatabase.get(context).noteDao()
    val note = dao.getNotes().firstOrNull { noteTitleAndPreview(it).first.equals(TODO_LIST_TITLE, ignoreCase = true) }
        ?: Note(title = TODO_LIST_TITLE)
    val lines = if (note.content.isEmpty()) mutableListOf() else note.content.split("\n").toMutableList()
    if (lines.any { it.isCheckboxLine() && it.checkboxText().trim().equals(item, ignoreCase = true) }) return false

    val blockEnd = lines.indexOfFirst { it.isSeparatorLine() }.let { if (it == -1) lines.size else it }
    val insertAt = (blockEnd - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }?.plus(1) ?: blockEnd
    lines.add(insertAt, UNCHECKED_PREFIX + item)
    dao.upsertNote(note.copy(content = lines.joinToString("\n"), updatedAt = System.currentTimeMillis()))
    return true
}
