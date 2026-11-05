package com.example.myapp.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A book in the library. The epub or PDF itself is copied into the app's files, so the picked document
 * doesn't have to stay where it was, and the reading position lives here rather than in a separate
 * progress file: it is written on every scroll pause and the row is the smallest thing to write.
 * For a PDF the chapters are its pages, and blockOffset how far the page is scrolled at 1x.
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

private const val PDF_COVER_WIDTH = 450

private fun booksDir(context: Context): File =
    File(context.filesDir, "books").also { it.mkdirs() }

val Book.isPdf: Boolean get() = fileName.endsWith(".pdf")

fun bookFile(context: Context, book: Book): File = File(booksDir(context), book.fileName)

fun coverFile(context: Context, book: Book): File? =
    book.coverFileName?.let { File(booksDir(context), it) }

/**
 * Copies the picked file in, reads what it says about itself, and files it. Anything that turns out
 * to be neither a PDF nor an epub leaves nothing behind.
 */
suspend fun importBook(context: Context, uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
    val id = UUID.randomUUID().toString()
    var target: File? = null
    runCatching {
        val pdf = isPdf(context, uri)
        val file = File(booksDir(context), if (pdf) "$id.pdf" else "$id.epub").also { target = it }
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: error("Unreadable file")

        val book = if (pdf) pdfBook(context, id, file, documentTitle(context, uri)) else epubBook(context, id, file)
        book.also { AppDatabase.get(context).bookDao().upsert(it) }
    }.onFailure {
        target?.delete()
        File(booksDir(context), "$id-cover").delete()
    }
}

/**
 * What a failed book read says on screen. A book is a local file, so an IOException is a bad file
 * (a zip that isn't one, a missing entry), where userMessage would blame the network.
 */
internal fun bookError(e: Throwable, fallback: String): String =
    if (e is IOException) fallback else userMessage(e, fallback)

private fun epubBook(context: Context, id: String, file: File): Book {
    val metadata = Epub.metadata(file)
    val coverName = metadata.coverHref?.let { href ->
        Epub.entry(file, href)?.let { bytes ->
            File(booksDir(context), "$id-cover").also { it.writeBytes(bytes) }.name
        }
    }
    return Book(
        id = id,
        title = metadata.title,
        author = metadata.author,
        fileName = file.name,
        coverFileName = coverName,
        chapterCount = metadata.chapters.size
    )
}

/** A PDF says little about itself: the title is the file's name, the cover its first page. */
private suspend fun pdfBook(context: Context, id: String, file: File, title: String): Book {
    val document = openPdf(context, Uri.fromFile(file))
    try {
        val cover = File(booksDir(context), "$id-cover")
        document.render(0, PDF_COVER_WIDTH)?.let { bitmap ->
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return Book(
            id = id,
            title = title,
            author = "",
            fileName = file.name,
            coverFileName = cover.name.takeIf { cover.exists() },
            chapterCount = document.pageAspects.size
        )
    } finally {
        document.close()
    }
}

/** Permanent: the file was copied in, so nothing else holds it. */
suspend fun deleteBook(context: Context, book: Book) = withContext(Dispatchers.IO) {
    bookFile(context, book).delete()
    coverFile(context, book)?.delete()
    AppDatabase.get(context).bookDao().delete(book.id)
}
