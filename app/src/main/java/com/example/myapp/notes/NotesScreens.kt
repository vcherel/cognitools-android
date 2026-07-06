package com.example.myapp.notes

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.formatDuration
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val isDarkMode = LocalIsDarkMode.current

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
                        LazyColumn(contentPadding = PaddingValues(bottom = 116.dp)) {
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
    val interactionSource = remember { MutableInteractionSource() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val (title, preview) = remember(note) { noteTitleAndPreview(note) }
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = noteCardColor(note.color, isDarkMode)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple()
            ) { onNavigate() },
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
 * Adds or removes the separator prefix on the line the cursor is on.
 */
private fun toggleSeparatorPrefix(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start.coerceIn(0, text.length)
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    return if (line.isSeparatorLine()) {
        // A bare "---" has no trailing space: remove the whole line in that case
        val removed = if (line.startsWith(SEPARATOR_PREFIX)) SEPARATOR_PREFIX.length else line.length
        val newText = text.substring(0, lineStart) + text.substring(lineStart + removed)
        val newCursor = (cursor - removed).coerceIn(lineStart, newText.length)
        value.copy(text = newText, selection = TextRange(newCursor))
    } else {
        val newText = text.substring(0, lineStart) + SEPARATOR_PREFIX + text.substring(lineStart)
        value.copy(text = newText, selection = TextRange(cursor + SEPARATOR_PREFIX.length))
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

private class SlashCommand(val label: String, val keywords: List<String>, val prefix: String)

private val SLASH_COMMANDS = listOf(
    SlashCommand("Case à cocher", listOf("case", "checkbox", "todo"), UNCHECKED_PREFIX),
    SlashCommand("Séparateur", listOf("separateur", "séparateur", "ligne"), SEPARATOR_PREFIX)
)

/**
 * If the cursor sits in a "/commande" token at the start of its line, returns
 * the token start index and the text typed after the slash.
 */
private fun slashQuery(value: TextFieldValue): Pair<Int, String>? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.start
    val text = value.text
    if (cursor > text.length) return null
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    if (cursor <= lineStart || text.getOrNull(lineStart) != '/') return null
    val token = text.substring(lineStart + 1, cursor)
    if (' ' in token) return null
    return lineStart to token
}

@Composable
fun NoteEditorScreen(noteId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }
    val isNew = noteId == "new"
    val id = remember { if (isNew) UUID.randomUUID().toString() else noteId }

    var isEditing by remember { mutableStateOf(isNew) }
    var titleValue by remember { mutableStateOf("") }
    var textValue by remember { mutableStateOf(TextFieldValue("")) }
    var noteColor by remember { mutableStateOf(0) }
    // null until the note is loaded; guards the autosave against saving too early
    var lastSaved by remember { mutableStateOf<Pair<String, String>?>(if (isNew) "" to "" else null) }

    LaunchedEffect(Unit) {
        if (!isNew) {
            val note = dao.getNote(noteId)
            val content = note?.content ?: ""
            titleValue = note?.title ?: ""
            noteColor = note?.color ?: 0
            textValue = TextFieldValue(content, TextRange(content.length))
            lastSaved = titleValue to content
        }
    }

    // Debounced autosave while typing
    LaunchedEffect(titleValue, textValue.text) {
        val current = titleValue to textValue.text
        if (lastSaved == null || current == lastSaved) return@LaunchedEffect
        delay(600)
        if (current.first.isNotBlank() || current.second.isNotBlank()) {
            dao.upsertNote(
                Note(id = id, title = current.first, content = current.second, updatedAt = System.currentTimeMillis(), color = noteColor)
            )
            lastSaved = current
        }
    }

    // Saves pending changes right away, e.g. when leaving the edit mode
    fun saveNow() {
        if (lastSaved == null) return
        val current = titleValue to textValue.text
        if (current == lastSaved) return
        if (current.first.isNotBlank() || current.second.isNotBlank()) {
            lastSaved = current
            scope.launch {
                dao.upsertNote(
                    Note(id = id, title = current.first, content = current.second, updatedAt = System.currentTimeMillis(), color = noteColor)
                )
            }
        }
    }

    fun finish() {
        scope.launch {
            if (lastSaved != null) {
                val current = titleValue to textValue.text
                if (current.first.isBlank() && current.second.isBlank()) {
                    dao.deleteNote(id)
                } else if (current != lastSaved) {
                    dao.upsertNote(
                        Note(id = id, title = current.first, content = current.second, updatedAt = System.currentTimeMillis(), color = noteColor)
                    )
                }
            }
            onBack()
        }
    }

    // Immediate save for changes made from the view mode (checkbox toggles, reorders)
    fun saveContent(newText: String) {
        textValue = textValue.copy(text = newText)
        lastSaved = titleValue to newText
        scope.launch {
            dao.upsertNote(Note(id = id, title = titleValue, content = newText, updatedAt = System.currentTimeMillis(), color = noteColor))
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
        saveContent(lines.joinToString("\n"))
    }

    fun deleteLine(index: Int) {
        val lines = textValue.text.split("\n").toMutableList()
        lines.removeAt(index)
        saveContent(lines.joinToString("\n"))
    }

    // While editing, back validates the note and shows the view instead of leaving
    fun goBack() {
        if (isEditing) {
            saveNow()
            isEditing = false
        } else {
            finish()
        }
    }

    BackHandler { goBack() }

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
            IconButton(onClick = { goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            BasicTextField(
                value = titleValue,
                onValueChange = { titleValue = it.replace("\n", "") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                decorationBox = { innerTextField ->
                    Box {
                        if (titleValue.isEmpty()) {
                            Text(
                                "Note",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.Gray
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (isEditing) {
                IconButton(onClick = { textValue = toggleCheckboxPrefix(textValue) }) {
                    Icon(Icons.Default.CheckBox, contentDescription = "Case à cocher")
                }
                IconButton(onClick = { textValue = toggleSeparatorPrefix(textValue) }) {
                    Icon(Icons.Default.HorizontalRule, contentDescription = "Séparateur")
                }
                IconButton(onClick = {
                    saveNow()
                    isEditing = false
                }) {
                    Icon(Icons.Default.Done, contentDescription = "Terminé")
                }
            } else {
                if (textValue.text.hasCheckboxLine()) {
                    IconButton(onClick = { saveContent(setAllCheckboxes(textValue.text, true)) }) {
                        Icon(Icons.Default.CheckBox, contentDescription = "Tout cocher")
                    }
                    IconButton(onClick = { saveContent(setAllCheckboxes(textValue.text, false)) }) {
                        Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = "Tout décocher")
                    }
                }
                IconButton(onClick = { isEditing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Éditer")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (isEditing) {
            // Typing "/" at the start of a line proposes commands as chips
            val slash = slashQuery(textValue)
            val slashMatches = if (slash == null) emptyList() else SLASH_COMMANDS.filter { cmd ->
                cmd.keywords.any { it.startsWith(slash.second, ignoreCase = true) }
            }
            if (slashMatches.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slashMatches.forEach { cmd ->
                        SuggestionChip(
                            onClick = {
                                val lineStart = slash!!.first
                                val text = textValue.text
                                val cursor = textValue.selection.start
                                textValue = TextFieldValue(
                                    text = text.substring(0, lineStart) + cmd.prefix + text.substring(cursor),
                                    selection = TextRange(lineStart + cmd.prefix.length)
                                )
                            },
                            label = { Text(cmd.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            BasicTextField(
                value = textValue,
                onValueChange = { new -> textValue = autoContinueCheckbox(textValue, new) },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground)
            )
        } else {
            val lines = textValue.text.split("\n")

            // Character offset of the start of each line, for double tap to edit
            val lineStarts = remember(textValue.text) {
                val starts = IntArray(lines.size)
                var acc = 0
                lines.forEachIndexed { i, l ->
                    starts[i] = acc
                    acc += l.length + 1
                }
                starts
            }

            fun enterEditAt(offset: Int) {
                textValue = textValue.copy(
                    selection = TextRange(offset.coerceIn(0, textValue.text.length))
                )
                isEditing = true
            }

            // Long press drag to reorder lines. While a drag is in progress,
            // displayOrder holds the permuted line indices; otherwise it is null.
            var displayOrder by remember { mutableStateOf<List<Int>?>(null) }
            var draggedLine by remember { mutableStateOf(-1) }
            var dragOffset by remember { mutableStateOf(0f) }
            val lineHeights = remember { mutableStateMapOf<Int, Int>() }

            fun endDrag(commit: Boolean) {
                val order = displayOrder
                if (commit && order != null) {
                    val current = textValue.text.split("\n")
                    if (order.size == current.size) {
                        val newText = order.joinToString("\n") { current[it] }
                        if (newText != textValue.text) saveContent(newText)
                    }
                }
                displayOrder = null
                draggedLine = -1
                dragOffset = 0f
            }

            // Swaps the dragged line with its neighbours as the finger crosses them
            fun onDragMove(deltaY: Float) {
                val order = (displayOrder ?: return).toMutableList()
                var pos = order.indexOf(draggedLine)
                if (pos < 0) return
                var offset = dragOffset + deltaY
                var moved = false
                while (true) {
                    if (offset > 0) {
                        val next = order.getOrNull(pos + 1) ?: break
                        val h = lineHeights[next] ?: break
                        if (h <= 0 || offset <= h / 2f) break
                        order[pos] = next
                        order[pos + 1] = draggedLine
                        pos++
                        offset -= h
                    } else {
                        val prev = order.getOrNull(pos - 1) ?: break
                        val h = lineHeights[prev] ?: break
                        if (h <= 0 || -offset <= h / 2f) break
                        order[pos] = prev
                        order[pos - 1] = draggedLine
                        pos--
                        offset += h
                    }
                    moved = true
                }
                if (moved) displayOrder = order
                dragOffset = offset
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .pointerInput(textValue.text) {
                        detectTapGestures(onDoubleTap = { enterEditAt(textValue.text.length) })
                    }
            ) {
                (displayOrder ?: lines.indices.toList()).forEach { lineIndex ->
                    key(lineIndex) {
                        val line = lines[lineIndex]
                        val isDragged = lineIndex == draggedLine
                        // Text layout of the line, to map a double tap to a cursor position
                        val textLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragged) 1f else 0f)
                                .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) }
                                .onSizeChanged { lineHeights[lineIndex] = it.height }
                                .background(
                                    if (isDragged) MaterialTheme.colorScheme.surfaceVariant
                                    else Color.Transparent
                                )
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedLine = lineIndex
                                            dragOffset = 0f
                                            displayOrder = textValue.text.split("\n").indices.toList()
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            onDragMove(amount.y)
                                        },
                                        onDragEnd = { endDrag(commit = true) },
                                        onDragCancel = { endDrag(commit = false) }
                                    )
                                }
                        ) {
                            if (line.isSeparatorLine()) {
                                val name = line.separatorName()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .pointerInput(textValue.text) {
                                            detectTapGestures(onDoubleTap = {
                                                enterEditAt(lineStarts[lineIndex] + line.length)
                                            })
                                        }
                                ) {
                                    HorizontalDivider(modifier = Modifier.weight(1f))
                                    if (name.isNotEmpty()) {
                                        Text(
                                            name.uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.Gray,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                        HorizontalDivider(modifier = Modifier.weight(1f))
                                    }
                                    IconButton(
                                        onClick = { deleteLine(lineIndex) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Supprimer la ligne",
                                            tint = Color.Gray
                                        )
                                    }
                                }
                            } else if (line.isCheckboxLine()) {
                                val checked = line.isCheckedLine()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggleLine(lineIndex) }
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = { toggleLine(lineIndex) })
                                    Text(
                                        line.checkboxText(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                                        color = if (checked) Color.Gray else MaterialTheme.colorScheme.onBackground,
                                        onTextLayout = { textLayout[0] = it },
                                        modifier = Modifier
                                            .weight(1f)
                                            .pointerInput(textValue.text) {
                                                detectTapGestures(
                                                    onTap = { toggleLine(lineIndex) },
                                                    onDoubleTap = { pos ->
                                                        val inLine = textLayout[0]?.getOffsetForPosition(pos)
                                                            ?: line.checkboxText().length
                                                        enterEditAt(
                                                            lineStarts[lineIndex] + UNCHECKED_PREFIX.length +
                                                                inLine.coerceIn(0, line.checkboxText().length)
                                                        )
                                                    }
                                                )
                                            }
                                    )
                                    IconButton(
                                        onClick = { deleteLine(lineIndex) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Supprimer la ligne",
                                            tint = Color.Gray
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    if (line.isEmpty()) " " else line,
                                    style = MaterialTheme.typography.bodyLarge,
                                    onTextLayout = { textLayout[0] = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .pointerInput(textValue.text) {
                                            detectTapGestures(onDoubleTap = { pos ->
                                                val inLine = textLayout[0]?.getOffsetForPosition(pos) ?: line.length
                                                enterEditAt(lineStarts[lineIndex] + inLine.coerceIn(0, line.length))
                                            })
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
