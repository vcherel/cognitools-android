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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.myapp.flashcards.AppDatabase
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    // Whether the current edit session inserted a fake blank line at the top/bottom
    // of the content, to give room to type before the first line or after the last
    var addedTopPad by remember { mutableStateOf(false) }
    var addedBottomPad by remember { mutableStateOf(false) }
    var noteColor by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    // A locked note stays gated until the PIN is entered (or it is a new note)
    var unlocked by remember { mutableStateOf(isNew) }
    var showCreatePin by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    var showFormatMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // null until the note is loaded; guards the autosave against saving too early
    var lastSaved by remember { mutableStateOf<Pair<String, String>?>(if (isNew) "" to "" else null) }
    // Snapshots of (title, content) to step back through with the undo button
    val undoStack = remember { mutableStateListOf<Pair<String, String>>() }

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
        snapshotFlow { titleFieldState.text.toString() to textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { current ->
                if (lastSaved == null || current == lastSaved) return@collectLatest
                delay(600)
                if (current.first.isNotBlank() || current.second.isNotBlank()) {
                    pushUndo()
                    dao.upsertNote(currentNote())
                    lastSaved = current
                }
            }
    }

    // Saves pending changes right away, e.g. when leaving the edit mode
    fun saveNow() {
        if (lastSaved == null) return
        val current = titleFieldState.text.toString() to textFieldState.text.toString()
        if (current == lastSaved) return
        if (current.first.isNotBlank() || current.second.isNotBlank()) {
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
        titleFieldState.edit { replace(0, length, previous.first) }
        textFieldState.edit { replace(0, length, previous.second) }
        lastSaved = previous
        scope.launch { dao.upsertNote(currentNote()) }
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
        pushUndo()
        lastSaved = titleFieldState.text.toString() to newText
        val note = currentNote()
        scope.launch { dao.upsertNote(note) }
    }

    // Inserts a blank line above/below the content if the first/last line isn't
    // already blank, and shifts caretOffset to account for a line added above it
    fun padForEditing(caretOffset: Int): Int {
        val text = textFieldState.text.toString()
        addedTopPad = false
        addedBottomPad = false
        if (text.isEmpty()) return caretOffset
        val firstLineEnd = text.indexOf('\n').let { if (it == -1) text.length else it }
        val lastLineStart = text.lastIndexOf('\n') + 1
        val needsTopPad = firstLineEnd > 0
        val needsBottomPad = lastLineStart < text.length
        if (!needsTopPad && !needsBottomPad) return caretOffset
        var offset = caretOffset
        textFieldState.edit {
            if (needsBottomPad) replace(text.length, text.length, "\n")
            if (needsTopPad) {
                replace(0, 0, "\n")
                offset += 1
            }
        }
        addedTopPad = needsTopPad
        addedBottomPad = needsBottomPad
        // The padding isn't a real edit; keep lastSaved in sync so it doesn't
        // get autosaved or land on the undo stack on its own
        lastSaved?.let { lastSaved = it.first to textFieldState.text.toString() }
        return offset
    }

    // Removes the fake padding lines added by padForEditing, but only the ones
    // still empty; anything the user typed into them is kept
    fun stripEditPadding() {
        if (!addedTopPad && !addedBottomPad) return
        var strippedBottom = false
        var strippedTop = false
        textFieldState.edit {
            if (addedBottomPad && length > 0 && asCharSequence()[length - 1] == '\n') {
                replace(length - 1, length, "")
                strippedBottom = true
            }
            if (addedTopPad && length > 0 && asCharSequence()[0] == '\n') {
                replace(0, 1, "")
                strippedTop = true
            }
        }
        // Mirror the same removal on lastSaved so a padding-only round trip
        // (enter edit mode, leave without typing) doesn't look like an edit
        if (strippedBottom || strippedTop) {
            lastSaved?.let { saved ->
                var content = saved.second
                if (strippedBottom && content.endsWith("\n")) content = content.dropLast(1)
                if (strippedTop && content.startsWith("\n")) content = content.drop(1)
                lastSaved = saved.first to content
            }
        }
        addedTopPad = false
        addedBottomPad = false
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

    // Bumps the day in a "Muscu (jour)" checkbox line two days forward, wrapping
    // across the week, so a tap after a session sets it to the next planned one
    fun advanceMuscuLineDay(index: Int) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        val text = lines[index].checkboxText()
        val match = muscuDayMatch(text) ?: return
        val dayGroup = match.groups[1]!!
        val dayIndex = frenchDays.indexOf(dayGroup.value.trim().lowercase())
        val newDay = frenchDays[(dayIndex + 2) % 7]
        val newText = text.substring(0, dayGroup.range.first) + newDay + text.substring(dayGroup.range.last + 1)
        lines[index] = lines[index].substring(0, UNCHECKED_PREFIX.length) + newText
        saveContent(lines.joinToString("\n"))
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

    // Changes a checkbox line's "(N)" quantity by delta (floored at 1, "(1)" is dropped)
    fun changeLineQuantity(index: Int, delta: Int) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        val line = lines[index]
        if (!line.isCheckboxLine()) return
        val prefix = if (line.isCheckedLine()) CHECKED_PREFIX else UNCHECKED_PREFIX
        lines[index] = prefix + line.checkboxText().withQuantityDelta(delta)
        saveContent(lines.joinToString("\n"))
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Set while a Courses -> Ingrédients move (or a direct add / model re-sort) has
    // unknown items left to reconcile; drives the reconcile dialog and holds the
    // undo snapshots for the whole batch.
    var ingredientBatch by remember { mutableStateOf<IngredientMoveBatch?>(null) }
    var showAddIngredientDialog by remember { mutableStateOf(false) }

    // Writes new content to the note identified by noteId: through the live text field
    // when it's the note currently open (so the screen reflects it immediately), or
    // straight to the DAO otherwise.
    suspend fun updateNoteContent(noteId: String, newContent: String) {
        if (noteId == id) {
            saveContent(newContent)
        } else {
            dao.getNote(noteId)?.let {
                if (it.content != newContent) {
                    dao.upsertNote(it.copy(content = newContent, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

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
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val current = textFieldState.text.toString().split("\n").toMutableList()
                current.add(index.coerceAtMost(current.size), removed)
                saveContent(current.joinToString("\n"))
            }
        }
    }

    // Removes a checkbox line from this note (the "Ingrédients" note) and appends it as a
    // new unchecked item on the "Courses" note, so running out of an ingredient turns
    // straight into a shopping list entry. Does nothing if "Courses" doesn't exist.
    fun moveLineToCourses(index: Int) {
        val removed = textFieldState.text.toString().split("\n")[index]
        scope.launch {
            val courses = dao.getNotes().firstOrNull { it.title.trim().equals("Courses", ignoreCase = true) }
            if (courses == null) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = "Note \"Courses\" introuvable",
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                return@launch
            }

            val lines = textFieldState.text.toString().split("\n").toMutableList()
            lines.removeAt(index)
            saveContent(lines.joinToString("\n"))

            val addedLine = UNCHECKED_PREFIX + removed.checkboxText()
            val newCoursesLines =
                if (courses.content.isEmpty()) listOf(addedLine) else courses.content.split("\n") + addedLine
            // Position of the line we just appended, so undo removes exactly that one rather than
            // an older identical line that already existed in Courses.
            val addedIndex = newCoursesLines.size - 1
            dao.upsertNote(courses.copy(content = newCoursesLines.joinToString("\n"), updatedAt = System.currentTimeMillis()))

            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Déplacé vers Courses",
                actionLabel = "Annuler",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val current = textFieldState.text.toString().split("\n").toMutableList()
                current.add(index.coerceAtMost(current.size), removed)
                saveContent(current.joinToString("\n"))

                val latestCourses = dao.getNote(courses.id)
                if (latestCourses != null) {
                    val coursesLines = latestCourses.content.split("\n").toMutableList()
                    // Only undo the append if that slot still holds our line (Courses may have changed)
                    if (addedIndex in coursesLines.indices && coursesLines[addedIndex] == addedLine) {
                        coursesLines.removeAt(addedIndex)
                        dao.upsertNote(
                            latestCourses.copy(
                                content = coursesLines.joinToString("\n"),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    // Shows the "X ingrédients ajoutés/réordonnés" snackbar at the end of a batch, whose
    // Annuler restores the Courses, Ingrédients and Modèle notes to their snapshots.
    fun finishIngredientMove(batch: IngredientMoveBatch) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val n = batch.movedCount
            if (n == 0) return@launch
            val plural = if (n > 1) "s" else ""
            val verb = if (batch.reorder) "réordonné" else "ajouté"
            val result = snackbarHostState.showSnackbar(
                message = "$n ingrédient$plural $verb$plural",
                actionLabel = "Annuler",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (batch.coursesId != null && batch.coursesSnapshot != null) {
                    updateNoteContent(batch.coursesId, batch.coursesSnapshot)
                }
                updateNoteContent(batch.ingredientsId, batch.ingredientsSnapshot)
                updateNoteContent(batch.modelId, batch.modelSnapshot)
            }
        }
    }

    // Drops the current item from the pending list and, once none remain, closes the
    // batch and shows its snackbar. `moved` counts the item toward the "X ajoutés" tally.
    fun advancePending(moved: Boolean) {
        val batch = ingredientBatch ?: return
        val remaining = batch.pending.drop(1)
        val updated = batch.copy(
            pending = remaining,
            movedCount = batch.movedCount + if (moved) 1 else 0
        )
        if (remaining.isEmpty()) {
            ingredientBatch = null
            finishIngredientMove(updated)
        } else {
            ingredientBatch = updated
        }
    }

    // Moves every checked Courses item into the Ingrédients prompt. Items found in the
    // Modèle note are spliced in at their canonical spot and removed from Courses right
    // away; items not in the model are queued for the reconcile dialog.
    fun sendCheckedToIngredients() {
        scope.launch {
            val notes = dao.getNotes()
            val ingNote = notes.firstOrNull { it.title.trim().equals(INGREDIENTS_TITLE, ignoreCase = true) }
            val modelNote = notes.firstOrNull { it.title.trim().equals(INGREDIENT_MODEL_TITLE, ignoreCase = true) }
            if (ingNote == null || modelNote == null) {
                val missing = if (ingNote == null) INGREDIENTS_TITLE else INGREDIENT_MODEL_TITLE
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = "Note \"$missing\" introuvable",
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                return@launch
            }

            val checkedLines = textFieldState.text.toString().split("\n").filter { it.isCheckedLine() }
            if (checkedLines.isEmpty()) return@launch

            val groups = parseIngredientGroups(modelNote.content)
            val flatModel = groups.flatten()
            val modelKeys = flatModel.map { it.trim().lowercase() }

            val matchedNames = mutableListOf<String>()
            val matchedLines = mutableListOf<String>()
            val unknown = mutableListOf<ReconcileItem>()
            for (line in checkedLines) {
                val mi = modelKeys.indexOf(line.ingredientKey())
                if (mi >= 0) {
                    matchedNames.add(flatModel[mi])
                    matchedLines.add(line)
                } else {
                    unknown.add(ReconcileItem(name = line.checkboxText().withoutQuantitySuffix().trim(), coursesLine = line, inIngredients = false))
                }
            }

            val coursesSnap = textFieldState.text.toString()
            val ingSnap = ingNote.content
            val modelSnap = modelNote.content

            if (matchedNames.isNotEmpty()) {
                val present = presentIngredients(ingNote.content)
                val newPresent = (present + matchedNames).distinctBy { it.trim().lowercase() }
                updateNoteContent(ingNote.id, renderIntoIngredientsNote(ingNote.content, newPresent, groups))
            }
            if (matchedLines.isNotEmpty()) {
                saveContent(removeFirstLines(textFieldState.text.toString(), matchedLines))
            }

            val batch = IngredientMoveBatch(
                ingredientsId = ingNote.id,
                modelId = modelNote.id,
                coursesId = id,
                coursesSnapshot = coursesSnap,
                ingredientsSnapshot = ingSnap,
                modelSnapshot = modelSnap,
                groups = groups,
                pending = unknown,
                movedCount = matchedLines.size
            )
            if (unknown.isEmpty()) finishIngredientMove(batch) else ingredientBatch = batch
        }
    }

    // Adds a single ingredient directly from the Ingrédients note: same matching against
    // the model as the Courses flow, but with no Courses line to remove.
    fun addIngredientDirectly(name: String) {
        scope.launch {
            val modelNote = dao.getNotes().firstOrNull { it.title.trim().equals(INGREDIENT_MODEL_TITLE, ignoreCase = true) }
            if (modelNote == null) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = "Note \"$INGREDIENT_MODEL_TITLE\" introuvable",
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                return@launch
            }
            val groups = parseIngredientGroups(modelNote.content)
            val flatModel = groups.flatten()
            val modelKeys = flatModel.map { it.trim().lowercase() }
            val ingSnap = textFieldState.text.toString()
            val modelSnap = modelNote.content
            val mi = modelKeys.indexOf(name.trim().lowercase())

            if (mi >= 0) {
                val present = presentIngredients(textFieldState.text.toString())
                val newPresent = (present + flatModel[mi]).distinctBy { it.trim().lowercase() }
                saveContent(renderIntoIngredientsNote(textFieldState.text.toString(), newPresent, groups))
                finishIngredientMove(
                    IngredientMoveBatch(
                        ingredientsId = id,
                        modelId = modelNote.id,
                        coursesId = null,
                        coursesSnapshot = null,
                        ingredientsSnapshot = ingSnap,
                        modelSnapshot = modelSnap,
                        groups = groups,
                        pending = emptyList(),
                        movedCount = 1
                    )
                )
            } else {
                ingredientBatch = IngredientMoveBatch(
                    ingredientsId = id,
                    modelId = modelNote.id,
                    coursesId = null,
                    coursesSnapshot = null,
                    ingredientsSnapshot = ingSnap,
                    modelSnapshot = modelSnap,
                    groups = groups,
                    pending = listOf(ReconcileItem(name = name, coursesLine = null, inIngredients = false)),
                    movedCount = 0
                )
            }
        }
    }

    // Re-sorts the ingredients already present in the Ingrédients note by the model's
    // groups and alphabetical order; anything present but unrecognized by the model goes
    // through the same reconcile flow as usual.
    fun resortIngredients() {
        scope.launch {
            val ingNote = dao.getNotes().firstOrNull { it.title.trim().equals(INGREDIENTS_TITLE, ignoreCase = true) }
            if (ingNote == null) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = "Note \"$INGREDIENTS_TITLE\" introuvable",
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                return@launch
            }
            val groups = parseIngredientGroups(textFieldState.text.toString())
            val modelKeys = groups.flatten().map { it.trim().lowercase() }
            val present = presentIngredients(ingNote.content)
            val unknown = present.filter { it.trim().lowercase() !in modelKeys }

            val ingSnap = ingNote.content
            val modelSnap = textFieldState.text.toString()

            val newIngContent = renderIntoIngredientsNote(ingNote.content, present, groups)
            if (newIngContent != ingNote.content) {
                dao.upsertNote(ingNote.copy(content = newIngContent, updatedAt = System.currentTimeMillis()))
            }

            val pending = unknown.map { ReconcileItem(name = it, coursesLine = null, inIngredients = true) }
            val batch = IngredientMoveBatch(
                ingredientsId = ingNote.id,
                modelId = id,
                coursesId = null,
                coursesSnapshot = null,
                ingredientsSnapshot = ingSnap,
                modelSnapshot = modelSnap,
                groups = groups,
                pending = pending,
                movedCount = present.size - unknown.size,
                reorder = true
            )
            if (pending.isEmpty()) finishIngredientMove(batch) else ingredientBatch = batch
        }
    }

    // Reconcile choice: add the current unknown item to the model in the chosen group
    // (alphabetically placed), refresh the Ingrédients note, and remove it from Courses
    // if it came from there. `groupIndex` of -1 means a brand-new group.
    fun reconcileAddNew(groupIndex: Int) {
        val batch = ingredientBatch ?: return
        val current = batch.pending.firstOrNull() ?: return
        val name = current.name
        scope.launch {
            val modelNote = dao.getNote(batch.modelId)
            val ingNote = dao.getNote(batch.ingredientsId)
            if (modelNote == null || ingNote == null || name.isEmpty()) {
                advancePending(false)
                return@launch
            }
            val modelContent = if (batch.modelId == id) textFieldState.text.toString() else modelNote.content
            val newModelContent = addNameToModelGroup(modelContent, groupIndex, name)
            updateNoteContent(batch.modelId, newModelContent)

            val newGroups = parseIngredientGroups(newModelContent)
            val ingContent = if (batch.ingredientsId == id) textFieldState.text.toString() else ingNote.content
            val present = presentIngredients(ingContent)
            val newPresent = if (current.inIngredients) present else (present + name).distinctBy { it.trim().lowercase() }
            updateNoteContent(batch.ingredientsId, renderIntoIngredientsNote(ingContent, newPresent, newGroups))

            current.coursesLine?.let { line ->
                saveContent(removeFirstLines(textFieldState.text.toString(), listOf(line)))
            }
            advancePending(true)
        }
    }

    // Reconcile choice: the current unknown item is really an existing model entry
    // written differently; splice that canonical name in and remove it from Courses
    // if it came from there.
    fun reconcileMapExisting(canonical: String) {
        val batch = ingredientBatch ?: return
        val current = batch.pending.firstOrNull() ?: return
        scope.launch {
            val ingNote = dao.getNote(batch.ingredientsId)
            if (ingNote == null) {
                advancePending(false)
                return@launch
            }
            val ingContent = if (batch.ingredientsId == id) textFieldState.text.toString() else ingNote.content
            val present = presentIngredients(ingContent)
            val basePresent = if (current.inIngredients) {
                present.filter { it.trim().lowercase() != current.name.trim().lowercase() }
            } else present
            val newPresent = (basePresent + canonical).distinctBy { it.trim().lowercase() }
            updateNoteContent(batch.ingredientsId, renderIntoIngredientsNote(ingContent, newPresent, batch.groups))

            current.coursesLine?.let { line ->
                saveContent(removeFirstLines(textFieldState.text.toString(), listOf(line)))
            }
            advancePending(true)
        }
    }

    // Reconcile choice: leave the item as is and move on.
    fun reconcileSkip() = advancePending(false)

    // Wraps a whole line's text in the marker (or removes it when already wrapped)
    fun toggleLineMarker(index: Int, marker: String) {
        saveContent(textFieldState.text.toString().withLineMarkerToggled(index, marker))
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
                if (!titleFocused) {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
                BasicTextField(
                    state = titleFieldState,
                    inputTransformation = stripNewlinesTransformation,
                    // Wraps onto several lines rather than being clipped by the buttons;
                    // stripNewlinesTransformation still keeps it a single logical line.
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                        .onFocusChanged { titleFocused = it.isFocused },
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
                if (!titleFocused) {
                    if (!isEditing && textFieldState.text.toString().let { it != it.trimBlankEdgeLines() }) {
                        IconButton(onClick = { saveContent(textFieldState.text.toString().trimBlankEdgeLines()) }) {
                            Icon(Icons.Default.VerticalAlignCenter, contentDescription = "Supprimer les lignes vides en haut/bas")
                        }
                    }
                    if (!isEditing &&
                        titleFieldState.text.toString().trim().equals("Courses", ignoreCase = true) &&
                        textFieldState.text.toString().hasCheckedLine()
                    ) {
                        IconButton(onClick = { sendCheckedToIngredients() }) {
                            Icon(Icons.Default.Kitchen, contentDescription = "Ranger les articles cochés dans les Ingrédients")
                        }
                    }
                    if (!isEditing &&
                        titleFieldState.text.toString().trim().equals(INGREDIENTS_TITLE, ignoreCase = true)
                    ) {
                        IconButton(onClick = { showAddIngredientDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter un ingrédient")
                        }
                    }
                    if (!isEditing &&
                        titleFieldState.text.toString().trim().equals(INGREDIENT_MODEL_TITLE, ignoreCase = true)
                    ) {
                        IconButton(onClick = { resortIngredients() }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Réordonner les ingrédients présents")
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
                            if (!isEditing && textFieldState.text.toString().hasCheckboxLine()) {
                                DropdownMenuItem(
                                    text = { Text("Tout cocher") },
                                    leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        saveContent(setAllCheckboxes(textFieldState.text.toString(), true))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Tout décocher") },
                                    leadingIcon = { Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        saveContent(setAllCheckboxes(textFieldState.text.toString(), false))
                                    }
                                )
                                if (textFieldState.text.toString().hasCheckedLine()) {
                                    DropdownMenuItem(
                                        text = { Text("Supprimer les cochés") },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            saveContent(removeCheckedCheckboxes(textFieldState.text.toString()))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!isEditing && !titleFocused && titleFieldState.text.toString().equals("Courses", ignoreCase = true)) {
                val itemCount = textFieldState.text.toString().uncheckedItemCount()
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
                    title = titleFieldState.text.toString(),
                    onSaveContent = { saveContent(it) },
                    onToggleLine = { toggleLine(it) },
                    onDeleteLine = { deleteLine(it) },
                    onMoveToCourses = { moveLineToCourses(it) },
                    onChangeQuantity = { index, delta -> changeLineQuantity(index, delta) },
                    onAdvanceMuscu = { advanceMuscuLineDay(it) },
                    onToggleLineMarker = { index, marker -> toggleLineMarker(index, marker) },
                    onEnterEditAt = { enterEditAt(it) }
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

        ingredientBatch?.let { batch ->
            batch.pending.firstOrNull()?.let { current ->
                IngredientReconcileDialog(
                    itemName = current.name,
                    groups = batch.groups,
                    onAddNew = { reconcileAddNew(it) },
                    onMapExisting = { reconcileMapExisting(it) },
                    onSkip = { reconcileSkip() },
                    onDismiss = {
                        // Stop reconciling; keep what's already resolved, leave the rest as is
                        ingredientBatch = null
                        finishIngredientMove(batch.copy(pending = emptyList()))
                    }
                )
            }
        }

        if (showAddIngredientDialog) {
            AddIngredientNameDialog(
                onConfirm = {
                    showAddIngredientDialog = false
                    addIngredientDirectly(it)
                },
                onDismiss = { showAddIngredientDialog = false }
            )
        }
    }
}
