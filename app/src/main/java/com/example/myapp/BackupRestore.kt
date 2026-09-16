package com.example.myapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Off the screen's scope: leaving the screen mid-write used to cancel the job and leave a
// truncated backup, or an import applied halfway.
private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Backup and restore icon buttons plus the import confirmation dialog.
 * [importFromJson] runs on Dispatchers.IO and may throw to signal an invalid file.
 */
@Composable
fun BackupRestoreActions(
    /** Stem of the suggested file name; the date and the .json extension are appended. */
    backupFileName: String,
    importDialogText: String,
    createBackupJson: suspend () -> String,
    importFromJson: suspend (String) -> Unit
) {
    val context = LocalContext.current.applicationContext

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupScope.launch {
                val done = runCatching {
                    val json = createBackupJson()
                    // "wt": a plain "w" is not truncated by every provider (Downloads, Drive), and
                    // a shorter backup written over a longer one then keeps the old file's tail.
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: error("Fichier inaccessible")
                }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (done) "Sauvegarde créée" else "Erreur de sauvegarde", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

    IconButton(onClick = {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        backupLauncher.launch("${backupFileName}_$date.json")
    }) {
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
