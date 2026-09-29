package com.example.myapp.notes

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * Pressing Enter at the end of a checkbox line continues the list with a fresh
 * checkbox; on an empty checkbox line it removes the checkbox instead. Enter
 * within the prefix of a non empty checkbox line inserts an empty checkbox
 * above the item. Backspacing right after a checkbox prefix removes the whole
 * prefix in one go instead of one character at a time.
 *
 * The first letter typed into an empty checkbox is uppercased, since the keyboard
 * only capitalizes after a full stop or at a line start, never after "[ ] ".
 * Erasing that capital turns it off for the line, so a lowercase item stays possible.
 */
internal class CheckboxInputTransformation : InputTransformation {
    private var capitalizedLineStart = -1
    private var lowercaseLineStart = -1

    override fun TextFieldBuffer.transformInput() {
        val cursor = selection.start
        // Not copied to a String: this runs on every keystroke, and a long note would be copied each time.
        val oldText = originalText

        val typedNewline = length == oldText.length + 1 &&
            cursor > 0 && cursor <= length && charAt(cursor - 1) == '\n'
        if (typedNewline) {
            val oldCursor = cursor - 1
            val oldLineStart = if (oldCursor == 0) 0 else oldText.lastIndexOf('\n', oldCursor - 1) + 1
            val oldLineEnd = oldText.indexOf('\n', oldCursor).let { if (it == -1) oldText.length else it }
            val oldLine = oldText.substring(oldLineStart, oldLineEnd)
            if (oldLine.isCheckboxLine() &&
                oldCursor - oldLineStart <= UNCHECKED_PREFIX.length &&
                oldLine.checkboxText().isNotBlank()
            ) {
                replace(cursor - 1, cursor, "")
                replace(oldLineStart, oldLineStart, UNCHECKED_PREFIX + "\n")
                selection = TextRange(oldLineStart + UNCHECKED_PREFIX.length)
                capitalizedLineStart = -1
                lowercaseLineStart = -1
                return
            }

            val newText = asCharSequence().toString()
            val prevLineStart = if (cursor == 1) 0 else newText.lastIndexOf('\n', cursor - 2) + 1
            val prevLine = newText.substring(prevLineStart, cursor - 1)
            capitalizedLineStart = -1
            lowercaseLineStart = -1
            if (!prevLine.isCheckboxLine()) return

            if (prevLine.checkboxText().isBlank()) {
                // Empty checkbox line: Enter exits the list, dropping the empty item
                replace(prevLineStart, cursor, "")
                selection = TextRange(prevLineStart)
            } else {
                replace(cursor, cursor, UNCHECKED_PREFIX)
                selection = TextRange(cursor + UNCHECKED_PREFIX.length)
            }
            return
        }

        val deletedOneChar = selection.collapsed && length == oldText.length - 1
        if (deletedOneChar) {
            val newText = asCharSequence().toString()
            val lineStart = if (cursor == 0) 0 else newText.lastIndexOf('\n', cursor - 1) + 1
            val remainder = newText.substring(lineStart, cursor)
            if (remainder == UNCHECKED_PREFIX.dropLast(1) || remainder == CHECKED_PREFIX.dropLast(1)) {
                // The last character of a checkbox prefix was just erased; drop the rest in one go
                replace(lineStart, cursor, "")
                selection = TextRange(lineStart)
            } else if (lineStart == capitalizedLineStart && remainder == UNCHECKED_PREFIX &&
                (cursor == length || charAt(cursor) == '\n')
            ) {
                lowercaseLineStart = lineStart
            }
            return
        }

        val typedOneChar = selection.collapsed && length == oldText.length + 1 && cursor > 0
        if (typedOneChar && charAt(cursor - 1).isLowerCase()) {
            val at = cursor - 1
            val lineStart = if (at == 0) 0 else oldText.lastIndexOf('\n', at - 1) + 1
            val emptyCheckbox = at - lineStart == UNCHECKED_PREFIX.length &&
                oldText.regionMatches(lineStart, UNCHECKED_PREFIX, 0, UNCHECKED_PREFIX.length) &&
                (at == oldText.length || oldText[at] == '\n')
            if (emptyCheckbox && lineStart != lowercaseLineStart) {
                replace(at, cursor, charAt(at).uppercase())
                capitalizedLineStart = lineStart
            }
        }
    }
}

// The title field is single line; strips any newline that sneaks in (e.g. from pasted text).
internal val stripNewlinesTransformation = InputTransformation {
    var i = 0
    while (i < length) {
        if (charAt(i) == '\n') replace(i, i + 1, "") else i++
    }
}

internal class SlashCommand(val label: String, val keywords: List<String>, val prefix: String, val suffix: String = "")

internal val SLASH_COMMANDS = listOf(
    SlashCommand("Check", listOf("case", "checkbox", "todo"), UNCHECKED_PREFIX),
    SlashCommand("Séparateur", listOf("separateur", "séparateur", "ligne"), SEPARATOR_PREFIX),
    SlashCommand("Titre", listOf("titre", "title"), "**__", "__**")
)

/**
 * If the cursor sits in a "/commande" token at the start of its line, returns
 * the token start index and the text typed after the slash.
 */
