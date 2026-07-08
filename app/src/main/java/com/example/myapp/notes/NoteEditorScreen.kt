package com.example.myapp.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapp.flashcards.AppDatabase
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pressing Enter at the end of a checkbox line continues the list with a fresh
 * checkbox; on an empty checkbox line it removes the checkbox instead. Enter
 * within the prefix of a non empty checkbox line inserts an empty checkbox
 * above the item.
 */
private val autoContinueCheckboxTransformation = InputTransformation {
    val cursor = selection.start
    val oldText = originalText.toString()
    val typedNewline = length == oldText.length + 1 &&
        cursor > 0 && cursor <= length && charAt(cursor - 1) == '\n'
    if (!typedNewline) return@InputTransformation

    val oldCursor = cursor - 1
    val oldLineStart = if (oldCursor == 0) 0 else oldText.lastIndexOf('\n', oldCursor - 1) + 1
    val oldLineEnd = oldText.indexOf('\n', oldCursor).let { if (it == -1) oldText.length else it }
    val oldLine = oldText.substring(oldLineStart, oldLineEnd)
    if (oldLine.isCheckboxLine() &&
        oldCursor - oldLineStart <= UNCHECKED_PREFIX.length &&
        oldLine.checkboxText().isNotBlank()
    ) {
        replace(oldLineStart, oldLineStart, UNCHECKED_PREFIX + "\n")
        selection = TextRange(oldLineStart + UNCHECKED_PREFIX.length)
        return@InputTransformation
    }

    val newText = asCharSequence().toString()
    val prevLineStart = if (cursor == 1) 0 else newText.lastIndexOf('\n', cursor - 2) + 1
    val prevLine = newText.substring(prevLineStart, cursor - 1)
    if (!prevLine.isCheckboxLine()) return@InputTransformation

    if (prevLine.checkboxText().isBlank()) {
        // Empty checkbox line: Enter exits the list, dropping the empty item
        replace(prevLineStart, cursor, "")
        selection = TextRange(prevLineStart)
    } else {
        replace(cursor, cursor, UNCHECKED_PREFIX)
        selection = TextRange(cursor + UNCHECKED_PREFIX.length)
    }
}

