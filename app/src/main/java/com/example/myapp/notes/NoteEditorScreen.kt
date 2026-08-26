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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.myapp.BackIconButton
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.plural
import kotlinx.coroutines.launch

private enum class AddItemTarget { INGREDIENT, COURSE }

/** The header's content derived flags, scanned once per content change. */
private data class ContentScans(
    val hasBlankEdgeLines: Boolean,
    val hasCheckboxLine: Boolean,
    val hasCheckedLine: Boolean
)

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

    val state = rememberNoteEditorState(noteId, initialEditOffset, dao)
    val textFieldState = state.textFieldState
    val titleFieldState = state.titleFieldState
    val isEditing = state.isEditing

    // Set when the note was opened from the notes search: its words stay painted while reading.
    val listSearchTerms = remember(searchQuery) { searchTermsOf(searchQuery) }
    // The note's own search bar, which takes over the highlighting while it is open.
    var searchOpen by remember { mutableStateOf(false) }
    var noteQuery by remember { mutableStateOf("") }
    var matchPos by remember { mutableStateOf(0) }
    // Bumped on every jump so asking for the same match twice still scrolls back to it.
    var focusNonce by remember { mutableStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }
    var titleFocused by remember { mutableStateOf(false) }
    var showCreatePin by remember { mutableStateOf(false) }
    var addItemTarget by remember { mutableStateOf<AddItemTarget?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val lineEdits = rememberNoteLineEdits(
        textFieldState = textFieldState,
        snackbar = snackbarHostState,
        scope = scope,
        saveContent = { state.saveContent(it) },
        isCoursesNote = {
            titleFieldState.text.toString().trim().equals(COURSES_TITLE, ignoreCase = true)
        }
    )

    val sync = rememberNoteSyncActions(
        noteId = state.id,
        dao = dao,
        snackbar = snackbarHostState,
        textFieldState = textFieldState,
        saveContent = { state.saveContent(it) }
    )

    BackHandler { state.goBack(onBack) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.pinNeeded) {
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
            // The three whole-content scans the header needs, one of which copies the entire note.
            // Kept out of the recompositions that aren't a content change (a selection, a drag,
            // the search bar opening).
            val contentScans = remember(content) {
                ContentScans(
                    hasBlankEdgeLines = content != content.trimBlankEdgeLines(),
                    hasCheckboxLine = content.hasCheckboxLine(),
                    hasCheckedLine = content.hasCheckedLine()
                )
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
                        locked = state.locked,
                        searchOpen = searchOpen,
                        canUndo = state.canUndo,
                        hasBlankEdgeLines = contentScans.hasBlankEdgeLines,
                        hasCheckboxLine = contentScans.hasCheckboxLine,
                        hasCheckedLine = contentScans.hasCheckedLine,
                        hasContent = content.isNotEmpty(),
                        isCoursesNote = isCoursesNote,
                        isIngredientsNote = isIngredientsNote,
                        isIngredientModelNote = isIngredientModelNote
                    ),
                    actions = NoteEditorBarActions(
                        onBack = { state.goBack(onBack) },
                        onUndo = { state.undo() },
                        onToggleSearch = {
                            searchOpen = !searchOpen
                            if (!searchOpen) noteQuery = ""
                        },
                        onToggleLock = {
                            if (state.locked) state.persistLock(false)
                            else scope.launch {
                                if (NoteLock.hasPin(context)) state.persistLock(true) else showCreatePin = true
                            }
                        },
                        onTrimBlankEdgeLines = { state.saveContent(content.trimBlankEdgeLines()) },
                        onSendCheckedToIngredients = { sync.sendCheckedToIngredients() },
                        onAddIngredient = { addItemTarget = AddItemTarget.INGREDIENT },
                        onAddCourseItem = { addItemTarget = AddItemTarget.COURSE },
                        onResortIngredients = { sync.resortIngredients() },
                        onResortCourses = { sync.resortCourses() },
                        onSetAllCheckboxes = { state.saveContent(setAllCheckboxes(content, it)) },
                        onRemoveChecked = {
                            val stripped = removeCheckedCheckboxes(content)
                            state.saveContent(if (isCoursesNote) dropEmptyCourseSections(stripped) else stripped)
                        },
                        onClearContent = { state.saveContent("") },
                        onToggleInlineMarker = { textFieldState.toggleInlineMarker(it) },
                        onToggleTitle = { textFieldState.toggleTitleLine() }
                    )
                )

                if (searchOpen && !isEditing && !titleFocused) {
                    NoteSearchBar(
                        query = noteQuery,
                        onQueryChange = { noteQuery = it },
                        matchCount = matchLines.size,
                        matchPos = matchPos,
                        focusRequester = searchFocusRequester,
                        onStep = { step ->
                            matchPos = (matchPos + step + matchLines.size) % matchLines.size
                            focusNonce++
                        }
                    )
                }

                if (!isEditing && !titleFocused && isCoursesNote) {
                    val itemCount = content.totalItemCount()
                    if (itemCount > 0) {
                        Text(
                            "${content.checkedItemCount()} / $itemCount article${plural(itemCount)}",
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
                            onEnterEditAt = { state.enterEditAt(it) },
                            onReorder = { state.saveContent(it) }
                        )
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        NoteEditorDialogs(
            state = state,
            sync = sync,
            showCreatePin = showCreatePin,
            addItemTarget = addItemTarget,
            onDismissCreatePin = { showCreatePin = false },
            onDismissAddItem = { addItemTarget = null },
            onBack = onBack
        )
    }
}

// The note's own search bar: the query, how many lines match, and the arrows stepping through them.
@Composable
private fun NoteSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    focusRequester: FocusRequester,
    onStep: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text("Rechercher dans la note") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Effacer")
                    }
                }
            }
        )
        if (query.isNotBlank()) {
            Text(
                if (matchCount == 0) "0" else "${matchPos + 1}/$matchCount",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = { onStep(-1) }, enabled = matchCount > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Résultat précédent")
            }
            IconButton(onClick = { onStep(1) }, enabled = matchCount > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Résultat suivant")
            }
        }
    }
}

// Everything the editor can put on top of itself: the two PIN dialogs, the ingredient reconcile
// run, and the prompt for a new item's name.
@Composable
private fun NoteEditorDialogs(
    state: NoteEditorState,
    sync: NoteSyncActions,
    showCreatePin: Boolean,
    addItemTarget: AddItemTarget?,
    onDismissCreatePin: () -> Unit,
    onDismissAddItem: () -> Unit,
    onBack: () -> Unit
) {
    if (state.pinNeeded) {
        PinDialog(
            purpose = PinPurpose.Enter,
            onDismiss = onBack,
            onSuccess = { state.pinEntered() }
        )
    }
    if (showCreatePin) {
        PinDialog(
            purpose = PinPurpose.Create,
            onDismiss = onDismissCreatePin,
            onSuccess = {
                onDismissCreatePin()
                state.persistLock(true)
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
                onDismissAddItem()
                if (target == AddItemTarget.COURSE) sync.addCourseItem(it) else sync.addIngredientDirectly(it)
            },
            onDismiss = onDismissAddItem
        )
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
