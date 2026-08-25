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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
fun NoteEditorScreen(
    noteId: String,
    initialEditOffset: Int = -1,
    searchQuery: String = "",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).noteDao() }
    val isNew = noteId == "new"
    val id = remember { if (isNew) UUID.randomUUID().toString() else noteId }

    var isEditing by remember { mutableStateOf(isNew) }
    // Set when the note was opened from the notes search: its words stay painted while reading.
    val listSearchTerms = remember(searchQuery) { searchTermsOf(searchQuery) }
    // The note's own search bar, which takes over the highlighting while it is open.
    var searchOpen by remember { mutableStateOf(false) }
    var noteQuery by remember { mutableStateOf("") }
    var matchPos by remember { mutableStateOf(0) }
    // Bumped on every jump so asking for the same match twice still scrolls back to it.
    var focusNonce by remember { mutableStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }
    val titleFieldState = remember { TextFieldState() }
    val textFieldState = remember { TextFieldState() }
    var editPadding by remember { mutableStateOf(EditPadding()) }
    var noteColor by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    // A locked note stays gated until the PIN is entered (or it is a new note)
    var unlocked by remember { mutableStateOf(isNew) }
    var showCreatePin by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
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

    val lineEdits = rememberNoteLineEdits(
        textFieldState = textFieldState,
        snackbar = snackbarHostState,
        scope = scope,
        saveContent = { saveContent(it) },
        isCoursesNote = {
            titleFieldState.text.toString().trim().equals(COURSES_TITLE, ignoreCase = true)
        }
    )

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

            val searchTerms = if (searchOpen) remember(noteQuery) { searchTermsOf(noteQuery) } else listSearchTerms
            // The lines holding any of the searched words, in order: what the arrows step through.
            val matchLines = remember(content, searchTerms) {
                if (searchTerms.isEmpty()) emptyList()
                else content.split("\n").mapIndexedNotNull { index, line ->
                    val normalized = normalizeForSearch(line)
                    index.takeIf { searchTerms.any { term -> normalized.contains(term) } }
                }
            }
            LaunchedEffect(matchLines) {
                matchPos = 0
                if (matchLines.isNotEmpty()) focusNonce++
            }
            LaunchedEffect(searchOpen) {
                if (searchOpen) searchFocusRequester.requestFocus()
            }
            BackHandler(enabled = searchOpen) { searchOpen = false; noteQuery = "" }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(16.dp))

                NoteEditorTopBar(
                    titleFieldState = titleFieldState,
                    titleFocused = titleFocused,
                    onTitleFocusChanged = { titleFocused = it },
                    state = NoteEditorBarState(
                        isEditing = isEditing,
                        locked = locked,
                        searchOpen = searchOpen,
                        canUndo = undoStack.isNotEmpty(),
                        hasBlankEdgeLines = content != content.trimBlankEdgeLines(),
                        hasCheckboxLine = content.hasCheckboxLine(),
                        hasCheckedLine = content.hasCheckedLine(),
                        hasContent = content.isNotEmpty(),
                        isCoursesNote = isCoursesNote,
                        isIngredientsNote = isIngredientsNote,
                        isIngredientModelNote = isIngredientModelNote
                    ),
                    actions = NoteEditorBarActions(
                        onBack = { goBack() },
                        onUndo = { performUndo() },
                        onToggleSearch = {
                            searchOpen = !searchOpen
                            if (!searchOpen) noteQuery = ""
                        },
                        onToggleLock = {
                            if (locked) persistLock(false)
                            else scope.launch {
                                if (NoteLock.hasPin(context)) persistLock(true) else showCreatePin = true
                            }
                        },
                        onTrimBlankEdgeLines = { saveContent(content.trimBlankEdgeLines()) },
                        onSendCheckedToIngredients = { sync.sendCheckedToIngredients() },
                        onAddIngredient = { addItemTarget = AddItemTarget.INGREDIENT },
                        onAddCourseItem = { addItemTarget = AddItemTarget.COURSE },
                        onResortIngredients = { sync.resortIngredients() },
                        onResortCourses = { sync.resortCourses() },
                        onSetAllCheckboxes = { saveContent(setAllCheckboxes(content, it)) },
                        onRemoveChecked = {
                            val stripped = removeCheckedCheckboxes(content)
                            saveContent(if (isCoursesNote) dropEmptyCourseSections(stripped) else stripped)
                        },
                        onClearContent = { saveContent("") },
                        onToggleInlineMarker = { textFieldState.toggleInlineMarker(it) },
                        onToggleTitle = { textFieldState.toggleTitleLine() }
                    )
                )

                if (searchOpen && !isEditing && !titleFocused) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = noteQuery,
                            onValueChange = { noteQuery = it },
                            modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                            placeholder = { Text("Rechercher dans la note") },
                            singleLine = true,
                            trailingIcon = {
                                if (noteQuery.isNotEmpty()) {
                                    IconButton(onClick = { noteQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Effacer")
                                    }
                                }
                            }
                        )
                        if (noteQuery.isNotBlank()) {
                            Text(
                                if (matchLines.isEmpty()) "0" else "${matchPos + 1}/${matchLines.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = {
                                    matchPos = (matchPos - 1 + matchLines.size) % matchLines.size
                                    focusNonce++
                                },
                                enabled = matchLines.isNotEmpty()
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Résultat précédent")
                            }
                            IconButton(
                                onClick = {
                                    matchPos = (matchPos + 1) % matchLines.size
                                    focusNonce++
                                },
                                enabled = matchLines.isNotEmpty()
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Résultat suivant")
                            }
                        }
                    }
                }

                if (!isEditing && !titleFocused && isCoursesNote) {
                    val itemCount = content.totalItemCount()
                    if (itemCount > 0) {
                        Text(
                            "${content.checkedItemCount()} / $itemCount article${if (itemCount > 1) "s" else ""}",
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
                        searchTerms = searchTerms,
                        focusedLine = if (searchOpen) matchLines.getOrNull(matchPos) ?: -1 else -1,
                        focusNonce = focusNonce,
                        actions = NoteLineActions(
                            onToggleLine = lineEdits::toggleLine,
                            onDeleteLine = lineEdits::deleteLine,
                            onMoveToCourses = { sync.moveLineToCourses(it) },
                            onChangeQuantity = lineEdits::changeQuantity,
                            onAdvanceMuscu = lineEdits::advanceMuscuDay,
                            onRemoveDateSuffix = lineEdits::removeDateSuffix,
                            onToggleLineMarker = lineEdits::toggleLineMarker,
                            onToggleTitleLine = lineEdits::toggleTitleLine,
                            onToggleResume = lineEdits::toggleResumeAfter,
                            onToggleEnhance = lineEdits::toggleEnhanceAtEnd,
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
                    onAddNewGroup = if (batch.kind == SyncKind.COURSE) {
                        { name, beforeIndex -> sync.reconcileAddNewCourseGroup(name, beforeIndex) }
                    } else null,
                    onAddNew = { sync.reconcileAddNew(it) },
                    onMapExisting = { sync.reconcileMapExisting(it) },
                    onSkip = { sync.reconcileSkip() },
                    skipLabel = if (current.sourceLine != null) "Supprimer" else "Ignorer",
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
