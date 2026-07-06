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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup and restore icon buttons plus the import confirmation dialog.
 * [importFromJson] runs on Dispatchers.IO and may throw to signal an invalid file.
 */
@Composable
fun BackupRestoreActions(
    backupFileName: String,
    importDialogText: String,
    createBackupJson: suspend () -> String,
    importFromJson: suspend (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val json = createBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sauvegarde créée", Toast.LENGTH_SHORT).show()
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
        scope.launch(Dispatchers.IO) {
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

    IconButton(onClick = { backupLauncher.launch(backupFileName) }) {
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
