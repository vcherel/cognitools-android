package com.example.myapp.files

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Everything the file explorer does to the filesystem, plus the intents it hands other apps.
 * Plain java.io.File: the app already holds MANAGE_EXTERNAL_STORAGE (see the manifest), so no
 * SAF tree and no per-operation consent dialog is involved.
 */

/** The top of the tree the explorer lets you walk, and where it starts. */
val storageRoot: File get() = Environment.getExternalStorageDirectory()
val downloadsDir: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val childCount: Int
) {
    val path: String get() = file.absolutePath
}

/** Folders first, then files, each newest first. Hidden entries are listed like any other. */
fun listFolder(dir: File): List<FileEntry> {
    val children = dir.listFiles() ?: return emptyList()
    return children
        .map { child ->
            val isDir = child.isDirectory
            FileEntry(
                file = child,
                isDirectory = isDir,
                name = child.name,
                size = if (isDir) 0L else child.length(),
                lastModified = child.lastModified(),
                childCount = if (isDir) (child.list()?.size ?: 0) else 0
            )
        }
        .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.lastModified })
}

/** The parent folder, or null once at the top of the walkable tree. */
fun parentOf(dir: File): File? {
    if (dir.absolutePath == storageRoot.absolutePath) return null
    val parent = dir.parentFile ?: return null
    return if (parent.absolutePath.startsWith(storageRoot.absolutePath)) parent else null
}

/** The path split into (label, folder) pairs, from the storage root down to `dir`. */
fun breadcrumb(dir: File): List<Pair<String, File>> {
    val chain = mutableListOf<File>()
    var current: File? = dir
    while (current != null) {
        chain.add(0, current)
        current = parentOf(current)
    }
    return chain.map { folder ->
        val label = if (folder.absolutePath == storageRoot.absolutePath) "Stockage" else folder.name
        label to folder
    }
}

fun formatFileSize(bytes: Long): String {
    val units = listOf("o", "Ko", "Mo", "Go", "To")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = if (unit == 0 || value >= 100) "%.0f" else "%.1f"
    return String.format(Locale.FRANCE, pattern, value) + " " + units[unit]
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayFormat = SimpleDateFormat("d MMM", Locale.FRANCE)
private val fullFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

fun formatFileDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> timeFormat.format(then.time)
        sameYear && dayDiff == 1 -> "hier"
        sameYear -> dayFormat.format(then.time)
        else -> fullFormat.format(then.time)
    }
}

fun mimeTypeOf(file: File): String {
    val extension = file.extension.lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * Hands the file to whatever app can show it. False when nothing on the phone can.
 *
 * [defaultComponent] is the app already chosen for this extension (see [FileDefaults]): it opens
 * without asking anything. Without one, or with [forceChooser], the system sheet asks instead and
 * reports what was picked to [FileOpenChoiceReceiver], which remembers it for next time.
 */
fun openFile(
    context: Context,
    file: File,
    defaultComponent: String? = null,
    forceChooser: Boolean = false
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uriFor(context, file), mimeTypeOf(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val extension = file.extension.lowercase(Locale.ROOT)
    if (!forceChooser && defaultComponent != null) {
        val component = ComponentName.unflattenFromString(defaultComponent)
        if (component != null) {
            try {
                context.startActivity(Intent(intent).setComponent(component))
                return true
            } catch (e: ActivityNotFoundException) {
                // The app was uninstalled or no longer handles this: fall back to asking again.
            }
        }
    }
    val chooser = if (extension.isEmpty()) Intent.createChooser(intent, "Ouvrir avec")
    else Intent.createChooser(intent, "Ouvrir avec", chosenAppCallback(context, extension).intentSender)
    return try {
        context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun chosenAppCallback(context: Context, extension: String): PendingIntent {
    val callback = Intent(context, FileOpenChoiceReceiver::class.java)
        .putExtra(EXTRA_OPENED_EXTENSION, extension)
    // Mutable on purpose: the chooser is what fills EXTRA_CHOSEN_COMPONENT in.
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    return PendingIntent.getBroadcast(context, extension.hashCode(), callback, flags)
}

fun shareFiles(context: Context, files: List<File>): Boolean {
    if (files.isEmpty()) return false
    val uris = ArrayList(files.map { uriFor(context, it) })
    val mimeTypes = files.map { mimeTypeOf(it) }.distinct()
    val type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            setType(type)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            setType(type)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(intent, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** "photo.jpg" next to an existing "photo.jpg" becomes "photo (2).jpg". */
fun uniqueTarget(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "")
    var index = 2
    while (candidate.exists()) {
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        candidate = File(dir, "$base ($index)$suffix")
        index++
    }
    return candidate
}

fun renameEntry(file: File, newName: String): Boolean {
    val trimmed = newName.trim()
    if (trimmed.isEmpty() || trimmed.contains('/')) return false
    val target = File(file.parentFile, trimmed)
    if (target.exists()) return false
    return file.renameTo(target)
}

fun createFolder(parent: File, name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.contains('/')) return false
    val target = File(parent, trimmed)
    if (target.exists()) return false
    return target.mkdirs()
}

/** Deletes files and folders (recursively). Returns how many of them went. */
fun deleteEntries(files: List<File>): Int = files.count { it.deleteRecursively() }

/**
 * Copies into `destination`, renaming around name clashes. Returns how many were copied whole.
 */
fun copyEntries(files: List<File>, destination: File): Int = files.count { source ->
    if (isInside(source, destination)) return@count false
    val target = uniqueTarget(destination, source.name)
    runCatching {
        if (source.isDirectory) source.copyRecursively(target, overwrite = false)
        else source.copyTo(target)
    }.isSuccess
}

/**
 * Moves into `destination`. A rename is enough within one volume; across volumes it falls back to
 * a copy then a delete.
 */
fun moveEntries(files: List<File>, destination: File): Int = files.count { source ->
    if (isInside(source, destination) || source.parentFile?.absolutePath == destination.absolutePath) {
        return@count false
    }
    val target = uniqueTarget(destination, source.name)
    if (source.renameTo(target)) return@count true
    runCatching {
        if (source.isDirectory) source.copyRecursively(target, overwrite = false)
        else source.copyTo(target)
        source.deleteRecursively()
    }.isSuccess
}

/** Guards against dropping a folder into itself, which would recurse forever. */
private fun isInside(folder: File, candidate: File): Boolean =
    folder.isDirectory && (candidate.absolutePath + "/").startsWith(folder.absolutePath + "/")
