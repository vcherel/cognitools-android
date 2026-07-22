package com.example.myapp.gallery

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

sealed interface WriteOutcome {
    data object Done : WriteOutcome
    data class NeedsConsent(val intentSender: IntentSender) : WriteOutcome
    data class Error(val message: String) : WriteOutcome
}

private val PROJECTION = arrayOf(
    MediaStore.Files.FileColumns._ID,
    MediaStore.Files.FileColumns.DISPLAY_NAME,
    MediaStore.Files.FileColumns.MEDIA_TYPE,
    MediaStore.Files.FileColumns.DATE_ADDED,
    MediaStore.Files.FileColumns.DATE_MODIFIED,
    MediaStore.Files.FileColumns.BUCKET_ID,
    MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
    MediaStore.Files.FileColumns.MIME_TYPE,
    MediaStore.Files.FileColumns.DATA
)

fun queryAlbums(context: Context): List<Album> {
    val items = queryMediaItems(context)
    return items.groupBy { it.bucketId }
        .map { (_, group) ->
            val cover = group.first()
            cover.dateAdded to Album(
                bucketId = cover.bucketId,
                name = cover.bucketName,
                relativePath = cover.relativePath,
                coverUri = cover.uri,
                coverDateModified = cover.dateModified,
                itemCount = group.size
            )
        }
        .sortedByDescending { it.first }
        .map { it.second }
}

fun queryMediaItemById(context: Context, id: Long): MediaItem? =
    queryMediaItems(context, itemId = id).firstOrNull()

fun queryMediaItems(context: Context, bucketId: Long? = null, itemId: Long? = null): List<MediaItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val selection = buildString {
        append("(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)")
        if (bucketId != null) append(" AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?")
        if (itemId != null) append(" AND ${MediaStore.Files.FileColumns._ID} = ?")
    }
    val args = buildList {
        add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        if (bucketId != null) add(bucketId.toString())
        if (itemId != null) add(itemId.toString())
    }.toTypedArray()

    val items = mutableListOf<MediaItem>()
    context.contentResolver.query(
        collection,
        PROJECTION,
        selection,
        args,
        "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
        val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        @Suppress("DEPRECATION")
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val data = cursor.getString(dataCol) ?: ""
            val mediaType = if (cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                MediaType.VIDEO
            } else {
                MediaType.IMAGE
            }
            // createWriteRequest/createDeleteRequest reject Uris from the generic Files
            // collection ("All requested items must be Media items"), so build the Uri from the
            // type-specific Images/Video collection instead; it addresses the same underlying row.
            val typedCollection = if (mediaType == MediaType.VIDEO) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            items.add(
                MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(typedCollection, id),
                    displayName = cursor.getString(nameCol) ?: "",
                    type = mediaType,
                    dateAdded = cursor.getLong(dateCol),
                    dateModified = cursor.getLong(dateModifiedCol),
                    bucketId = cursor.getLong(bucketIdCol),
                    bucketName = cursor.getString(bucketNameCol) ?: "Autres",
                    relativePath = relativeFolderFromData(data),
                    mimeType = cursor.getString(mimeCol) ?: ""
                )
            )
        }
    }
    return items
}

// Folder part of a filesystem path, relative to the primary external storage root and with a
// trailing slash, matching MediaStore's RELATIVE_PATH convention (e.g. "DCIM/Camera/").
@Suppress("DEPRECATION")
private fun relativeFolderFromData(data: String): String {
    val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
    val withoutRoot = if (data.startsWith(root)) data.removePrefix(root) else data.substringAfter("/0/", data)
    val folder = withoutRoot.substringBeforeLast('/', "")
    return if (folder.isEmpty()) "" else "$folder/"
}

// Grants write access for uris not owned by this app (API 30+ shows a one-time system consent
// dialog via requestConsent), then runs `write`. On API 29, `write` itself may fail with a
// RecoverableSecurityException, in which case we ask for consent and retry once. Below API 29,
// legacy full storage access means `write` just succeeds directly.
private suspend fun performMediaWrite(
    context: Context,
    uris: List<Uri>,
    requestConsent: suspend (IntentSender) -> Boolean,
    write: () -> WriteOutcome
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
        val pending = MediaStore.createWriteRequest(context.contentResolver, uris)
        if (!requestConsent(pending.intentSender)) return false
    }
    return when (val outcome = write()) {
        WriteOutcome.Done -> true
        is WriteOutcome.NeedsConsent -> requestConsent(outcome.intentSender) && write() == WriteOutcome.Done
        is WriteOutcome.Error -> false
    }
}

