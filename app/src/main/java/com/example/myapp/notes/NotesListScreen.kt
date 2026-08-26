package com.example.myapp.notes

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.myapp.AppSnackbar
import com.example.myapp.BackIconButton
import com.example.myapp.BackupRestoreActions
import com.example.myapp.BottomFadeOverlay
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.popBackStackOnce
import com.example.myapp.RecentSearchChips
import com.example.myapp.SearchHistory
import com.example.myapp.SearchSurface
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.formatDuration
import kotlinx.coroutines.launch

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

/** The next palette index in a fixed cycle, wrapping back to the first after the last. */
private fun nextNoteColor(current: Int): Int =
    if (current in 1 until NOTE_COLORS_LIGHT.size) current + 1 else 1

@Composable
fun NotesListScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }

    var notes by remember { mutableStateOf<List<Note>?>(null) }
    LaunchedEffect(dao) {
        // CLI escape hatch: unlock everything if the reset sentinel file is present
        NoteLock.applyResetSentinelIfPresent(context, dao)
    }
    LaunchedEffect(dao) {
        dao.observeNotes().collect { notes = it }
    }
    // The whole list, not just its size: a search looks through the trash too.
    var trashedNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    LaunchedEffect(dao) {
        dao.observeTrashedNotes().collect { trashedNotes = it }
    }
    val trashedCount = trashedNotes.size
    // Set while a "supprimer définitivement" confirmation is waiting on a trashed search result.
    var confirmDelete by remember { mutableStateOf<Note?>(null) }
    // Set while a "mettre à la corbeille" confirmation is waiting on a note of the list.
    var confirmTrash by remember { mutableStateOf<Note?>(null) }

    // Rewrites a note's lines and saves it, for the edits the pinned Todo widget makes in place.
    // Reads the note back inside the coroutine rather than transforming the copy captured at
    // composition time: two taps before the first recomposition lands would otherwise both rewrite
    // the same stale content, and the second one would silently undo the first.
    fun updateNoteLines(noteId: String, onSaved: () -> Unit = {}, transform: (MutableList<String>) -> Unit) {
        scope.launch {
            val current = dao.getNote(noteId) ?: return@launch
            val lines = current.content.split("\n").toMutableList()
            transform(lines)
            dao.upsertNote(
                current.copy(content = lines.joinToString("\n"), updatedAt = System.currentTimeMillis())
            )
            onSaved()
        }
    }

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }
    // Notes are filtered live as you type, so there is no "search" event to hang the history on.
    // The two moments a search is visibly over are instead what gets remembered: closing the bar,
    // and opening one of the results.
    fun recordSearch() {
        if (searchActive) SearchHistory.record(context, SearchSurface.NOTES, searchQuery)
    }
    fun closeSearch() {
        recordSearch()
        searchActive = false
        searchQuery = ""
    }
    BackHandler(enabled = searchActive) { closeSearch() }

    val searchTerms = remember(searchQuery) { searchTermsOf(searchQuery) }

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
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text("Rechercher dans les notes") },
                            singleLine = true,
                            leadingIcon = {
                                IconButton(onClick = { closeSearch() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer la recherche")
                                }
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Effacer")
                                    }
                                }
                            }
                        )
                    } else {
                        BackIconButton(onBack = { navController.popBackStackOnce() })
                        Text(
                            "Notes",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                if (!searchActive) {
                    Row {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Rechercher")
                        }
                        BackupRestoreActions(
                            backupFileName = "cognitools_notes.json",
                            importDialogText = "Les notes du fichier seront ajoutées. " +
                                    "Celles qui existent déjà seront remplacées par la version du fichier.",
                            createBackupJson = { notesToJsonString(dao.getNotes()) },
                            importFromJson = { json ->
                                val imported = Note.listFromJsonString(json)
                                if (imported.isEmpty()) throw IllegalArgumentException("Aucune note dans le fichier")
                                dao.upsertNotes(imported)
                            }
                        )
                    }
                }
            }

            if (searchActive && searchQuery.isBlank()) {
                RecentSearchChips(
                    surface = SearchSurface.NOTES,
                    onPick = { searchQuery = it },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val currentNotes = notes
                val displayedNotes = remember(currentNotes, searchTerms) {
                    currentNotes.orEmpty().filter { noteMatchesSearch(it, searchTerms) }
                }
                // Something deleted by mistake stays findable without opening the trash screen.
                val trashedMatches = remember(trashedNotes, searchTerms) {
                    if (searchTerms.isEmpty()) emptyList()
                    else trashedNotes.filter { noteMatchesSearch(it, searchTerms) }
                }
                val todoNote = remember(currentNotes) {
                    currentNotes?.firstOrNull {
                        noteTitleAndPreview(it).first.equals(TODO_LIST_TITLE, ignoreCase = true)
                    }
                }
                // Pinned above the grid while browsing; excluded from the grid itself to
                // avoid showing it twice. Hidden while searching so it doesn't get in the
                // way of the results, at which point it behaves like a regular note again.
                val pinnedTodo = if (searchQuery.isBlank()) todoNote else null
                val gridNotes = if (pinnedTodo != null) {
                    displayedNotes.filterNot { it.id == pinnedTodo.id }
                } else displayedNotes
                when {
                    currentNotes == null -> CircularProgressIndicator()
                    currentNotes.isEmpty() && trashedCount == 0 -> Text(
                        "Aucune note",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    gridNotes.isEmpty() && pinnedTodo == null && trashedMatches.isEmpty() && searchQuery.isNotBlank() -> Text(
                        "Aucun résultat",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            contentPadding = PaddingValues(bottom = 116.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalItemSpacing = 12.dp
                        ) {
                            if (pinnedTodo != null) {
                                item(key = "todo-widget", span = StaggeredGridItemSpan.FullLine) {
                                    TodoWidgetCard(
                                        note = pinnedTodo,
                                        onNavigate = { navController.navigate("note/${pinnedTodo.id}") },
                                        onToggleLine = { index ->
                                            updateNoteLines(pinnedTodo.id) { lines ->
                                                val line = lines[index]
                                                val prefix = if (line.isCheckedLine()) UNCHECKED_PREFIX else CHECKED_PREFIX
                                                lines[index] = prefix + line.checkboxText()
                                            }
                                        },
                                        onDeleteLine = { index ->
                                            updateNoteLines(pinnedTodo.id) { lines -> lines.removeAt(index) }
                                        },
                                        onAddItem = {
                                            // The new item goes at the end of the active block, above the
                                            // first separator; the editor then opens with the caret on it.
                                            val lines = pinnedTodo.content.split("\n")
                                            val insertAt = lines.indexOfFirst { it.isSeparatorLine() }
                                                .let { if (it == -1) lines.size else it }
                                            val offset = lines.take(insertAt).sumOf { it.length + 1 } +
                                                UNCHECKED_PREFIX.length
                                            updateNoteLines(
                                                noteId = pinnedTodo.id,
                                                onSaved = { navController.navigate("note/${pinnedTodo.id}?editAt=$offset") }
                                            ) { it.add(insertAt, UNCHECKED_PREFIX) }
                                        }
                                    )
                                }
                            }
                            items(items = gridNotes, key = { it.id }) { note ->
                                NoteItem(
                                    note = note,
                                    searchTerms = searchTerms,
                                    onNavigate = {
                                        recordSearch()
                                        val search = if (searchActive) Uri.encode(searchQuery) else ""
                                        navController.navigate("note/${note.id}?search=$search")
                                    },
                                    onRecolor = {
                                        // Keep updatedAt so recoloring does not reorder the list
                                        scope.launch { dao.upsertNote(note.copy(color = nextNoteColor(note.color))) }
                                    },
                                    onDelete = { confirmTrash = note }
                                )
                            }
                            if (trashedMatches.isNotEmpty()) {
                                item(key = "trash-heading", span = StaggeredGridItemSpan.FullLine) {
                                    Text(
                                        "Dans la corbeille",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                items(
                                    items = trashedMatches,
                                    key = { "trashed-${it.id}" },
                                    span = { StaggeredGridItemSpan.FullLine }
                                ) { note ->
                                    TrashedNoteCard(
                                        note = note,
                                        searchTerms = searchTerms,
                                        onRestore = {
                                            recordSearch()
                                            scope.launch {
                                                dao.restoreNote(note.id)
                                                AppSnackbar.show("Note restaurée")
                                            }
                                        },
                                        onDeleteForever = { confirmDelete = note }
                                    )
                                }
                            }
                            if (trashedCount > 0 && searchTerms.isEmpty()) {
                                item(key = "trash-entry", span = StaggeredGridItemSpan.FullLine) {
                                    TrashEntryRow(
                                        count = trashedCount,
                                        onClick = { navController.navigate("notes/trash") }
                                    )
                                }
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

    confirmTrash?.let { note ->
        val (title, _) = noteTitleAndPreview(note)
        ShowAlertDialog(
            onDismiss = { confirmTrash = null },
            title = "Mettre « $title » à la corbeille ?",
            onCancel = { confirmTrash = null },
            onConfirm = {
                confirmTrash = null
                scope.launch {
                    dao.trashNote(note.id)
                    AppSnackbar.show(
                        message = "Note dans la corbeille",
                        actionLabel = "Annuler",
                        onAction = { dao.restoreNote(note.id) }
                    )
                }
            }
        )
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
}

/** Way into the notes trash, after the last note and only while the trash holds something. */
@Composable
private fun TrashEntryRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color.Gray
        )
        Text(
            "Corbeille ($count)",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

// The colored, raised card both the grid items and the pinned Todo widget are drawn on.
@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = noteCardColor(note.color, isDarkMode)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            content = content
        )
    }
}

/** All a locked note ever shows on the list: a padlock and its title. */
@Composable
private fun LockedNoteTitle(note: Note) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            note.title.ifBlank { "Note verrouillée" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Pinned preview of the "Todo list" note: only the lines before its first separator, checkboxes toggle in place. */
@Composable
private fun TodoWidgetCard(
    note: Note,
    onNavigate: () -> Unit,
    onToggleLine: (Int) -> Unit,
    onDeleteLine: (Int) -> Unit,
    onAddItem: () -> Unit
) {
    val (title, _) = remember(note) { noteTitleAndPreview(note) }
    val contentLines = remember(note.content) { note.content.split("\n") }
    // When the title field is blank, noteTitleAndPreview falls back to the first
    // content line; skip that line below so it isn't shown twice.
    val titleLineIndex = remember(note, contentLines) {
        if (note.title.isBlank()) contentLines.indexOfFirst { it.isNotBlank() && !it.isSeparatorLine() }
        else -1
    }
    val bodyLines = remember(contentLines, titleLineIndex) {
        contentLines.withIndex()
            .takeWhile { !it.value.isSeparatorLine() }
            .filter { it.index != titleLineIndex && it.value.isNotBlank() }
    }

    NoteCard(note = note, onClick = onNavigate) {
        if (note.locked) {
            LockedNoteTitle(note)
            return@NoteCard
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        bodyLines.forEach { (index, line) ->
            if (line.isCheckboxLine()) {
                val checked = line.isCheckedLine()
                // Same as the editor: a wrapped item keeps its box and its delete
                // button on the first line.
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleLine(index) }
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleLine(index) })
                    Text(
                        line.checkboxText().formatInline(),
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (checked) Color.Gray else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 14.dp)
                    )
                    DeleteLineButton(
                        onClick = { onDeleteLine(index) },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        line.formatInline(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    )
                    DeleteLineButton(onClick = { onDeleteLine(index) })
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAddItem() }
        ) {
            IconButton(onClick = onAddItem, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Gray)
            }
            Text(
                "Nouvel élément",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

/** Small trailing "x" to remove a single line of the Todo widget, checked or not. */
@Composable
private fun DeleteLineButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Supprimer la ligne",
            modifier = Modifier.size(18.dp),
            tint = Color.Gray
        )
    }
}

@Composable
private fun NoteItem(
    note: Note,
    onNavigate: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit,
    searchTerms: List<String> = emptyList()
) {
    val (title, preview) = remember(note) { noteTitleAndPreview(note) }
    // While searching, the line that actually matched replaces the usual first lines, so a card
    // whose hit is buried deep in the note explains itself.
    val shownLine = remember(note, searchTerms) {
        matchingLineOf(note, searchTerms) ?: preview
    }
    val highlighted = highlightedSearchText(shownLine, searchTerms)

    NoteCard(note = note, onClick = onNavigate) {
        if (note.locked) {
            LockedNoteTitle(note)
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
                    highlighted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
            if (!note.locked) {
                Spacer(Modifier.size(4.dp))
                val clipboard = LocalClipboardManager.current
                val context = LocalContext.current
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(note.content))
                        if (note.title.trim().equals(INGREDIENTS_TITLE, ignoreCase = true)) {
                            Toast.makeText(context, "${(1..15).random()}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copier le contenu")
                }
            }
            Spacer(Modifier.size(4.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
            }
        }
    }
}
