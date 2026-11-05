package com.example.myapp.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val PDF_MIME = "application/pdf"

private const val CACHE_BYTES = 96 * 1024 * 1024

/** Android refuses to draw a bitmap over 100 MB. */
private const val MAX_BITMAP_BYTES = 64 * 1024 * 1024

/**
 * An open PDF, its pages drawn on demand through the platform's PdfRenderer. The renderer only holds
 * one page open at a time and is not thread safe, so every use goes through the mutex.
 */
class PdfDocument(private val renderer: PdfRenderer) {
    /** Width over height of every page, read up front so the list knows each page's height. */
    val pageAspects: List<Float> = List(renderer.pageCount) { index ->
        renderer.openPage(index).use { it.width.coerceAtLeast(1).toFloat() / it.height.coerceAtLeast(1) }
    }

    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LruCache<Pair<Int, Int>, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Pair<Int, Int>, value: Bitmap) = value.allocationByteCount
    }

    fun cached(page: Int, width: Int): Bitmap? = cache.get(page to width)

    /** Null once the document is closed. */
    suspend fun render(page: Int, width: Int): Bitmap? = cached(page, width) ?: withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            cached(page, width) ?: draw(page, width).also { cache.put(page to width, it) }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closed = true
            renderer.close()
        }
    }

    private fun draw(index: Int, requestedWidth: Int): Bitmap = renderer.openPage(index).use { page ->
        val requestedHeight = requestedWidth / pageAspects[index]
        val bytes = requestedWidth * requestedHeight * 4
        val fit = if (bytes > MAX_BITMAP_BYTES) sqrt(MAX_BITMAP_BYTES / bytes) else 1f
        val bitmap = Bitmap.createBitmap(
            (requestedWidth * fit).roundToInt().coerceAtLeast(1),
            (requestedHeight * fit).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }
}

suspend fun openPdf(context: Context, uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
    val descriptor = try {
        seekableDescriptor(context, uri)
    } catch (e: IOException) {
        throw IllegalStateException("Can't open this file", e)
    } catch (e: SecurityException) {
        throw IllegalStateException("Can't open this file", e)
    }
    try {
        PdfDocument(PdfRenderer(descriptor))
    } catch (e: SecurityException) {
        descriptor.close()
        throw IllegalStateException("This PDF is password protected", e)
    } catch (e: IOException) {
        descriptor.close()
        throw IllegalStateException("Unreadable PDF", e)
    }
}

/**
 * PdfRenderer needs to seek, which a pipe handed over by another app can't: those go through a copy
 * of their own, unlinked once open so it lasts as long as the descriptor and leaves nothing behind.
 */
private fun seekableDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
    val resolver = context.contentResolver
    val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Unreadable file")
    if (descriptor.statSize >= 0) return descriptor
    descriptor.close()
    val copy = File.createTempFile("opened", ".pdf", context.cacheDir)
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("Unreadable file")
        input.use { source -> copy.outputStream().use { source.copyTo(it) } }
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
    } finally {
        copy.delete()
    }
}

private val PDF_HEADER = "%PDF-".toByteArray()

/** By its type or, for a sender that labels everything a generic binary, by its first bytes. */
fun isPdf(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver
    if (resolver.getType(uri) == PDF_MIME) return true
    val header = resolver.openInputStream(uri)?.use { it.readNBytes(PDF_HEADER.size) }
    return header != null && header.contentEquals(PDF_HEADER)
}

/** The name a document goes by, without its extension. */
fun documentTitle(context: Context, uri: Uri): String {
    val displayName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
    return (displayName ?: uri.lastPathSegment)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "PDF"
}
