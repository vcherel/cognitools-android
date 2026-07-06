package com.example.myapp.notes

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(json: JSONObject): Note {
            return Note(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", ""),
                content = json.optString("content", ""),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        fun listFromJsonString(jsonString: String): List<Note> {
            return try {
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

fun Note.toJson(): JSONObject = JSONObject().also {
    it.put("id", id)
    it.put("title", title)
    it.put("content", content)
    it.put("updatedAt", updatedAt)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    suspend fun getNotes(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: String): Note?

    @Upsert suspend fun upsertNote(note: Note)
    @Upsert suspend fun upsertNotes(notes: List<Note>)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: String)
}

// A note is plain text; a line starting with one of these prefixes renders as a checkbox.
const val UNCHECKED_PREFIX = "[ ] "
const val CHECKED_PREFIX = "[x] "

fun String.isCheckboxLine(): Boolean =
    startsWith(UNCHECKED_PREFIX) || startsWith(CHECKED_PREFIX)

fun String.isCheckedLine(): Boolean = startsWith(CHECKED_PREFIX)

/** The line without its checkbox prefix, or the line itself for plain lines. */
fun String.checkboxText(): String =
    if (isCheckboxLine()) substring(UNCHECKED_PREFIX.length) else this

/**
 * Display title and preview line. The title field wins when set; otherwise
 * the first non empty content line is the title, as before the field existed.
 */
fun noteTitleAndPreview(note: Note): Pair<String, String> {
    val lines = note.content.lineSequence()
        .map { it.checkboxText().trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .toList()
    return if (note.title.isNotBlank()) {
        note.title to lines.getOrElse(0) { "" }
    } else {
        lines.getOrElse(0) { "Note vide" } to lines.getOrElse(1) { "" }
    }
}

/** True if any line of the content is a checkbox line. */
fun String.hasCheckboxLine(): Boolean = lineSequence().any { it.isCheckboxLine() }

/** Content with every checkbox line set to the given state. */
fun setAllCheckboxes(content: String, checked: Boolean): String {
    val prefix = if (checked) CHECKED_PREFIX else UNCHECKED_PREFIX
    return content.lineSequence().joinToString("\n") { line ->
        if (line.isCheckboxLine()) prefix + line.checkboxText() else line
    }
}
