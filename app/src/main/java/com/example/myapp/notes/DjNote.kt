package com.example.myapp.notes

import android.content.Context
import com.example.myapp.flashcards.AppDatabase

/** The note listing the tracks Valentin wants to download. */
const val DJ_NOTE_TITLE = "DJ"

/**
 * Appends "Artiste - Titre" at the end of the DJ note, which is what marks a track as one to
 * download. Creates the note if it doesn't exist yet, and leaves the note alone when the line is
 * already there. Returns true when a line was actually added.
 */
suspend fun appendToDjNote(context: Context, artist: String, title: String): Boolean {
    val name = title.trim()
    if (name.isEmpty()) return false
    val line = if (artist.isBlank()) name else "${artist.trim()} - $name"

    val dao = AppDatabase.get(context).noteDao()
    val note = dao.getNotes().firstOrNull { it.title.trim().equals(DJ_NOTE_TITLE, ignoreCase = true) }
        ?: Note(title = DJ_NOTE_TITLE)
    if (note.content.lineSequence().any { it.trim().equals(line, ignoreCase = true) }) return false

    val content = if (note.content.isBlank()) line else note.content.trimEnd('\n') + "\n" + line
    dao.upsertNote(note.copy(content = content, updatedAt = System.currentTimeMillis()))
    return true
}
