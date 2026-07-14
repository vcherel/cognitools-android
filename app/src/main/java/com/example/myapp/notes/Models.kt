package com.example.myapp.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
    val color: Int = 0,
    // When true, the note is gated behind the app PIN (see NoteLock).
    val locked: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): Note {
            return Note(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", ""),
                content = json.optString("content", ""),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                color = json.optInt("color", 0),
                locked = json.optBoolean("locked", false)
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
    it.put("locked", locked)
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
    @Query("UPDATE notes SET locked = 0") suspend fun unlockAll()
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

// Inline markers within a line: **gras**, *italique*, ***les deux***,
// __souligné__. The markers stay in the stored text and are hidden when
// rendering. Content inside a marker pair is parsed again, so markers combine.
fun String.formatInline(): AnnotatedString {
    val s = this
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '*' || c == '_') {
                var run = 1
                val maxRun = if (c == '*') 3 else 2
                while (run < maxRun && i + run < s.length && s[i + run] == c) run++
                if (c == '*' || run == 2) {
                    val marker = c.toString().repeat(run)
                    val close = s.indexOf(marker, i + run)
                    if (close > i + run) {
                        val style = when {
                            c == '_' -> SpanStyle(textDecoration = TextDecoration.Underline)
                            run == 3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                            run == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                            else -> SpanStyle(fontStyle = FontStyle.Italic)
                        }
                        withStyle(style) { append(s.substring(i + run, close).formatInline()) }
                        i = close + run
                        continue
                    }
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

// A checkbox line may end with "(N)" to mean the item counts N times, e.g. "Yaourts (4)".
private val QUANTITY_SUFFIX = Regex("""\((\d+)\)\s*$""")

/** The quantity from a trailing "(N)" suffix, or 1 if there is none. */
fun String.itemQuantity(): Int = QUANTITY_SUFFIX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 1

/** Sum of quantities of unchecked checkbox lines, honoring the "(N)" suffix. */
fun String.uncheckedItemCount(): Int = lineSequence()
    .filter { it.isCheckboxLine() && !it.isCheckedLine() }
    .sumOf { it.checkboxText().itemQuantity() }

/** Content with every checkbox line set to the given state. */
fun setAllCheckboxes(content: String, checked: Boolean): String {
    val prefix = if (checked) CHECKED_PREFIX else UNCHECKED_PREFIX
    return content.lineSequence().joinToString("\n") { line ->
        if (line.isCheckboxLine()) prefix + line.checkboxText() else line
    }
}

/** Content with blank lines at the very top and bottom removed. */
fun String.trimBlankEdgeLines(): String {
    val lines = split("\n")
    val start = lines.indexOfFirst { it.isNotBlank() }
    if (start == -1) return ""
    val end = lines.indexOfLast { it.isNotBlank() }
    return lines.subList(start, end + 1).joinToString("\n")
}
