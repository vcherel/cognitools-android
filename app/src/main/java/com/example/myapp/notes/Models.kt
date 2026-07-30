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
    val updatedAt: Long = System.currentTimeMillis(),
    // Index into the note color palette; 0 means no color.
    val color: Int = 0,
    // When true, the note is gated behind the app PIN (see NoteLock).
    val locked: Boolean = false,
    // When the note was moved to the trash, 0 while it is a normal note. Trashed notes are hidden
    // everywhere but the trash screen and are purged after NOTES_TRASH_RETENTION_DAYS days.
    val deletedAt: Long = 0
) {
    companion object {
        fun fromJson(json: JSONObject): Note {
            return Note(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", ""),
                content = json.optString("content", ""),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                color = json.optInt("color", 0),
                locked = json.optBoolean("locked", false),
                deletedAt = json.optLong("deletedAt", 0)
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
    it.put("deletedAt", deletedAt)
}

fun notesToJsonString(notes: List<Note>): String {
    val array = JSONArray()
    notes.forEach { array.put(it.toJson()) }
    return array.toString()
}

// How long a trashed note is kept before it is purged for good, matching the gallery trash.
const val NOTES_TRASH_RETENTION_DAYS = 30

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAt = 0 ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE deletedAt = 0")
    suspend fun getNotes(): List<Note>

    @Query("SELECT * FROM notes WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrashedNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: String): Note?

    @Upsert suspend fun upsertNote(note: Note)
    @Upsert suspend fun upsertNotes(notes: List<Note>)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: String)
    @Query("UPDATE notes SET locked = 0") suspend fun unlockAll()

    @Query("UPDATE notes SET deletedAt = :now WHERE id = :id")
    suspend fun trashNote(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET deletedAt = 0 WHERE id = :id")
    suspend fun restoreNote(id: String)

    @Query("DELETE FROM notes WHERE deletedAt > 0")
    suspend fun emptyNotesTrash()

    /** Drops trashed notes whose retention window is over. Called at app start. */
    @Query("DELETE FROM notes WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun purgeExpiredTrashedNotes(cutoff: Long)
}
