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
import androidx.compose.runtime.collectAsState
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
import com.example.myapp.LightIconButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.daysLeftUntil
import com.example.myapp.notesLists
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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

    val notes by context.notesLists.trashed.collectAsState()
    // The note a "supprimer définitivement" confirmation is waiting on, or null when the pending
    // confirmation is for emptying the whole trash.
    var confirmDelete by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Trash",
            onBack = onBack,
            modifier = Modifier.padding(16.dp),
            titleStyle = MaterialTheme.typography.headlineSmall,
            titleSuffix = {
                if (notes.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirmEmpty = true }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Empty the trash")
                    }
                }
            }
        )

        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        Text(
            "Deleted for good after $NOTES_TRASH_RETENTION_DAYS days.",
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
                            AppSnackbar.show("Note restored")
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
            title = "Delete this note for good?",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch { dao.deleteNote(note.id) }
            }
        )
    }

    if (confirmEmpty) ShowAlertDialog(
        onDismiss = { confirmEmpty = false },
        title = "Empty the trash?",
        onCancel = { confirmEmpty = false },
        onConfirm = {
            confirmEmpty = false
            scope.launch { dao.emptyNotesTrash() }
        }
    )
}

/** Not private: the notes list reuses this for the trashed notes a search turned up. */
@Composable
internal fun TrashedNoteCard(
    note: Note,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    searchTerms: List<String> = emptyList()
) {
    val (title, preview) = remember(note) { noteTitleAndPreview(note) }
    val shownLine = remember(note, searchTerms) { matchingLineOf(note, searchTerms) ?: preview }

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
                        note.title.ifBlank { "Locked note" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    highlightedSearchText(title, searchTerms),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (shownLine.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        highlightedSearchText(shownLine, searchTerms),
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
                    "Expires in ${remainingDays(note.deletedAt)} d",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                LightIconButton(Icons.Default.Restore, "Restore", onClick = onRestore)
                Spacer(Modifier.size(4.dp))
                LightIconButton(Icons.Default.Delete, "Delete for good", onClick = onDeleteForever)
            }
        }
    }
}

/** Days left before the purge takes the note. */
private fun remainingDays(deletedAt: Long): Int =
    daysLeftUntil(deletedAt + TimeUnit.DAYS.toMillis(NOTES_TRASH_RETENTION_DAYS.toLong()))
