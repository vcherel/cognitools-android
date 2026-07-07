package com.example.myapp.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    val updatedAt: Long = System.currentTimeMillis(),
    // Index into the note color palette; 0 means no color.
    val color: Int = 0
) {
    companion object {
        fun fromJson(json: JSONObject): Note {
            return Note(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", ""),
                content = json.optString("content", ""),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                color = json.optInt("color", 0)
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
    it.put("color", color)
}

fun notesToJsonString(notes: List<Note>): String {
    val array = JSONArray()
    notes.forEach { array.put(it.toJson()) }
    return array.toString()
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

// Inline markers within a line: **gras**, *italique*, ***les deux***. The
// markers stay in the stored text and are hidden when rendering.
fun String.formatInline(): AnnotatedString {
    val s = this
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '*') {
                var stars = 1
                while (stars < 3 && i + stars < s.length && s[i + stars] == '*') stars++
                val marker = "*".repeat(stars)
                val close = s.indexOf(marker, i + stars)
                if (close > i + stars) {
                    val style = when (stars) {
                        3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                        2 -> SpanStyle(fontWeight = FontWeight.Bold)
                        else -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    withStyle(style) { append(s.substring(i + stars, close)) }
                    i = close + stars
                    continue
                }
            }
            append(s[i])
            i++
        }
    }
}

/**
 * Display title and preview line. The title field wins when set; otherwise
 * the first non empty content line is the title, as before the field existed.
 */
fun noteTitleAndPreview(note: Note): Pair<String, String> {
    val lines = note.content.lineSequence()
        .filterNot { it.isSeparatorLine() }
        .map { it.checkboxText().formatInline().text.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .toList()
    return if (note.title.isNotBlank()) {
        note.title to lines.getOrElse(0) { "" }
    } else {
        lines.getOrElse(0) { "Note vide" } to lines.getOrElse(1) { "" }
    }
}

// A line with this prefix renders as a horizontal separator named by the rest
// of the line; a bare "---" is an unnamed separator.
const val SEPARATOR_PREFIX = "--- "

fun String.isSeparatorLine(): Boolean = startsWith(SEPARATOR_PREFIX) || this == "---"

/** The separator name, empty for an unnamed separator. */
fun String.separatorName(): String =
    if (startsWith(SEPARATOR_PREFIX)) substring(SEPARATOR_PREFIX.length).trim() else ""

/** True if any line of the content is a checkbox line. */
fun String.hasCheckboxLine(): Boolean = lineSequence().any { it.isCheckboxLine() }

/** Content with every checkbox line set to the given state. */
fun setAllCheckboxes(content: String, checked: Boolean): String {
    val prefix = if (checked) CHECKED_PREFIX else UNCHECKED_PREFIX
    return content.lineSequence().joinToString("\n") { line ->
        if (line.isCheckboxLine()) prefix + line.checkboxText() else line
    }
}
