package com.example.myapp.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.launch

/**
 * The notes waiting in the trash. Each one can go back to the list or be dropped for good, and
 * anything left alone disappears [NOTES_TRASH_RETENTION_DAYS] days after it was deleted (the purge
 * runs at app start, see MyApplication).
 */
@Composable
fun NotesTrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }

    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    LaunchedEffect(dao) {
        dao.observeTrashedNotes().collect { notes = it }
    }
    // The note a "supprimer définitivement" confirmation is waiting on, or null when the pending
    // confirmation is for emptying the whole trash.
    var confirmDelete by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Corbeille",
            onBack = onBack,
            modifier = Modifier.padding(16.dp),
            titleStyle = MaterialTheme.typography.headlineSmall,
            titleSuffix = {
                if (notes.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirmEmpty = true }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Vider la corbeille")
                    }
                }
            }
        )

        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Corbeille vide", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        Text(
            "Supprimées définitivement après $NOTES_TRASH_RETENTION_DAYS jours.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(notes, key = { it.id }) { note ->
                TrashedNoteCard(
                    note = note,
                    onRestore = {
                        scope.launch {
                            dao.restoreNote(note.id)
                            AppSnackbar.show("Note restaurée")
                        }
                    },
                    onDeleteForever = { confirmDelete = note }
                )
            }
        }
    }

    confirmDelete?.let { note ->
        ShowAlertDialog(
            onDismiss = { confirmDelete = null },
            title = "Supprimer définitivement cette note ?",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch { dao.deleteNote(note.id) }
            }
        )
    }

    if (confirmEmpty) ShowAlertDialog(
        onDismiss = { confirmEmpty = false },
        title = "Vider la corbeille ?",
        onCancel = { confirmEmpty = false },
        onConfirm = {
            confirmEmpty = false
            scope.launch { dao.emptyNotesTrash() }
        }
    )
}

@Composable
private fun TrashedNoteCard(note: Note, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val (title, preview) = remember(note) { noteTitleAndPreview(note) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (note.locked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        note.title.ifBlank { "Note verrouillée" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Expire dans ${remainingDays(note.deletedAt)} j",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRestore, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Restore, contentDescription = "Restaurer")
                }
                Spacer(Modifier.size(4.dp))
                IconButton(onClick = onDeleteForever, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer définitivement")
                }
            }
        }
    }
}

/** Days left before the purge takes the note, at least 1. */
private fun remainingDays(deletedAt: Long): Int {
    val retentionMillis = NOTES_TRASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L
    val millisLeft = deletedAt + retentionMillis - System.currentTimeMillis()
    return ((millisLeft + 86_400_000L - 1) / 86_400_000L).toInt().coerceAtLeast(1)
}
