package com.example.myapp

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.calendar.calendarToJson
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.notes.notesToJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Off the screen's scope: leaving the screen mid-write used to cancel the job and leave a
// truncated backup.
private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val KEEP_BACKUPS = 3
private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val BACKUP_STEM = "cognitools_backup"

private val Context.backupDataStore by preferencesDataStore("backup_prefs")

private val cloudKey = longPreferencesKey("cloud_$BACKUP_STEM")

private suspend fun backupJson(context: Context): String {
    val db = AppDatabase.get(context)
    return JSONObject()
        .put("version", 1)
        .put("notes", notesToJson(db.noteDao().getNotes()))
        .put("flashcards", context.flashcardRepository.backupJson())
        .put("calendar", calendarToJson(db.calendarDao().getAll()))
        .toString(2)
}

/**
 * Writes one bundle of the notes, flashcards and calendar to Téléchargements and uploads it to
 * Yahoo, keeping the [KEEP_BACKUPS] newest in both places, then says in a toast where it landed.
 */
private fun saveBackup(context: Context): Job = backupScope.launch {
    val fileName = "${BACKUP_STEM}_${LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"
    val json = try {
        backupJson(context)
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Backup failed: ${userMessage(e)}", Toast.LENGTH_LONG).show()
        }
        return@launch
    }
    val localOk = runCatching {
        writeToDownloads(context, fileName, json)
        pruneDownloads()
    }.isSuccess
    val cloudError = runCatching {
        context.mailRepository.uploadBackup(BACKUP_STEM, fileName, json, KEEP_BACKUPS)
        context.backupDataStore.edit { it[cloudKey] = System.currentTimeMillis() }
    }.exceptionOrNull()
    val message = when {
        localOk && cloudError == null -> "Saved on the phone and on Yahoo"
        localOk -> "Saved on the phone, not on Yahoo: ${userMessage(cloudError!!)}"
        cloudError == null -> "Saved on Yahoo, not on the phone"
        else -> "Backup failed: ${userMessage(cloudError)}"
    }
    withContext(Dispatchers.Main) {
        Toast.makeText(context, message, if (localOk && cloudError == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }
}

/**
 * The menu's save button. Its icon tells how long ago Yahoo last got a copy: a done cloud under
 * 3 days, then an upload cloud in orange, in red from 7 days.
 */
@Composable
fun BackupMenuButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lastBackup by remember { context.backupDataStore.data.map { it[cloudKey] ?: 0L } }
        .collectAsState(initial = null)
    var saving by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            if (!saving) scope.launch {
                saving = true
                saveBackup(context).join()
                saving = false
            }
        },
        modifier = modifier
    ) {
        val age = lastBackup?.let { System.currentTimeMillis() - it }
        when {
            saving -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            age == null -> Unit
            age < 3 * DAY_MS -> Icon(Icons.Default.CloudDone, contentDescription = "Back up")
            else -> Icon(
                Icons.Default.CloudUpload,
                contentDescription = "Back up",
                tint = if (age < 7 * DAY_MS) Color(0xFFFF9800) else Color(0xFFE53935)
            )
        }
    }
}

// A second save the same day gets a " (1)" suffix from MediaStore rather than overwriting the
// first. IS_PENDING hides the file until it is fully written.
private fun writeToDownloads(context: Context, fileName: String, json: String) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("File unreadable")
    try {
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("File unreadable")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

// Goes through the file system rather than MediaStore: all files access lets a plain delete reach
// a save MediaStore attributes to an earlier install.
private fun pruneDownloads() {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    downloads.listFiles { file: File -> file.name.startsWith("${BACKUP_STEM}_") && file.name.endsWith(".json") }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .drop(KEEP_BACKUPS)
        .forEach { it.delete() }
}