internal fun slashQuery(state: TextFieldState): Pair<Int, String>? {
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

internal val frenchDays = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

// Finds a "(jour)" day name on a "Muscu (jour)" checkbox line, regardless of note
fun muscuDayMatch(lineText: String) =
    Regex("""^Muscu\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE).find(lineText)
        ?.takeIf { frenchDays.contains(it.groupValues[1].trim().lowercase()) }

private val episodeRegex = Regex("""\bS(\d+)\s*E(\d+)\b""", RegexOption.IGNORE_CASE)

/** True for a Movies note line tracking a series, "- Breaking bad S1E10". */
fun String.hasEpisode(): Boolean = episodeRegex.containsMatchIn(this)

/** The next episode ("S1E10" to "S1E11"), or with [season] the next season's first ("S2E1"). */
fun String.withEpisodeBumped(season: Boolean): String = episodeRegex.replace(this) { match ->
    val s = match.groupValues[1].toInt()
    val e = match.groupValues[2].toInt()
    if (season) "S${s + 1}E1" else "S${s}E${e + 1}"
}

// Bold ("**") and italic ("*") both use the asterisk, so a plain prefix check can't
// tell "already bold, adding italic" from "already italic": the asterisk run length
// (0..3) encodes the combination, matching formatInline (1 = italic, 2 = bold, 3 = both).
// Toggling a style flips its bit in that run length instead of blindly wrapping.
private fun asteriskBit(marker: String) = if (marker == "*") 1 else 2

// Wraps a whole line's text in the marker (or removes it when already wrapped),
// returning the full content with that one line replaced.
internal fun String.withLineMarkerToggled(index: Int, marker: String): String {
    val lines = split("\n").toMutableList()
    val line = lines[index]
    lines[index] = if (marker == "*" || marker == "**") {
        var run = 0
        while (run < 3 && run < line.length && line[run] == '*') run++
        var runEnd = 0
        while (runEnd < 3 && runEnd < line.length - run && line[line.length - 1 - runEnd] == '*') runEnd++
        val oldRun = minOf(run, runEnd)
        val inner = line.substring(oldRun, line.length - oldRun)
        val newRun = oldRun xor asteriskBit(marker)
        "*".repeat(newRun) + inner + "*".repeat(newRun)
    } else {
        val m = marker.length
        if (line.length >= 2 * m && line.startsWith(marker) && line.endsWith(marker)) {
            line.substring(m, line.length - m)
        } else {
            marker + line + marker
        }
    }
    return lines.joinToString("\n")
}

// Same title toggle as toggleTitleLine below, on a line picked by index rather than by
// where the cursor is: what the read-only view's line actions need.
internal fun String.withTitleLineToggled(index: Int): String {
    val lines = split("\n").toMutableList()
    val line = lines[index]
    lines[index] = if (line.isTitleLine()) line.substring(4, line.length - 4) else "**__" + line + "__**"
    return lines.joinToString("\n")
}

// The whole line the cursor sits on becomes a title (or stops being one), with the same
// "**__ ... __**" markers the /titre command inserts. Line level rather than selection level,
// because a title is a line, and the cursor is left after the text.
internal fun TextFieldState.toggleTitleLine() {
    val fullText = text.toString()
    val cursor = selection.max
    val lineStart = if (cursor == 0) 0 else fullText.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = fullText.indexOf('\n', cursor).let { if (it == -1) fullText.length else it }
    val line = fullText.substring(lineStart, lineEnd)
    val toggled = if (line.isTitleLine()) line.substring(4, line.length - 4) else "**__" + line + "__**"
    edit {
        replace(lineStart, lineEnd, toggled)
        selection = TextRange(lineStart + toggled.length - if (line.isTitleLine()) 0 else 4)
    }
}

// Wraps the selection in the marker (or removes it when already wrapped);
// with no selection, inserts a marker pair and puts the cursor inside.
internal fun TextFieldState.toggleInlineMarker(marker: String) {
    val fullText = text.toString()
    val start = selection.min
    val end = selection.max
    if (marker == "*" || marker == "**") {
        var run = 0
        while (run < 3 && start - run - 1 >= 0 && fullText[start - run - 1] == '*') run++
        var runEnd = 0
        while (runEnd < 3 && end + runEnd < fullText.length && fullText[end + runEnd] == '*') runEnd++
        val oldRun = minOf(run, runEnd)
        val newRun = oldRun xor asteriskBit(marker)
        edit {
            if (oldRun > 0) {
                replace(end, end + oldRun, "")
                replace(start - oldRun, start, "")
            }
            val newStart = start - oldRun
            val newEnd = end - oldRun
            if (newRun > 0) {
                val stars = "*".repeat(newRun)
                replace(newEnd, newEnd, stars)
                replace(newStart, newStart, stars)
                selection = TextRange(newStart + newRun, newEnd + newRun)
            } else {
                selection = TextRange(newStart, newEnd)
            }
        }
        return
    }
    val m = marker.length
    edit {
        if (start >= m && end + m <= fullText.length &&
            fullText.startsWith(marker, start - m) && fullText.startsWith(marker, end)
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
