package com.example.myapp.reader

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * A book in the library. The epub itself is copied into the app's files, so the picked document
 * doesn't have to stay where it was, and the reading position lives here rather than in a separate
 * progress file: it is written on every scroll pause and the row is the smallest thing to write.
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val fileName: String,
    val coverFileName: String?,
    val chapterCount: Int,
    val chapterIndex: Int = 0,
    /** First visible block of the chapter, and how far it is scrolled past the top. */
    val blockIndex: Int = 0,
    val blockOffset: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: String): Book?

    @Upsert
    suspend fun upsert(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE books SET chapterIndex = :chapter, blockIndex = :block, blockOffset = :offset, lastOpenedAt = :now WHERE id = :id")
    suspend fun saveProgress(id: String, chapter: Int, block: Int, offset: Int, now: Long)
}

private fun booksDir(context: Context): File =
    File(context.filesDir, "books").also { it.mkdirs() }

fun bookFile(context: Context, book: Book): File = File(booksDir(context), book.fileName)

fun coverFile(context: Context, book: Book): File? =
    book.coverFileName?.let { File(booksDir(context), it) }

/**
 * Copies the picked epub in, reads what it says about itself, and files it. Anything that turns out
 * not to be an epub leaves nothing behind.
 */
suspend fun importEpub(context: Context, uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
    val id = UUID.randomUUID().toString()
    val target = File(booksDir(context), "$id.epub")
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error("Fichier illisible")

        val metadata = Epub.metadata(target)
        val coverName = metadata.coverHref?.let { href ->
            Epub.entry(target, href)?.let { bytes ->
                File(booksDir(context), "$id-cover").also { it.writeBytes(bytes) }.name
            }
        }

        Book(
            id = id,
            title = metadata.title,
            author = metadata.author,
            fileName = target.name,
            coverFileName = coverName,
            chapterCount = metadata.chapters.size
        ).also { AppDatabase.get(context).bookDao().upsert(it) }
    }.onFailure { target.delete() }
}

/** Permanent: the epub was copied in, so nothing else holds it. */
suspend fun deleteBook(context: Context, book: Book) = withContext(Dispatchers.IO) {
    bookFile(context, book).delete()
    coverFile(context, book)?.delete()
    AppDatabase.get(context).bookDao().delete(book.id)
}
