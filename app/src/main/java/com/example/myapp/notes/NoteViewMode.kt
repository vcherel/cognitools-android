package com.example.myapp.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CodeOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Everything a rendered line can ask the editor to do, by line index. */
@Immutable
data class NoteLineActions(
    val onToggleLine: (Int) -> Unit,
    val onDeleteLine: (Int) -> Unit,
    val onMoveToCourses: (Int) -> Unit,
    val onChangeQuantity: (index: Int, delta: Int) -> Unit,
    val onAdvanceMuscu: (Int) -> Unit,
    val onRemoveDateSuffix: (Int) -> Unit,
    val onToggleLineMarker: (index: Int, marker: String) -> Unit,
    val onToggleTitleLine: (Int) -> Unit,
    val onToggleResume: (Int) -> Unit,
    val onToggleEnhance: (Int) -> Unit,
    val onEnterEditAt: (offset: Int) -> Unit,
    val onReorder: (newContent: String) -> Unit
)

// The read-only rendering of a note: each line drawn as a checkbox, separator, or plain text,
// with long-press drag to reorder, double-tap to edit, and per-line action buttons.
@Composable
fun NoteViewMode(
    textFieldState: TextFieldState,
    title: String,
    actions: NoteLineActions,
    searchTerms: List<String> = emptyList()
) {
    val isIngredientsNote = title.equals(INGREDIENTS_TITLE, ignoreCase = true)
    val isCoursesNote = title.equals(COURSES_TITLE, ignoreCase = true)
    val isTodoListNote = title.equals(TODO_LIST_TITLE, ignoreCase = true)
    val isClaudeNote = title.equals(CLAUDE_NOTE_TITLE, ignoreCase = true)
    // Computed once per recomposition and reused below, instead of re-converting
    // the whole note's text to a String for every line.
    val fullText = textFieldState.text.toString()
    val lines = fullText.split("\n")

    // Character offset of the start of each line, for double tap to edit
    val lineStarts = remember(fullText) {
        val starts = IntArray(lines.size)
        var acc = 0
        lines.forEachIndexed { i, l ->
            starts[i] = acc
            acc += l.length + 1
        }
        starts
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
                if (newText != textFieldState.text.toString()) actions.onReorder(newText)
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { selectedLine = -1 },
                    onDoubleTap = { actions.onEnterEditAt(textFieldState.text.length) }
                )
            }
    ) {
        (displayOrder ?: lines.indices.toList()).forEach { lineIndex ->
            key(lineIndex) {
                NoteLine(
                    lineIndex = lineIndex,
                    line = lines[lineIndex],
                    lineStart = lineStarts[lineIndex],
                    isDragged = lineIndex == draggedLine,
                    dragOffsetPx = if (lineIndex == draggedLine) dragOffset.roundToInt() else 0,
                    selected = lineIndex == selectedLine,
                    isIngredientsNote = isIngredientsNote,
                    isCoursesNote = isCoursesNote,
                    isTodoListNote = isTodoListNote,
                    isClaudeNote = isClaudeNote,
                    hasResumeAfter = lines.getOrNull(lineIndex + 1)?.isMarkerLine(RESUME_LINE) == true,
                    hasEnhanceAfter = lines[lineIndex].isTitleLine() &&
                        lines.categoryAfter(lineIndex).any { it.isMarkerLine(ENHANCE_LINE) },
                    actions = actions,
                    searchTerms = searchTerms,
                    onSizeChanged = { lineHeights[lineIndex] = it },
                    onDragStart = {
                        draggedLine = lineIndex
                        dragOffset = 0f
                        displayOrder = textFieldState.text.toString().split("\n").indices.toList()
                    },
                    onDrag = { amountY -> onDragMove(amountY) },
                    onDragEnd = { endDrag(commit = true) },
                    onDragCancel = { endDrag(commit = false) },
                    onToggleSelected = { selectedLine = if (lineIndex == selectedLine) -1 else lineIndex },
                    onDeselect = { selectedLine = -1 }
                )
            }
        }
    }
}

