package com.example.myapp.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.myapp.BackIconButton
import com.example.myapp.flashcards.AppDatabase
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Whether the current edit session added a fake blank line at the top and/or the bottom of the
// content, to give room to type before the first line or after the last.
private data class EditPadding(val top: Boolean = false, val bottom: Boolean = false) {
    val any: Boolean get() = top || bottom
}

private data class NoteSnapshot(val title: String, val content: String)

private enum class AddItemTarget { INGREDIENT, COURSE }

@Composable
fun NoteEditorScreen(noteId: String, initialEditOffset: Int = -1, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }
    val isNew = noteId == "new"
    val id = remember { if (isNew) UUID.randomUUID().toString() else noteId }

    var isEditing by remember { mutableStateOf(isNew) }
    val titleFieldState = remember { TextFieldState() }
    val textFieldState = remember { TextFieldState() }
    var editPadding by remember { mutableStateOf(EditPadding()) }
    var noteColor by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    // A locked note stays gated until the PIN is entered (or it is a new note)
    var unlocked by remember { mutableStateOf(isNew) }
    var showCreatePin by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    var showFormatMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // null until the note is loaded; guards the autosave against saving too early
    var lastSaved by remember { mutableStateOf<NoteSnapshot?>(if (isNew) NoteSnapshot("", "") else null) }
    // Snapshots to step back through with the undo button
    val undoStack = remember { mutableStateListOf<NoteSnapshot>() }
    val snackbarHostState = remember { SnackbarHostState() }
    var addItemTarget by remember { mutableStateOf<AddItemTarget?>(null) }

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
            lastSaved = NoteSnapshot(titleFieldState.text.toString(), content)
            if (initialEditOffset >= 0) {
                textFieldState.edit { selection = TextRange(initialEditOffset.coerceIn(0, length)) }
                isEditing = true
            }
        }
    }

    // Pushes the last saved snapshot onto the undo stack before it gets replaced
    fun pushUndo() {
        val previous = lastSaved ?: return
        undoStack.add(previous)
        if (undoStack.size > 50) undoStack.removeAt(0)
    }

    // Debounced autosave while typing. Tracked via snapshotFlow rather than a
    // LaunchedEffect key so typing doesn't force this whole screen to recompose
    // just to keep the key up to date.
    LaunchedEffect(Unit) {
        snapshotFlow { NoteSnapshot(titleFieldState.text.toString(), textFieldState.text.toString()) }
            .distinctUntilChanged()
            .collectLatest { current ->
                if (lastSaved == null || current == lastSaved) return@collectLatest
                delay(600)
                if (current.title.isNotBlank() || current.content.isNotBlank()) {
                    pushUndo()
                    dao.upsertNote(currentNote())
                    lastSaved = current
                }
            }
    }

    // Saves pending changes right away, e.g. when leaving the edit mode
    fun saveNow() {
        if (lastSaved == null) return
        val current = NoteSnapshot(titleFieldState.text.toString(), textFieldState.text.toString())
        if (current == lastSaved) return
        if (current.title.isNotBlank() || current.content.isNotBlank()) {
            pushUndo()
            lastSaved = current
            val note = currentNote()
            scope.launch { dao.upsertNote(note) }
        }
    }

    // Steps back to the previous snapshot on the undo stack, one step per call
    fun performUndo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.size - 1)
        titleFieldState.edit { replace(0, length, previous.title) }
        textFieldState.edit { replace(0, length, previous.content) }
        lastSaved = previous
        scope.launch { dao.upsertNote(currentNote()) }
    }

    fun finish() {
        scope.launch {
            if (lastSaved != null) {
                val current = NoteSnapshot(titleFieldState.text.toString(), textFieldState.text.toString())
                if (current.title.isBlank() && current.content.isBlank()) {
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
        pushUndo()
        lastSaved = NoteSnapshot(titleFieldState.text.toString(), newText)
        val note = currentNote()
        scope.launch { dao.upsertNote(note) }
    }

    // Rewrites the note's lines in place: the single shape every per-line action shares.
    fun editLines(block: (MutableList<String>) -> Unit) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        block(lines)
        saveContent(lines.joinToString("\n"))
    }

    val sync = rememberNoteSyncActions(
        noteId = id,
        dao = dao,
        snackbar = snackbarHostState,
        textFieldState = textFieldState,
        saveContent = { saveContent(it) }
    )

    // Inserts a blank line above/below the content if the first/last line isn't
    // already blank, and shifts caretOffset to account for a line added above it
    fun padForEditing(caretOffset: Int): Int {
        val text = textFieldState.text.toString()
        editPadding = EditPadding()
        if (text.isEmpty()) return caretOffset
        val firstLineEnd = text.indexOf('\n').let { if (it == -1) text.length else it }
        val lastLineStart = text.lastIndexOf('\n') + 1
        val padding = EditPadding(top = firstLineEnd > 0, bottom = lastLineStart < text.length)
        if (!padding.any) return caretOffset
        var offset = caretOffset
        textFieldState.edit {
            if (padding.bottom) replace(text.length, text.length, "\n")
            if (padding.top) {
                replace(0, 0, "\n")
                offset += 1
            }
        }
        editPadding = padding
        // The padding isn't a real edit; keep lastSaved in sync so it doesn't
        // get autosaved or land on the undo stack on its own
        lastSaved?.let { lastSaved = it.copy(content = textFieldState.text.toString()) }
        return offset
    }

    // Removes the fake padding lines added by padForEditing, but only the ones
    // still empty; anything the user typed into them is kept
    fun stripEditPadding() {
        val padding = editPadding
        if (!padding.any) return
        var strippedBottom = false
        var strippedTop = false
        textFieldState.edit {
            if (padding.bottom && length > 0 && asCharSequence()[length - 1] == '\n') {
                replace(length - 1, length, "")
                strippedBottom = true
            }
            if (padding.top && length > 0 && asCharSequence()[0] == '\n') {
                replace(0, 1, "")
                strippedTop = true
            }
        }
        // Mirror the same removal on lastSaved so a padding-only round trip
        // (enter edit mode, leave without typing) doesn't look like an edit
        if (strippedBottom || strippedTop) {
            lastSaved?.let { saved ->
                var content = saved.content
                if (strippedBottom && content.endsWith("\n")) content = content.dropLast(1)
                if (strippedTop && content.startsWith("\n")) content = content.drop(1)
                lastSaved = saved.copy(content = content)
            }
        }
        editPadding = EditPadding()
    }

    // Persists a lock/unlock toggle right away. An empty new note has nothing to
    // save yet; the flag rides along on the next autosave once it has content.
    fun persistLock(newLocked: Boolean) {
        locked = newLocked
        if (lastSaved != null && (titleFieldState.text.isNotBlank() || textFieldState.text.isNotBlank())) {
            lastSaved = NoteSnapshot(titleFieldState.text.toString(), textFieldState.text.toString())
            val note = currentNote()
            scope.launch { dao.upsertNote(note) }
        }
    }

    // Bumps the day in a "Muscu (jour)" checkbox line two days forward, wrapping
    // across the week, so a tap after a session sets it to the next planned one
    fun advanceMuscuLineDay(index: Int) = editLines { lines ->
        val line = lines[index]
        if (!line.isCheckboxLine()) return@editLines
        val text = line.checkboxText()
        val match = muscuDayMatch(text) ?: return@editLines
        val dayGroup = match.groups[1]!!
        val dayIndex = frenchDays.indexOf(dayGroup.value.trim().lowercase())
        val newDay = frenchDays[(dayIndex + 2) % 7]
        val newText = text.substring(0, dayGroup.range.first) + newDay + text.substring(dayGroup.range.last + 1)
        lines[index] = line.checkboxPrefix() + newText
    }

    // Drops a checkbox line's trailing "(jour)"/"(date)" waiting suffix, once its day
    // or date has come and the item moves out of the waiting zone into the active list
    fun removeLineDateSuffix(index: Int) = editLines { lines ->
        val line = lines[index]
        if (!line.isCheckboxLine()) return@editLines
        lines[index] = line.checkboxPrefix() + line.checkboxText().withoutDateSuffix()
    }

    fun toggleLine(index: Int) = editLines { lines ->
        val line = lines[index]
        val prefix = if (line.isCheckedLine()) UNCHECKED_PREFIX else CHECKED_PREFIX
        lines[index] = prefix + line.checkboxText()
    }

    // Changes a checkbox line's "(N)" quantity by delta (floored at 1, "(1)" is dropped)
    fun changeLineQuantity(index: Int, delta: Int) = editLines { lines ->
        val line = lines[index]
        if (!line.isCheckboxLine()) return@editLines
        lines[index] = line.checkboxPrefix() + line.checkboxText().withQuantityDelta(delta)
    }

    fun deleteLine(index: Int) {
        var removed = ""
        editLines { lines -> removed = lines.removeAt(index) }
        scope.launch {
            // Replace any snackbar from a previous delete instead of queueing
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Élément supprimé",
                actionLabel = "Annuler",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                editLines { lines -> lines.add(index.coerceAtMost(lines.size), removed) }
            }
        }
    }

    // Wraps a whole line's text in the marker (or removes it when already wrapped)
    fun toggleLineMarker(index: Int, marker: String) {
        saveContent(textFieldState.text.toString().withLineMarkerToggled(index, marker))
    }

    // Adds/removes the "Resume" marker right after a category title, in the Claude note
    fun toggleResumeAfter(index: Int) = editLines { lines ->
        if (lines.getOrNull(index + 1) == RESUME_LINE) lines.removeAt(index + 1)
        else lines.add(index + 1, RESUME_LINE)
    }

    // Enters edit mode with the caret at the given content offset, padding the note
    // first so there's room to type before the first line or after the last.
    fun enterEditAt(offset: Int) {
        val padded = padForEditing(offset)
        textFieldState.edit { selection = TextRange(padded.coerceIn(0, length)) }
        isEditing = true
    }

    // While editing, back validates the note and shows the view instead of leaving
    fun goBack() {
        if (isEditing) {
            stripEditPadding()
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
            LockedNotePlaceholder(
                title = titleFieldState.text.toString(),
                onBack = onBack
            )
        } else {
            // Only read while not editing: every use below is gated on !isEditing, and reading
            // the field states here unconditionally would resubscribe this whole screen to them,
            // recomposing everything (top bar, menus...) on every keystroke while typing.
            val title = if (isEditing) "" else titleFieldState.text.toString().trim()
            val content = if (isEditing) "" else textFieldState.text.toString()
            val isCoursesNote = !isEditing && title.equals(COURSES_TITLE, ignoreCase = true)
            val isIngredientsNote = !isEditing && title.equals(INGREDIENTS_TITLE, ignoreCase = true)
            val isIngredientModelNote = !isEditing && title.equals(INGREDIENT_MODEL_TITLE, ignoreCase = true)

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
                    if (!titleFocused) {
                        BackIconButton(onBack = { goBack() })
                    }
                    BasicTextField(
                        state = titleFieldState,
                        inputTransformation = stripNewlinesTransformation,
                        // Wraps onto up to two lines rather than being clipped by the buttons;
                        // stripNewlinesTransformation still keeps it a single logical line.
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                            .onFocusChanged { titleFocused = it.isFocused },
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                        decorator = { innerTextField ->
                            Box {
                                if (titleFieldState.text.isEmpty()) {
                                    Text(
                                        "Note",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.Gray
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (!titleFocused) {
                        if (!isEditing) {
                            if (content != content.trimBlankEdgeLines()) {
                                IconButton(onClick = { saveContent(content.trimBlankEdgeLines()) }) {
                                    Icon(Icons.Default.VerticalAlignCenter, contentDescription = "Supprimer les lignes vides en haut/bas")
                                }
                            }
                            if (isCoursesNote && content.hasCheckedLine()) {
                                IconButton(onClick = { sync.sendCheckedToIngredients() }) {
                                    Icon(Icons.Default.Kitchen, contentDescription = "Ranger les articles cochés dans les Ingrédients")
                                }
                            }
                            if (isIngredientsNote) {
                                IconButton(onClick = { addItemTarget = AddItemTarget.INGREDIENT }) {
                                    Icon(Icons.Default.Add, contentDescription = "Ajouter un ingrédient")
                                }
                            }
                            if (isIngredientModelNote) {
                                IconButton(onClick = { sync.resortIngredients() }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Réordonner les ingrédients présents")
                                }
                            }
                            if (isCoursesNote) {
                                IconButton(onClick = { addItemTarget = AddItemTarget.COURSE }) {
                                    Icon(Icons.Default.Add, contentDescription = "Ajouter un article")
                                }
                            }
                        }
                        IconButton(onClick = { performUndo() }, enabled = undoStack.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Annuler")
                        }
                        if (isEditing) {
                            Box {
                                IconButton(onClick = { showFormatMenu = true }) {
                                    Icon(Icons.Default.FormatBold, contentDescription = "Mise en forme")
                                }
                                DropdownMenu(expanded = showFormatMenu, onDismissRequest = { showFormatMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Gras") },
                                        leadingIcon = { Icon(Icons.Default.FormatBold, contentDescription = null) },
                                        onClick = { textFieldState.toggleInlineMarker("**") }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Italique") },
                                        leadingIcon = { Icon(Icons.Default.FormatItalic, contentDescription = null) },
                                        onClick = { textFieldState.toggleInlineMarker("*") }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Souligné") },
                                        leadingIcon = { Icon(Icons.Default.FormatUnderlined, contentDescription = null) },
                                        onClick = { textFieldState.toggleInlineMarker("__") }
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options")
                            }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (locked) "Déverrouiller" else "Verrouiller") },
                                    leadingIcon = {
                                        Icon(
                                            if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        if (locked) persistLock(false)
                                        else scope.launch {
                                            if (NoteLock.hasPin(context)) persistLock(true) else showCreatePin = true
                                        }
                                    }
                                )
                                if (!isEditing) {
                                    if (content.hasCheckboxLine()) {
                                        DropdownMenuItem(
                                            text = { Text("Tout cocher") },
                                            leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                saveContent(setAllCheckboxes(content, true))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Tout décocher") },
                                            leadingIcon = { Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                saveContent(setAllCheckboxes(content, false))
                                            }
                                        )
                                        if (content.hasCheckedLine()) {
                                            DropdownMenuItem(
                                                text = { Text("Supprimer les cochés") },
                                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                                onClick = {
                                                    showMoreMenu = false
                                                    saveContent(removeCheckedCheckboxes(content))
                                                }
                                            )
                                        }
                                    }
                                    if (isCoursesNote) {
                                        DropdownMenuItem(
                                            text = { Text("Mettre à jour selon le modèle") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                sync.resortCourses()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isEditing && !titleFocused && isCoursesNote) {
                    val itemCount = content.uncheckedItemCount()
                    if (itemCount > 0) {
                        Text(
                            "$itemCount article${if (itemCount > 1) "s" else ""} à acheter",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
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
                                            replace(lineStart, cursor, cmd.prefix + cmd.suffix)
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
                    NoteViewMode(
                        textFieldState = textFieldState,
                        title = title,
                        actions = NoteLineActions(
                            onToggleLine = { toggleLine(it) },
                            onDeleteLine = { deleteLine(it) },
                            onMoveToCourses = { sync.moveLineToCourses(it) },
                            onChangeQuantity = { index, delta -> changeLineQuantity(index, delta) },
                            onAdvanceMuscu = { advanceMuscuLineDay(it) },
                            onRemoveDateSuffix = { removeLineDateSuffix(it) },
                            onToggleLineMarker = { index, marker -> toggleLineMarker(index, marker) },
                            onToggleResume = { toggleResumeAfter(it) },
                            onEnterEditAt = { enterEditAt(it) },
                            onReorder = { saveContent(it) }
                        )
                    )
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

        sync.batch?.let { batch ->
            batch.pending.firstOrNull()?.let { current ->
                IngredientReconcileDialog(
                    itemName = current.name,
                    groups = batch.groups,
                    groupLabels = batch.groupNames,
                    allowNewGroup = batch.kind == SyncKind.INGREDIENT,
                    onAddNew = { sync.reconcileAddNew(it) },
                    onMapExisting = { sync.reconcileMapExisting(it) },
                    onSkip = { sync.reconcileSkip() },
                    onDismiss = { sync.dismissBatch() }
                )
            }
        }

        addItemTarget?.let { target ->
            AddIngredientNameDialog(
                title = if (target == AddItemTarget.COURSE) "Ajouter un article" else "Ajouter un ingrédient",
                onConfirm = {
                    addItemTarget = null
                    if (target == AddItemTarget.COURSE) sync.addCourseItem(it) else sync.addIngredientDirectly(it)
                },
                onDismiss = { addItemTarget = null }
            )
        }
    }
}

// What a locked note shows until the PIN dialog above it is satisfied.
@Composable
private fun LockedNotePlaceholder(title: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            BackIconButton(onBack = onBack)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    title.ifBlank { "Note verrouillée" },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
