package com.example.myapp.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.formatDuration
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun formatNoteDate(updatedAt: Long): String {
    val diff = System.currentTimeMillis() - updatedAt
    return if (diff < 60_000L) "à l'instant" else "il y a ${formatDuration(diff)}"
}

@Composable
fun NotesListScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }
    val isDarkMode = LocalIsDarkMode.current

    var notes by remember { mutableStateOf<List<Note>?>(null) }
    LaunchedEffect(dao) {
        dao.observeNotes().collect { notes = it }
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
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    "Notes",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 8.dp)
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
                when {
                    currentNotes == null -> CircularProgressIndicator()
                    currentNotes.isEmpty() -> Text(
                        "Aucune note",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(contentPadding = PaddingValues(bottom = 116.dp)) {
                            items(items = currentNotes, key = { it.id }) { note ->
                                NoteItem(
                                    note = note,
                                    onNavigate = { navController.navigate("note/${note.id}") },
                                    onDelete = { scope.launch { dao.deleteNote(note.id) } }
                                )
                            }
                        }

                        // Gradient overlay at the bottom, same as the flashcards lists
                        val color = if (isDarkMode) Color(0xFF000000) else Color(0xFFFEF7FF)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(150.dp)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, color),
                                        startY = 0f,
                                        endY = Float.POSITIVE_INFINITY
                                    )
                                )
                        )
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
}

@Composable
private fun NoteItem(
    note: Note,
    onNavigate: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val (title, preview) = remember(note.content) { noteTitleAndPreview(note.content) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple()
            ) { onNavigate() },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    formatNoteDate(note.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
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

/**
 * Adds or removes the checkbox prefix on the line the cursor is on.
 */
private fun toggleCheckboxPrefix(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start.coerceIn(0, text.length)
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    val rest = text.substring(lineStart)
    return if (rest.isCheckboxLine()) {
        val newText = text.substring(0, lineStart) + text.substring(lineStart + UNCHECKED_PREFIX.length)
        val newCursor = (cursor - UNCHECKED_PREFIX.length).coerceAtLeast(lineStart)
        value.copy(text = newText, selection = TextRange(newCursor.coerceAtMost(newText.length)))
    } else {
        val newText = text.substring(0, lineStart) + UNCHECKED_PREFIX + text.substring(lineStart)
        value.copy(text = newText, selection = TextRange(cursor + UNCHECKED_PREFIX.length))
    }
}

/**
 * Pressing Enter at the end of a checkbox line continues the list with a fresh
 * checkbox; on an empty checkbox line it removes the checkbox instead.
 */
private fun autoContinueCheckbox(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val cursor = new.selection.start
    val typedNewline = new.text.length == old.text.length + 1 &&
        cursor > 0 && cursor <= new.text.length && new.text[cursor - 1] == '\n'
    if (!typedNewline) return new

    val prevLineStart = if (cursor == 1) 0 else new.text.lastIndexOf('\n', cursor - 2) + 1
    val prevLine = new.text.substring(prevLineStart, cursor - 1)
    if (!prevLine.isCheckboxLine()) return new

    return if (prevLine.checkboxText().isBlank()) {
        // Empty checkbox line: Enter exits the list, dropping the empty item
        TextFieldValue(
            text = new.text.substring(0, prevLineStart) + new.text.substring(cursor),
            selection = TextRange(prevLineStart)
        )
    } else {
        TextFieldValue(
            text = new.text.substring(0, cursor) + UNCHECKED_PREFIX + new.text.substring(cursor),
            selection = TextRange(cursor + UNCHECKED_PREFIX.length)
        )
    }
}

@Composable
fun NoteEditorScreen(noteId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }
    val isNew = noteId == "new"
    val id = remember { if (isNew) UUID.randomUUID().toString() else noteId }

    var isEditing by remember { mutableStateOf(isNew) }
    var textValue by remember { mutableStateOf(TextFieldValue("")) }
    // null until the note is loaded; guards the autosave against saving too early
    var lastSavedText by remember { mutableStateOf(if (isNew) "" else null) }

    LaunchedEffect(Unit) {
        if (!isNew) {
            val note = dao.getNote(noteId)
            val content = note?.content ?: ""
            textValue = TextFieldValue(content, TextRange(content.length))
            lastSavedText = content
        }
    }

    // Debounced autosave while typing
    LaunchedEffect(textValue.text) {
        val text = textValue.text
        if (lastSavedText == null || text == lastSavedText) return@LaunchedEffect
        delay(600)
        if (text.isNotBlank()) {
            dao.upsertNote(Note(id = id, content = text, updatedAt = System.currentTimeMillis()))
            lastSavedText = text
        }
    }

    fun finish() {
        scope.launch {
            val text = textValue.text
            if (lastSavedText != null) {
                if (text.isBlank()) {
                    dao.deleteNote(id)
                } else if (text != lastSavedText) {
                    dao.upsertNote(Note(id = id, content = text, updatedAt = System.currentTimeMillis()))
                }
            }
            onBack()
        }
    }

    fun toggleLine(index: Int) {
        val lines = textValue.text.split("\n").toMutableList()
        val line = lines[index]
        lines[index] = if (line.isCheckedLine()) {
            UNCHECKED_PREFIX + line.checkboxText()
        } else {
            CHECKED_PREFIX + line.checkboxText()
        }
        val newText = lines.joinToString("\n")
        textValue = textValue.copy(text = newText)
        lastSavedText = newText
        scope.launch {
            dao.upsertNote(Note(id = id, content = newText, updatedAt = System.currentTimeMillis()))
        }
    }

    BackHandler { finish() }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }

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
            IconButton(onClick = { finish() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text(
                "Note",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 8.dp).weight(1f)
            )
            if (isEditing) {
                IconButton(onClick = { textValue = toggleCheckboxPrefix(textValue) }) {
                    Icon(Icons.Default.CheckBox, contentDescription = "Case à cocher")
                }
                IconButton(onClick = { isEditing = false }) {
                    Icon(Icons.Default.Done, contentDescription = "Terminé")
                }
            } else {
                IconButton(onClick = { isEditing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Éditer")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (isEditing) {
            BasicTextField(
                value = textValue,
                onValueChange = { new -> textValue = autoContinueCheckbox(textValue, new) },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground)
            )
        } else {
            val interactionSource = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { isEditing = true }
            ) {
                textValue.text.split("\n").forEachIndexed { index, line ->
                    if (line.isCheckboxLine()) {
                        val checked = line.isCheckedLine()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggleLine(index) }
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggleLine(index) })
                            Text(
                                line.checkboxText(),
                                style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (checked) Color.Gray else MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        Text(
                            if (line.isEmpty()) " " else line,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