// A single line of the note. Kept as its own composable (rather than inlined in the
// forEach above) so Compose can skip re-rendering the other lines when only one line's
// selection, drag position, or content actually changes.
@Composable
private fun NoteLine(
    lineIndex: Int,
    line: String,
    lineStart: Int,
    isDragged: Boolean,
    dragOffsetPx: Int,
    selected: Boolean,
    isIngredientsNote: Boolean,
    isCoursesNote: Boolean,
    isTodoListNote: Boolean,
    isClaudeNote: Boolean,
    hasResumeAfter: Boolean,
    hasEnhanceAfter: Boolean,
    actions: NoteLineActions,
    searchTerms: List<String>,
    onSizeChanged: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onToggleSelected: () -> Unit,
    onDeselect: () -> Unit
) {
    // Text layout of the line, to map a double tap to a cursor position
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 1f else 0f)
            .offset { IntOffset(0, dragOffsetPx) }
            .onSizeChanged { onSizeChanged(it.height) }
            .background(
                if (isDragged) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
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
                    .pointerInput(lineStart, line) {
                        detectTapGestures(onDoubleTap = {
                            actions.onEnterEditAt(lineStart + line.length)
                        })
                    }
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                if (name.isNotEmpty()) {
                    Text(
                        withSearchHighlight(AnnotatedString(name.uppercase()), searchTerms),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
                LineIconButton(Icons.Default.Delete, "Supprimer la ligne") { actions.onDeleteLine(lineIndex) }
            }
        } else if (line.isCheckboxLine()) {
            val checked = line.isCheckedLine()
            val text = line.checkboxText()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.onToggleLine(lineIndex) }
            ) {
                Checkbox(checked = checked, onCheckedChange = { actions.onToggleLine(lineIndex) })
                Text(
                    withSearchHighlight(text.formatInline(), searchTerms),
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (checked) Color.Gray else MaterialTheme.colorScheme.onBackground,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(lineStart, line) {
                            detectTapGestures(
                                onTap = { actions.onToggleLine(lineIndex) },
                                onDoubleTap = { pos ->
                                    val inLine = textLayout?.getOffsetForPosition(pos) ?: text.length
                                    actions.onEnterEditAt(
                                        lineStart + UNCHECKED_PREFIX.length + inLine.coerceIn(0, text.length)
                                    )
                                }
                            )
                        }
                )
                if (isCoursesNote) {
                    if (text.itemQuantity() > 1) {
                        LineIconButton(Icons.Default.Remove, "Diminuer la quantité") {
                            actions.onChangeQuantity(lineIndex, -1)
                        }
                    }
                    LineIconButton(Icons.Default.Add, "Augmenter la quantité") {
                        actions.onChangeQuantity(lineIndex, 1)
                    }
                }
                if (isIngredientsNote) {
                    LineIconButton(Icons.Default.ShoppingCart, "Déplacer vers Courses") {
                        actions.onMoveToCourses(lineIndex)
                    }
                }
                if (isTodoListNote && muscuDayMatch(text) != null) {
                    LineIconButton(Icons.Default.FitnessCenter, "Jour suivant") {
                        actions.onAdvanceMuscu(lineIndex)
                    }
                } else if (isTodoListNote && text.hasDateSuffix()) {
                    LineIconButton(Icons.Default.EventBusy, "Retirer la date") {
                        actions.onRemoveDateSuffix(lineIndex)
                    }
                }
                LineIconButton(Icons.Default.Delete, "Supprimer la ligne") { actions.onDeleteLine(lineIndex) }
            }
        } else {
            val isTitle = isClaudeNote && line.isTitleLine()
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        withSearchHighlight((if (line.isEmpty()) " " else line).formatInline(), searchTerms),
                        style = MaterialTheme.typography.bodyLarge,
                        onTextLayout = { textLayout = it },
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceVariant
                                else Color.Transparent
                            )
                            .padding(vertical = 4.dp)
                            .pointerInput(lineStart, line) {
                                detectTapGestures(
                                    onTap = { onToggleSelected() },
                                    onDoubleTap = { pos ->
                                        onDeselect()
                                        val inLine = textLayout?.getOffsetForPosition(pos) ?: line.length
                                        actions.onEnterEditAt(lineStart + inLine.coerceIn(0, line.length))
                                    }
                                )
                            }
                    )
                    if (isTitle) {
                        LineIconButton(
                            if (hasResumeAfter) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            if (hasResumeAfter) "Retirer Reprendre" else "Marquer à reprendre"
                        ) { actions.onToggleResume(lineIndex) }
                        LineIconButton(
                            if (hasEnhanceAfter) Icons.Default.Code else Icons.Default.CodeOff,
                            if (hasEnhanceAfter) "Retirer Enhance code" else "Ajouter Enhance code"
                        ) { actions.onToggleEnhance(lineIndex) }
                    }
                }
                if (selected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LineIconButton(Icons.Default.FormatBold, "Gras") {
                            actions.onToggleLineMarker(lineIndex, "**")
                        }
                        LineIconButton(Icons.Default.FormatItalic, "Italique") {
                            actions.onToggleLineMarker(lineIndex, "*")
                        }
                        LineIconButton(Icons.Default.FormatUnderlined, "Souligné") {
                            actions.onToggleLineMarker(lineIndex, "__")
                        }
                        LineIconButton(Icons.Default.Title, "Titre") {
                            actions.onToggleTitleLine(lineIndex)
                        }
                        if (isIngredientsNote) {
                            LineIconButton(Icons.Default.ShoppingCart, "Déplacer vers Courses") {
                                actions.onMoveToCourses(lineIndex)
                                onDeselect()
                            }
                        }
                        LineIconButton(Icons.Default.Delete, "Supprimer la ligne") {
                            actions.onDeleteLine(lineIndex)
                            onDeselect()
                        }
                        LineIconButton(Icons.Default.Edit, "Éditer") {
                            onDeselect()
                            actions.onEnterEditAt(lineStart + line.length)
                        }
                    }
                }
            }
        }
    }
}

// The small gray action button every line trailing control is made of.
@Composable
private fun LineIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = description, tint = Color.Gray)
    }
}
