package com.example.myapp.notes

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Every edit the note's read-only view can make to a single line: toggling its checkbox, changing
 * its quantity, bumping a muscu day, deleting it. They all have the same shape (rewrite the note's
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

    /**
     * Bumps the day in a "Muscu (jour)" checkbox line two days forward, wrapping across the week, so
     * a tap after a session sets it to the next planned one.
     */
    fun advanceMuscuDay(index: Int) = editLines { lines ->
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

    /**
     * Drops a checkbox line's trailing "(jour)"/"(date)" waiting suffix, once its day or date has
     * come and the item moves out of the waiting zone into the active list.
     */
    fun removeDateSuffix(index: Int) = editLines { lines ->
        val line = lines[index]
        if (!line.isCheckboxLine()) return@editLines
        lines[index] = line.checkboxPrefix() + line.checkboxText().withoutDateSuffix()
    }

    /** Wraps a whole line's text in [marker], or removes it when the line is already wrapped. */
    fun toggleLineMarker(index: Int, marker: String) {
        saveContent(textFieldState.text.toString().withLineMarkerToggled(index, marker))
    }

    /** Turns a whole line into a title, or back into plain text when it already is one. */
    fun toggleTitleLine(index: Int) {
        saveContent(textFieldState.text.toString().withTitleLineToggled(index))
    }

    /** Adds/removes the "Resume" marker right after a category title, in the Claude note. */
    fun toggleResumeAfter(index: Int) = editLines { lines ->
        if (lines.getOrNull(index + 1)?.isMarkerLine(RESUME_LINE) == true) lines.removeAt(index + 1)
        else lines.add(index + 1, RESUME_LINE)
    }

    /**
     * Adds/removes the "Enhance code" marker at the end of a category, in the Claude note:
     * after the category's last non-empty line, before the blank line and the next title.
     */
    fun toggleEnhanceAtEnd(index: Int) = editLines { lines ->
        val existing = (index + 1 until lines.size)
            .takeWhile { !lines[it].isTitleLine() }
            .firstOrNull { lines[it].isMarkerLine(ENHANCE_LINE) }
        if (existing != null) {
            lines.removeAt(existing)
            return@editLines
        }
        var end = index + 1 + lines.categoryAfter(index).size
        while (end > index + 1 && lines[end - 1].isBlank()) end--
        lines.add(end, ENHANCE_LINE)
    }

    fun deleteLine(index: Int) {
        val before = textFieldState.text.toString()
        val lines = before.split("\n").toMutableList()
        lines.removeAt(index)
        // On the Courses note, a section whose last article just left goes with it.
        val updated = lines.joinToString("\n")
        saveContent(if (isCoursesNote()) dropEmptyCourseSections(updated) else updated)
        scope.launch {
            // Replace any snackbar from a previous delete instead of queueing
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "Élément supprimé",
                actionLabel = "Annuler",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                saveContent(before)
            }
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
): NoteLineEdits = remember(textFieldState, snackbar) {
    NoteLineEdits(textFieldState, saveContent, snackbar, scope, isCoursesNote)
}
