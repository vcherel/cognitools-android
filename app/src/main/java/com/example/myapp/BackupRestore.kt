package com.example.myapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.notes.notesToJsonString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Off the screen's scope: leaving the screen mid-write used to cancel the job and leave a
// truncated backup, or an import applied halfway.
private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val KEEP_BACKUPS = 3
private const val DAY_MS = 24 * 60 * 60 * 1000L

enum class BackupKind(val stem: String) {
    NOTES("cognitools_notes"),
    FLASHCARDS("cognitools_flashcards");

    suspend fun createJson(context: Context): String = when (this) {
        NOTES -> notesToJsonString(AppDatabase.get(context).noteDao().getNotes())
        FLASHCARDS -> context.flashcardRepository.createBackupJson()
    }
}

private val Context.backupDataStore by preferencesDataStore("backup_prefs")

private fun cloudKey(kind: BackupKind) = longPreferencesKey("cloud_${kind.stem}")

/** When Yahoo last accepted a backup of every kind, so the oldest of them; 0 if one never reached it. */
private fun lastCloudBackup(context: Context): Flow<Long> = context.backupDataStore.data.map { prefs ->
    BackupKind.entries.minOf { prefs[cloudKey(it)] ?: 0L }
}

/**
 * Writes each kind to Téléchargements and uploads it to Yahoo, keeping the [KEEP_BACKUPS] newest
 * of each kind in both places, then says in a toast where it landed.
 */
fun saveBackups(context: Context, kinds: List<BackupKind>): Job = backupScope.launch {
    val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    var localOk = true
    var cloudError: Throwable? = null
    for (kind in kinds) {
        val fileName = "${kind.stem}_$date.json"
        val json = runCatching { kind.createJson(context) }.getOrElse {
            localOk = false
            cloudError = cloudError ?: it
            continue
        }
        runCatching {
            writeToDownloads(context, fileName, json)
            pruneDownloads(kind.stem)
        }.onFailure { localOk = false }
        runCatching {
            context.mailRepository.uploadBackup(kind.stem, fileName, json, KEEP_BACKUPS)
            context.backupDataStore.edit { it[cloudKey(kind)] = System.currentTimeMillis() }
        }.onFailure { cloudError = cloudError ?: it }
    }
    val error = cloudError
    val message = when {
        localOk && error == null -> "Sauvegardé sur le téléphone et sur Yahoo"
        localOk -> "Sauvegardé sur le téléphone, pas sur Yahoo : ${userMessage(error!!)}"
        error == null -> "Sauvegardé sur Yahoo, pas sur le téléphone"
        else -> "Erreur de sauvegarde : ${userMessage(error)}"
    }
    withContext(Dispatchers.Main) {
        Toast.makeText(context, message, if (localOk && error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }
}

/**
 * The menu's save button: saves every kind in one tap. Its icon tells how long ago Yahoo last got
 * a copy: a done cloud under 3 days, then an upload cloud in orange, in red from 7 days.
 */
@Composable
fun BackupMenuButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lastBackup by remember { lastCloudBackup(context) }.collectAsState(initial = null)
    var saving by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            if (!saving) scope.launch {
                saving = true
                saveBackups(context, BackupKind.entries).join()
                saving = false
            }
        },
        modifier = modifier
    ) {
        val age = lastBackup?.let { System.currentTimeMillis() - it }
        when {
            saving -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            age == null -> Unit
            age < 3 * DAY_MS -> Icon(Icons.Default.CloudDone, contentDescription = "Sauvegarder")
            else -> Icon(
                Icons.Default.CloudUpload,
                contentDescription = "Sauvegarder",
                tint = if (age < 7 * DAY_MS) Color(0xFFFF9800) else Color(0xFFE53935)
            )
        }
    }
}

/**
 * Backup and restore icon buttons plus the import confirmation dialog.
 * [importFromJson] runs on Dispatchers.IO and may throw to signal an invalid file.
 */
@Composable
fun BackupRestoreActions(
    kind: BackupKind,
    importDialogText: String,
    importFromJson: suspend (String) -> Unit
) {
    val context = LocalContext.current.applicationContext

    // Imports are confirmed by dialog first: the picked file waits here until then.
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    fun importBackup(uri: Uri) {
        backupScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.use { it.bufferedReader().readText() } ?: return@launch
                importFromJson(json)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Importation réussie", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erreur d'importation", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    IconButton(onClick = { saveBackups(context, listOf(kind)) }) {
        Icon(Icons.Default.Upload, contentDescription = "Sauvegarder")
    }

    IconButton(onClick = { restoreLauncher.launch("application/json") }) {
        Icon(Icons.Default.Download, contentDescription = "Restaurer")
    }

    pendingImportUri?.let { uri ->
        ShowAlertDialog(
            onDismiss = { pendingImportUri = null },
            title = "Importer la sauvegarde ?",
            textContent = { Text(importDialogText) },
            confirmText = "Importer",
            cancelText = "Annuler",
            onConfirm = {
                importBackup(uri)
                pendingImportUri = null
            },
            onCancel = { pendingImportUri = null }
        )
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
        ?: error("Fichier inaccessible")
    try {
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("Fichier inaccessible")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

// Goes through the file system rather than MediaStore: the saves made earlier through the system
// picker belong to another app, and all files access lets a plain delete reach them.
private fun pruneDownloads(stem: String) {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    downloads.listFiles { file: File -> file.name.startsWith("${stem}_") && file.name.endsWith(".json") }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .drop(KEEP_BACKUPS)
        .forEach { it.delete() }
}