// The title field is single line; strips any newline that sneaks in (e.g. from pasted text).
private val stripNewlinesTransformation = InputTransformation {
    var i = 0
    while (i < length) {
        if (charAt(i) == '\n') replace(i, i + 1, "") else i++
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
private fun slashQuery(state: TextFieldState): Pair<Int, String>? {
    if (!state.selection.collapsed) return null
    val cursor = state.selection.start
    val text = state.text.toString()
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
    val titleFieldState = remember { TextFieldState() }
    val textFieldState = remember { TextFieldState() }
    var noteColor by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    // A locked note stays gated until the PIN is entered (or it is a new note)
    var unlocked by remember { mutableStateOf(isNew) }
    var showCreatePin by remember { mutableStateOf(false) }
    // null until the note is loaded; guards the autosave against saving too early
    var lastSaved by remember { mutableStateOf<Pair<String, String>?>(if (isNew) "" to "" else null) }

    // Snapshot of the note as currently edited
    fun currentNote() = Note(
        id = id,
        title = titleFieldState.text.toString(),
        content = textFieldState.text.toString(),
        updatedAt = System.currentTimeMillis(),
        color = noteColor,
        locked = locked
    )

    LaunchedEffect(Unit) {
        if (!isNew) {
            val note = dao.getNote(noteId)
            val content = note?.content ?: ""
            titleFieldState.setTextAndPlaceCursorAtEnd(note?.title ?: "")
            noteColor = note?.color ?: 0
            locked = note?.locked ?: false
            unlocked = !(note?.locked ?: false)
            textFieldState.setTextAndPlaceCursorAtEnd(content)
            lastSaved = titleFieldState.text.toString() to content
        }
    }

    // Debounced autosave while typing
    LaunchedEffect(titleFieldState.text.toString(), textFieldState.text.toString()) {
        val current = titleFieldState.text.toString() to textFieldState.text.toString()
        if (lastSaved == null || current == lastSaved) return@LaunchedEffect
        delay(600)
        if (current.first.isNotBlank() || current.second.isNotBlank()) {
            dao.upsertNote(currentNote())
            lastSaved = current
        }
    }

    // Saves pending changes right away, e.g. when leaving the edit mode
    fun saveNow() {
        if (lastSaved == null) return
        val current = titleFieldState.text.toString() to textFieldState.text.toString()
        if (current == lastSaved) return
        if (current.first.isNotBlank() || current.second.isNotBlank()) {
            lastSaved = current
            val note = currentNote()
            scope.launch { dao.upsertNote(note) }
        }
    }

    fun finish() {
        scope.launch {
            if (lastSaved != null) {
                val current = titleFieldState.text.toString() to textFieldState.text.toString()
                if (current.first.isBlank() && current.second.isBlank()) {
                    dao.deleteNote(id)
                } else if (current != lastSaved) {
                    dao.upsertNote(currentNote())
                }
            }
            onBack()
        }
    }

    // Immediate save for changes made from the view mode (checkbox toggles, reorders)
    fun saveContent(newText: String) {
        textFieldState.edit { replace(0, length, newText) }
        lastSaved = titleFieldState.text.toString() to newText
        val note = currentNote()
        scope.launch { dao.upsertNote(note) }
    }

    // Persists a lock/unlock toggle right away. An empty new note has nothing to
    // save yet; the flag rides along on the next autosave once it has content.
    fun persistLock(newLocked: Boolean) {
        locked = newLocked
        if (lastSaved != null && (titleFieldState.text.isNotBlank() || textFieldState.text.isNotBlank())) {
            lastSaved = titleFieldState.text.toString() to textFieldState.text.toString()
            val note = currentNote()
            scope.launch { dao.upsertNote(note) }
        }
    }

    fun toggleLine(index: Int) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        val line = lines[index]
        lines[index] = if (line.isCheckedLine()) {
            UNCHECKED_PREFIX + line.checkboxText()
        } else {
            CHECKED_PREFIX + line.checkboxText()
        }
        saveContent(lines.joinToString("\n"))
    }

    val snackbarHostState = remember { SnackbarHostState() }

    fun deleteLine(index: Int) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        val removed = lines.removeAt(index)
        saveContent(lines.joinToString("\n"))
        scope.launch {
            // Replace any snackbar from a previous delete instead of queueing
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Élément supprimé",
                actionLabel = "Annuler",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val current = textFieldState.text.toString().split("\n").toMutableList()
                current.add(index.coerceAtMost(current.size), removed)
                saveContent(current.joinToString("\n"))
            }
        }
    }

    // Wraps a whole line's text in the marker (or removes it when already wrapped)
    fun toggleLineMarker(index: Int, marker: String) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        val line = lines[index]
        val m = marker.length
        lines[index] = if (line.length >= 2 * m && line.startsWith(marker) && line.endsWith(marker)) {
            line.substring(m, line.length - m)
        } else {
            marker + line + marker
        }
        saveContent(lines.joinToString("\n"))
    }

    // Wraps the selection in the marker (or removes it when already wrapped);
    // with no selection, inserts a marker pair and puts the cursor inside
    fun toggleInlineMarker(marker: String) {
        val text = textFieldState.text.toString()
        val start = textFieldState.selection.min
        val end = textFieldState.selection.max
        val m = marker.length
        textFieldState.edit {
            if (start >= m && end + m <= text.length &&
                text.startsWith(marker, start - m) && text.startsWith(marker, end)
            ) {
                replace(end, end + m, "")
                replace(start - m, start, "")
                selection = TextRange(start - m, end - m)
            } else if (start == end) {
                replace(start, start, marker + marker)
                selection = TextRange(start + m)
            } else {
                replace(end, end, marker)
                replace(start, start, marker)
                selection = TextRange(start + m, end + m)
            }
        }
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (locked && !unlocked) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            titleFieldState.text.toString().ifBlank { "Note verrouillée" },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
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
                    state = titleFieldState,
                    inputTransformation = stripNewlinesTransformation,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    decorator = { innerTextField ->
                        Box {
                            if (titleFieldState.text.isEmpty()) {
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
                IconButton(onClick = {
                    if (locked) persistLock(false)
                    else scope.launch {
                        if (NoteLock.hasPin(context)) persistLock(true) else showCreatePin = true
                    }
                }) {
                    Icon(
                        if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (locked) "Déverrouiller" else "Verrouiller"
                    )
                }
                if (isEditing) {
                    IconButton(onClick = { toggleInlineMarker("**") }) {
                        Icon(Icons.Default.FormatBold, contentDescription = "Gras")
                    }
                    IconButton(onClick = { toggleInlineMarker("*") }) {
                        Icon(Icons.Default.FormatItalic, contentDescription = "Italique")
                    }
                    IconButton(onClick = { toggleInlineMarker("__") }) {
                        Icon(Icons.Default.FormatUnderlined, contentDescription = "Souligné")
                    }
                }
                if (!isEditing && textFieldState.text.toString().hasCheckboxLine()) {
                    IconButton(onClick = { saveContent(setAllCheckboxes(textFieldState.text.toString(), true)) }) {
                        Icon(Icons.Default.CheckBox, contentDescription = "Tout cocher")
                    }
                    IconButton(onClick = { saveContent(setAllCheckboxes(textFieldState.text.toString(), false)) }) {
                        Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = "Tout décocher")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (isEditing) {
                // Typing "/" at the start of a line proposes commands as chips
                val slash = slashQuery(textFieldState)
                val slashMatches = if (slash == null) emptyList() else SLASH_COMMANDS.filter { cmd ->
                    cmd.keywords.any { it.startsWith(slash.second, ignoreCase = true) }
                }
                if (slashMatches.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        slashMatches.forEach { cmd ->
                            SuggestionChip(
                                onClick = {
                                    val lineStart = slash!!.first
                                    val cursor = textFieldState.selection.start
                                    textFieldState.edit {
                                        replace(lineStart, cursor, cmd.prefix)
                                        selection = TextRange(lineStart + cmd.prefix.length)
                                    }
                                },
                                label = { Text(cmd.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                BasicTextField(
                    state = textFieldState,
                    inputTransformation = autoContinueCheckboxTransformation,
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
                val lines = textFieldState.text.toString().split("\n")

                // Character offset of the start of each line, for double tap to edit
                val lineStarts = remember(textFieldState.text.toString()) {
                    val starts = IntArray(lines.size)
                    var acc = 0
                    lines.forEachIndexed { i, l ->
                        starts[i] = acc
                        acc += l.length + 1
                    }
                    starts
                }

                fun enterEditAt(offset: Int) {
                    textFieldState.edit {
                        selection = TextRange(offset.coerceIn(0, length))
                    }
                    isEditing = true
                }

                // Selected plain text line, showing the format/delete toolbar; -1 when none
                var selectedLine by remember { mutableStateOf(-1) }

                // Long press drag to reorder lines. While a drag is in progress,
                // displayOrder holds the permuted line indices; otherwise it is null.
                var displayOrder by remember { mutableStateOf<List<Int>?>(null) }
                var draggedLine by remember { mutableStateOf(-1) }
                var dragOffset by remember { mutableStateOf(0f) }
                val lineHeights = remember { mutableStateMapOf<Int, Int>() }

                fun endDrag(commit: Boolean) {
                    val order = displayOrder
                    if (commit && order != null) {
                        val current = textFieldState.text.toString().split("\n")
                        if (order.size == current.size) {
                            val newText = order.joinToString("\n") { current[it] }
                            if (newText != textFieldState.text.toString()) saveContent(newText)
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
                        .pointerInput(textFieldState.text.toString()) {
                            detectTapGestures(
                                onTap = { selectedLine = -1 },
                                onDoubleTap = { enterEditAt(textFieldState.text.length) }
                            )
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
                                                displayOrder = textFieldState.text.toString().split("\n").indices.toList()
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
                                            .pointerInput(textFieldState.text.toString()) {
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
                                            line.checkboxText().formatInline(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                                            color = if (checked) Color.Gray else MaterialTheme.colorScheme.onBackground,
                                            onTextLayout = { textLayout[0] = it },
                                            modifier = Modifier
                                                .weight(1f)
                                                .pointerInput(textFieldState.text.toString()) {
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
                                    val selected = lineIndex == selectedLine
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            (if (line.isEmpty()) " " else line).formatInline(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            onTextLayout = { textLayout[0] = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.surfaceVariant
                                                    else Color.Transparent
                                                )
                                                .padding(vertical = 4.dp)
                                                .pointerInput(textFieldState.text.toString()) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            selectedLine = if (selected) -1 else lineIndex
                                                        },
                                                        onDoubleTap = { pos ->
                                                            selectedLine = -1
                                                            val inLine = textLayout[0]?.getOffsetForPosition(pos) ?: line.length
                                                            enterEditAt(lineStarts[lineIndex] + inLine.coerceIn(0, line.length))
                                                        }
                                                    )
                                                }
                                        )
                                        if (selected) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                IconButton(
                                                    onClick = { toggleLineMarker(lineIndex, "**"); selectedLine = -1 },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(Icons.Default.FormatBold, contentDescription = "Gras", tint = Color.Gray)
                                                }
                                                IconButton(
                                                    onClick = { toggleLineMarker(lineIndex, "*"); selectedLine = -1 },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(Icons.Default.FormatItalic, contentDescription = "Italique", tint = Color.Gray)
                                                }
                                                IconButton(
                                                    onClick = { toggleLineMarker(lineIndex, "__"); selectedLine = -1 },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(Icons.Default.FormatUnderlined, contentDescription = "Souligné", tint = Color.Gray)
                                                }
                                                IconButton(
                                                    onClick = { deleteLine(lineIndex); selectedLine = -1 },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer la ligne", tint = Color.Gray)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (locked && !unlocked) {
            PinDialog(
                purpose = PinPurpose.Enter,
                onDismiss = { onBack() },
                onSuccess = { unlocked = true }
            )
        }
        if (showCreatePin) {
            PinDialog(
                purpose = PinPurpose.Create,
                onDismiss = { showCreatePin = false },
                onSuccess = {
                    showCreatePin = false
                    persistLock(true)
                }
            )
        }
    }
}
