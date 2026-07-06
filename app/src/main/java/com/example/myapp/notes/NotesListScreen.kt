package com.example.myapp.notes

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.myapp.BottomFadeOverlay
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatNoteDate(updatedAt: Long): String {
    val diff = System.currentTimeMillis() - updatedAt
    return if (diff < 60_000L) "à l'instant" else "il y a ${formatDuration(diff)}"
}

// Note card colors, one light and one dark variant per palette index (Note.color).
private val NOTE_COLORS_LIGHT = listOf(
    Color(0xFFF28B82), Color(0xFFFBBC04), Color(0xFFFFF475), Color(0xFFCCFF90),
    Color(0xFFA7FFEB), Color(0xFFCBF0F8), Color(0xFFD7AEFB), Color(0xFFFDCFE8)
)
private val NOTE_COLORS_DARK = listOf(
    Color(0xFF5C2B29), Color(0xFF614A19), Color(0xFF635D19), Color(0xFF345920),
    Color(0xFF16504B), Color(0xFF2D555E), Color(0xFF42275E), Color(0xFF5B2245)
)

private fun noteCardColor(index: Int, dark: Boolean): Color? =
    if (index in 1..NOTE_COLORS_LIGHT.size) {
        if (dark) NOTE_COLORS_DARK[index - 1] else NOTE_COLORS_LIGHT[index - 1]
    } else null

/** A random palette index, always different from the current one. */
private fun randomNoteColor(current: Int): Int =
    (1..NOTE_COLORS_LIGHT.size).filter { it != current }.random()

@Composable
fun NotesListScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }

    var notes by remember { mutableStateOf<List<Note>?>(null) }
    LaunchedEffect(dao) {
        dao.observeNotes().collect { notes = it }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val json = notesToJsonString(dao.getNotes())
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
                val imported = Note.listFromJsonString(json)
                if (imported.isEmpty()) throw IllegalArgumentException("Aucune note dans le fichier")
                dao.upsertNotes(imported)
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                    Text(
                        "Notes",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Row {
                    IconButton(onClick = {
                        backupLauncher.launch("cognitools_notes.json")
                    }) {
                        Icon(Icons.Default.Upload, contentDescription = "Sauvegarder")
                    }

                    IconButton(onClick = {
                        restoreLauncher.launch("application/json")
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Restaurer")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val currentNotes = notes
                when {
                    currentNotes == null -> CircularProgressIndicator()
                    currentNotes.isEmpty() -> Text(
                        "Aucune note",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            contentPadding = PaddingValues(bottom = 116.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalItemSpacing = 12.dp
                        ) {
                            items(items = currentNotes, key = { it.id }) { note ->
                                NoteItem(
                                    note = note,
                                    onNavigate = { navController.navigate("note/${note.id}") },
                                    onRecolor = {
                                        // Keep updatedAt so recoloring does not reorder the list
                                        scope.launch { dao.upsertNote(note.copy(color = randomNoteColor(note.color))) }
                                    },
                                    onDelete = { scope.launch { dao.deleteNote(note.id) } }
                                )
                            }
                        }

                        BottomFadeOverlay()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            MyButton(
                text = "Nouvelle note",
                modifier = Modifier.fillMaxWidth().height(100.dp),
                onClick = { navController.navigate("note/new") }
            )
        }
    }

    // Confirmation before importing a backup over the current data
    pendingImportUri?.let { uri ->
        ShowAlertDialog(
            onDismiss = { pendingImportUri = null },
            title = "Importer la sauvegarde ?",
            textContent = {
                Text(
                    "Les notes du fichier seront ajoutées. " +
                            "Celles qui existent déjà seront remplacées par la version du fichier."
                )
            },
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

@Composable
private fun NoteItem(
    note: Note,
    onNavigate: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val (title, preview) = remember(note) { noteTitleAndPreview(note) }
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = noteCardColor(note.color, isDarkMode)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate() },
        colors = if (cardColor != null) {
            CardDefaults.cardColors(
                containerColor = cardColor,
                contentColor = if (isDarkMode) Color(0xFFE8EAED) else Color(0xFF1F1F1F)
            )
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                formatNoteDate(note.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onRecolor,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Palette, contentDescription = "Couleur aléatoire")
                }
                Spacer(Modifier.size(4.dp))
                val clipboard = LocalClipboardManager.current
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(note.content)) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copier le contenu")
                }
                Spacer(Modifier.size(4.dp))
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                }
            }
        }
    }

    ShowAlertDialog(
        show = showDeleteDialog,
        onDismiss = { showDeleteDialog = false },
        title = "T'es sûr ??",
        onCancel = { showDeleteDialog = false },
        onConfirm = {
            onDelete()
            showDeleteDialog = false
        },
        cancelText = "Oula non merci",
        confirmText = "Oui t'inquiète"
    )
}
