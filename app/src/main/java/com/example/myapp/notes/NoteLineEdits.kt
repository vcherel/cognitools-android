package com.example.myapp.notes

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.myapp.recipes.withIngredientComment
import com.example.myapp.showUndoSnackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Every edit the note's read-only view can make to a single line: toggling its checkbox, changing
 * its quantity, bumping a series episode, deleting it. They all have the same shape (rewrite the note's
 * lines in place, save), which is what [editLines] holds; the actual text rules live in NoteEditing.
 *
 * Built by the editor and handed to NoteViewMode as part of a [NoteLineActions].
 */
class NoteLineEdits(
    private val textFieldState: TextFieldState,
    private val saveContent: (String) -> Unit,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val isCoursesNote: () -> Boolean
) {
    /** Rewrites the note's lines in place: the single shape every per-line action shares. */
    private inline fun editLines(block: (MutableList<String>) -> Unit) {
        val lines = textFieldState.text.toString().split("\n").toMutableList()
        block(lines)
        saveContent(lines.joinToString("\n"))
    }

    fun toggleLine(index: Int) = editLines { lines ->
        val line = lines[index]
        val prefix = if (line.isCheckedLine()) UNCHECKED_PREFIX else CHECKED_PREFIX
        lines[index] = prefix + line.checkboxText()
    }

    /** Changes a checkbox line's "(N)" quantity by [delta] (floored at 1, "(1)" is dropped). */
    fun changeQuantity(index: Int, delta: Int) = editLines { lines ->
        val line = lines[index]
        if (!line.isCheckboxLine()) return@editLines
        lines[index] = line.checkboxPrefix() + line.checkboxText().withQuantityDelta(delta)
    }

    /** Moves a Movies note series line to its next episode, or with [season] to the next season. */
    fun bumpEpisode(index: Int, season: Boolean) = editLines { lines ->
        lines[index] = lines[index].withEpisodeBumped(season)
    }

    /**
     * Drops a checkbox line's trailing "(jour)"/"(date)" waiting suffix, once its day or date has
     * come and the item moves out of the waiting zone into the active list.
     */
    fun removeDateSuffix(index: Int) = editLines { lines ->
        val line = lines[index]
        if (!line.isCheckboxLine()) return@editLines
        lines[index] = line.checkboxPrefix() + line.checkboxText().withoutDateSuffix()
    }

    /** Replaces an Ingrédients line's "(...)" comment, "Lait" becoming "Lait (entamé)"; blank drops it. */
    fun commentIngredient(index: Int, comment: String) = editLines { lines ->
        lines[index] = lines[index].withIngredientComment(comment)
    }

    /** Wraps a whole line's text in [marker], or removes it when the line is already wrapped. */
    fun toggleLineMarker(index: Int, marker: String) {
        saveContent(textFieldState.text.toString().withLineMarkerToggled(index, marker))
    }

    /** Turns a whole line into a title, or back into plain text when it already is one. */
    fun toggleTitleLine(index: Int) {
        saveContent(textFieldState.text.toString().withTitleLineToggled(index))
    }

    fun deleteLine(index: Int) {
        val before = textFieldState.text.toString()
        val lines = before.split("\n").toMutableList()
        lines.removeAt(index)
        // On the Courses note, a section whose last article just left goes with it.
        val updated = lines.joinToString("\n")
        saveContent(if (isCoursesNote()) dropEmptyCourseSections(updated) else updated)
        scope.launch {
            if (snackbar.showUndoSnackbar("Item deleted")) saveContent(before)
        }
    }
}

@Composable
fun rememberNoteLineEdits(
    textFieldState: TextFieldState,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    saveContent: (String) -> Unit,
    isCoursesNote: () -> Boolean
): NoteLineEdits {
    return remember(textFieldState, snackbar) {
        NoteLineEdits(textFieldState, saveContent, snackbar, scope, isCoursesNote)
    }
}
