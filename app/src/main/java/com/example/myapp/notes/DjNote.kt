package com.example.myapp.notes

import android.content.Context
import com.example.myapp.flashcards.AppDatabase

/** The note listing the tracks Valentin wants to download. */
const val DJ_NOTE_TITLE = "DJ"

// Ends a DJ note line whose MP3 the app already saved in Download.
private const val DOWNLOADED_SUFFIX = " (téléchargé)"

private fun djLine(artist: String, title: String): String? {
    val name = title.trim()
    if (name.isEmpty()) return null
    return if (artist.isBlank()) name else "${artist.trim()} - $name"
}

private suspend fun djNote(context: Context): Note? =
    AppDatabase.get(context).noteDao().getNotes()
        .firstOrNull { it.title.trim().equals(DJ_NOTE_TITLE, ignoreCase = true) }

/**
 * Appends "Artiste - Titre" at the end of the DJ note, which is what marks a track as one to
 * download, or with [downloaded] "Artiste - Titre (téléchargé)", turning the plain line into that
 * one when it is already there. Creates the note if it doesn't exist yet, and leaves the note alone
 * when the line is already as wanted. Returns true when the note changed.
 */
suspend fun appendToDjNote(context: Context, artist: String, title: String, downloaded: Boolean = false): Boolean {
    val line = djLine(artist, title) ?: return false
    val marked = line + DOWNLOADED_SUFFIX
    val note = djNote(context) ?: Note(title = DJ_NOTE_TITLE)
    val lines = note.content.split("\n").toMutableList()
    if (lines.any { it.trim().equals(marked, ignoreCase = true) }) return false
    val plain = lines.indexOfFirst { it.trim().equals(line, ignoreCase = true) }
    if (plain != -1 && !downloaded) return false

    val content = when {
        plain != -1 -> {
            lines[plain] = marked
            lines.joinToString("\n")
        }
        note.content.isBlank() -> if (downloaded) marked else line
        else -> note.content.trimEnd('\n') + "\n" + (if (downloaded) marked else line)
    }
    AppDatabase.get(context).noteDao().upsertNote(note.copy(content = content, updatedAt = System.currentTimeMillis()))
    return true
}

/** True when the DJ note says the track was already downloaded, even if the file has since moved. */
suspend fun isDownloadedInDjNote(context: Context, artist: String, title: String): Boolean {
    val marked = (djLine(artist, title) ?: return false) + DOWNLOADED_SUFFIX
    return djNote(context)?.content?.lineSequence()?.any { it.trim().equals(marked, ignoreCase = true) } == true
}