suspend fun performRename(
    context: Context,
    item: MediaItem,
    newName: String,
    requestConsent: suspend (IntentSender) -> Boolean
): Boolean = performMediaWrite(context, listOf(item.uri), requestConsent) {
    renameMediaItem(context, item, newName)
}

private fun renameMediaItem(context: Context, item: MediaItem, newName: String): WriteOutcome {
    val values = ContentValues().apply { put(MediaStore.Files.FileColumns.DISPLAY_NAME, newName) }
    return try {
        val rows = context.contentResolver.update(item.uri, values, null, null)
        if (rows > 0) WriteOutcome.Done else WriteOutcome.Error("Renommage impossible")
    } catch (e: RecoverableSecurityException) {
        WriteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender)
    } catch (e: Exception) {
        WriteOutcome.Error(e.message ?: "Erreur")
    }
}

suspend fun performMove(
    context: Context,
    item: MediaItem,
    targetRelativePath: String,
    requestConsent: suspend (IntentSender) -> Boolean
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return performMediaWrite(context, listOf(item.uri), requestConsent) {
            moveMediaItemLegacy(context, item, targetRelativePath)
        }
    }
    // Fast path: works for items in shared collections (Camera, Screenshots, Downloads...).
    val moved = performMediaWrite(context, listOf(item.uri), requestConsent) {
        updateRelativePath(context, item, targetRelativePath)
    }
    if (moved) return true
    // Falls back here for items MediaProvider refuses to reassign in place, e.g. photos still
    // living in another app's private Android/media/<package>/ folder (WhatsApp, ...): copy the
    // bytes into a new entry at the target location, then delete the original.
    return copyThenDeleteMove(context, item, targetRelativePath, requestConsent)
}

private fun updateRelativePath(context: Context, item: MediaItem, targetRelativePath: String): WriteOutcome {
    val values = ContentValues().apply { put(MediaStore.Files.FileColumns.RELATIVE_PATH, targetRelativePath) }
    return try {
        val rows = context.contentResolver.update(item.uri, values, null, null)
        if (rows > 0) WriteOutcome.Done else WriteOutcome.Error("Déplacement impossible")
    } catch (e: RecoverableSecurityException) {
        WriteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender)
    } catch (e: Exception) {
        WriteOutcome.Error(e.message ?: "Erreur")
    }
}

private suspend fun copyThenDeleteMove(
    context: Context,
    item: MediaItem,
    targetRelativePath: String,
    requestConsent: suspend (IntentSender) -> Boolean
): Boolean {
    val collection = if (item.type == MediaType.VIDEO) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val values = ContentValues().apply {
        put(MediaStore.Files.FileColumns.DISPLAY_NAME, item.displayName)
        put(MediaStore.Files.FileColumns.MIME_TYPE, item.mimeType)
        put(MediaStore.Files.FileColumns.RELATIVE_PATH, targetRelativePath)
        put(MediaStore.Files.FileColumns.IS_PENDING, 1)
    }
    val newUri = context.contentResolver.insert(collection, values) ?: return false
    val copied = try {
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            context.contentResolver.openOutputStream(newUri)?.use { output -> input.copyTo(output) }
        } != null
    } catch (e: Exception) {
        false
    }
    if (!copied) {
        context.contentResolver.delete(newUri, null, null)
        return false
    }
    context.contentResolver.update(
        newUri,
        ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
        null,
        null
    )
    return performDelete(context, item, requestConsent)
}

