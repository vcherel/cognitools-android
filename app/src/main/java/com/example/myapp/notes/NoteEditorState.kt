package com.example.myapp.notes

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The note as it stood at a save: what the autosave compares against and what undo steps back to. */
private data class NoteSnapshot(val title: String, val content: String)

// Whether the current edit session added a fake blank line at the top and/or the bottom of the
// content, to give room to type before the first line or after the last.
private data class EditPadding(val top: Boolean = false, val bottom: Boolean = false) {
    val any: Boolean get() = top || bottom
}

/**
 * The note being edited: its text, its row in the database, the autosave, the undo stack and the
 * PIN gate. [NoteEditorScreen] owns the layout and reads everything about the note itself here.
 */
@Stable
class NoteEditorState(noteId: String, private val dao: NoteDao, private val scope: CoroutineScope) {
    private val isNew = noteId == "new"
    val id: String = if (isNew) UUID.randomUUID().toString() else noteId

    val titleFieldState = TextFieldState()
    val textFieldState = TextFieldState()

    /** True while the raw text field is up, false while the read-only view is showing. */
    var isEditing by mutableStateOf(isNew)
        private set

    var locked by mutableStateOf(false)
        private set

    /** A note locked when it was opened stays behind the PIN dialog until it is entered. */
    var pinNeeded by mutableStateOf(false)
        private set

    private var color = 0

    // Null until the note is loaded; guards the autosave against saving too early.
    private var lastSaved by mutableStateOf<NoteSnapshot?>(if (isNew) NoteSnapshot("", "") else null)

    // Snapshots to step back through with the undo button.
    private val undoStack = mutableStateListOf<NoteSnapshot>()

    private var editPadding = EditPadding()

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    private val title: String get() = titleFieldState.text.toString()
    private val content: String get() = textFieldState.text.toString()

    private fun snapshot() = NoteSnapshot(title, content)

    private fun currentNote() = Note(
        id = id,
        title = title,
        content = content,
        updatedAt = System.currentTimeMillis(),
        color = color,
        locked = locked
    )

    /** Reads the note in, and opens straight into edit mode when the caller asked for a caret. */
    suspend fun load(initialEditOffset: Int) {
        if (isNew) return
        val note = dao.getNote(id)
        titleFieldState.setTextAndPlaceCursorAtEnd(note?.title.orEmpty())
        textFieldState.setTextAndPlaceCursorAtEnd(note?.content.orEmpty())
        color = note?.color ?: 0
        locked = note?.locked == true
        pinNeeded = locked
        lastSaved = snapshot()
        if (initialEditOffset >= 0) enterEditAt(initialEditOffset)
    }

    fun pinEntered() {
        pinNeeded = false
    }

    /**
     * Debounced autosave while typing. Tracked via snapshotFlow rather than a LaunchedEffect key so
     * typing doesn't force the whole editor to recompose just to keep the key up to date.
     */
    suspend fun autosave() {
        snapshotFlow { snapshot() }
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

    // Pushes the last saved snapshot onto the undo stack before it gets replaced.
    private fun pushUndo() {
        val previous = lastSaved ?: return
        undoStack.add(previous)
        if (undoStack.size > 50) undoStack.removeAt(0)
    }

    private fun save() {
        val note = currentNote()
        scope.launch { dao.upsertNote(note) }
    }

    // Saves pending changes right away, e.g. when leaving the edit mode.
    private fun saveNow() {
        if (lastSaved == null) return
        val current = snapshot()
        if (current == lastSaved) return
        if (current.title.isNotBlank() || current.content.isNotBlank()) {
            pushUndo()
            lastSaved = current
            save()
        }
    }

    /** Immediate save for changes made from the view mode (checkbox toggles, reorders). */
    fun saveContent(newText: String) {
        textFieldState.edit { replace(0, length, newText) }
        pushUndo()
        lastSaved = NoteSnapshot(title, newText)
        save()
    }

    /** Steps back to the previous snapshot on the undo stack, one step per call. */
    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.size - 1)
        titleFieldState.edit { replace(0, length, previous.title) }
        textFieldState.edit { replace(0, length, previous.content) }
        lastSaved = previous
        save()
    }

    /**
     * Persists a lock/unlock toggle right away. An empty new note has nothing to save yet; the flag
     * rides along on the next autosave once it has content.
     */
    fun persistLock(newLocked: Boolean) {
        locked = newLocked
        if (lastSaved != null && (titleFieldState.text.isNotBlank() || textFieldState.text.isNotBlank())) {
            lastSaved = snapshot()
            save()
        }
    }

    /**
     * Enters edit mode with the caret at [offset] in the content, padding the note first so there is
     * room to type before the first line or after the last.
     */
    fun enterEditAt(offset: Int) {
        val padded = padForEditing(offset)
        textFieldState.edit { selection = TextRange(padded.coerceIn(0, length)) }
        isEditing = true
    }

    /** While editing, back validates the note and shows the view; otherwise it leaves the screen. */
    fun goBack(onLeave: () -> Unit) {
        if (!isEditing) {
            finish(onLeave)
            return
        }
        stripEditPadding()
        saveNow()
        isEditing = false
    }

    // Saves or deletes the note on the way out: one left empty was never really written.
    private fun finish(onLeave: () -> Unit) {
        scope.launch {
            if (lastSaved != null) {
                val current = snapshot()
                if (current.title.isBlank() && current.content.isBlank()) {
                    dao.deleteNote(id)
                } else if (current != lastSaved) {
                    dao.upsertNote(currentNote())
                }
            }
            onLeave()
        }
    }

    // Inserts a blank line above/below the content if the first/last line isn't already blank, and
    // shifts caretOffset to account for a line added above it.
    private fun padForEditing(caretOffset: Int): Int {
        val text = content
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
        // The padding isn't a real edit; keep lastSaved in sync so it doesn't get autosaved or land
        // on the undo stack on its own.
        lastSaved?.let { lastSaved = it.copy(content = content) }
        return offset
    }

    // Removes the fake padding lines added by padForEditing, but only the ones still empty;
    // anything the user typed into them is kept.
    private fun stripEditPadding() {
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
        // Mirror the same removal on lastSaved so a padding-only round trip (enter edit mode, leave
        // without typing) doesn't look like an edit.
        if (strippedBottom || strippedTop) {
            lastSaved?.let { saved ->
                var kept = saved.content
                if (strippedBottom && kept.endsWith("\n")) kept = kept.dropLast(1)
                if (strippedTop && kept.startsWith("\n")) kept = kept.drop(1)
                lastSaved = saved.copy(content = kept)
            }
        }
        editPadding = EditPadding()
    }
}

@Composable
fun rememberNoteEditorState(noteId: String, initialEditOffset: Int, dao: NoteDao): NoteEditorState {
    val scope = rememberCoroutineScope()
    val state = remember { NoteEditorState(noteId, dao, scope) }
    LaunchedEffect(Unit) { state.load(initialEditOffset) }
    LaunchedEffect(Unit) { state.autosave() }
    return state
}