@Suppress("DEPRECATION")
private fun moveMediaItemLegacy(context: Context, item: MediaItem, targetRelativePath: String): WriteOutcome {
    return try {
        val root = Environment.getExternalStorageDirectory()
        val targetDir = File(root, targetRelativePath).apply { mkdirs() }
        val sourceFile = File(root, item.relativePath + item.displayName)
        val destFile = File(targetDir, item.displayName)
        if (!sourceFile.renameTo(destFile)) return WriteOutcome.Error("Déplacement impossible")
        val values = ContentValues().apply { put(MediaStore.Files.FileColumns.DATA, destFile.absolutePath) }
        context.contentResolver.update(item.uri, values, null, null)
        MediaScannerConnection.scanFile(context, arrayOf(sourceFile.absolutePath, destFile.absolutePath), null, null)
        WriteOutcome.Done
    } catch (e: Exception) {
        WriteOutcome.Error(e.message ?: "Erreur")
    }
}

suspend fun performDelete(
    context: Context,
    item: MediaItem,
    requestConsent: suspend (IntentSender) -> Boolean
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
        val pending = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
        return requestConsent(pending.intentSender)
    }
    return when (val outcome = deleteMediaItem(context, item)) {
        WriteOutcome.Done -> true
        is WriteOutcome.NeedsConsent -> requestConsent(outcome.intentSender) && deleteMediaItem(context, item) == WriteOutcome.Done
        is WriteOutcome.Error -> false
    }
}

// Deletes several items in one shot. On API 30+ MediaStore shows a single system consent dialog
// covering the whole batch; on older versions we fall back to deleting one by one.
suspend fun performDeleteBatch(
    context: Context,
    items: List<MediaItem>,
    requestConsent: suspend (IntentSender) -> Boolean
): Boolean {
    if (items.isEmpty()) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
        val pending = MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
        return requestConsent(pending.intentSender)
    }
    var allOk = true
    for (item in items) {
        if (!performDelete(context, item, requestConsent)) allOk = false
    }
    return allOk
}

// Moves several items with a single write consent on API 30+. Each item is reassigned in place;
// any MediaProvider refuses (e.g. another app's private media dir) falls back to copy+delete,
// mirroring the single-item performMove.
suspend fun performMoveBatch(
    context: Context,
    items: List<MediaItem>,
    targetRelativePath: String,
    requestConsent: suspend (IntentSender) -> Boolean
): Boolean {
    if (items.isEmpty()) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        var ok = true
        for (item in items) if (!performMove(context, item, targetRelativePath, requestConsent)) ok = false
        return ok
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
        val pending = MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri })
        if (!requestConsent(pending.intentSender)) return false
    }
    var allOk = true
    for (item in items) {
        if (updateRelativePath(context, item, targetRelativePath) != WriteOutcome.Done) {
            if (!copyThenDeleteMove(context, item, targetRelativePath, requestConsent)) allOk = false
        }
    }
    return allOk
}

private fun deleteMediaItem(context: Context, item: MediaItem): WriteOutcome {
    return try {
        val rows = context.contentResolver.delete(item.uri, null, null)
        if (rows > 0) WriteOutcome.Done else WriteOutcome.Error("Suppression impossible")
    } catch (e: RecoverableSecurityException) {
        WriteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender)
    } catch (e: Exception) {
        WriteOutcome.Error(e.message ?: "Erreur")
    }
}

// Used to save a crop or a video trim back into the original file (overwrite in place).
suspend fun performOverwrite(
    context: Context,
    item: MediaItem,
    requestConsent: suspend (IntentSender) -> Boolean,
    writeBytes: (OutputStream) -> Unit
): Boolean = performMediaWrite(context, listOf(item.uri), requestConsent) {
    overwriteMediaItemBytes(context, item, writeBytes)
}

private fun overwriteMediaItemBytes(context: Context, item: MediaItem, writeBytes: (OutputStream) -> Unit): WriteOutcome {
    return try {
        context.contentResolver.openOutputStream(item.uri, "wt")?.use(writeBytes)
            ?: return WriteOutcome.Error("Impossible d'ouvrir le fichier")
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        context.contentResolver.update(item.uri, values, null, null)
        WriteOutcome.Done
    } catch (e: RecoverableSecurityException) {
        WriteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender)
    } catch (e: Exception) {
        WriteOutcome.Error(e.message ?: "Erreur")
    }
}
