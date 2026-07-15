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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

// The read-only rendering of a note: each line drawn as a checkbox, separator, or plain text,
// with long-press drag to reorder, double-tap to edit, and per-line action buttons.
@Composable
fun NoteViewMode(
    textFieldState: TextFieldState,
    title: String,
    onSaveContent: (String) -> Unit,
    onToggleLine: (Int) -> Unit,
    onDeleteLine: (Int) -> Unit,
    onMoveToCourses: (Int) -> Unit,
    onAdvanceMuscu: (Int) -> Unit,
    onToggleLineMarker: (Int, String) -> Unit,
    onEnterEditAt: (Int) -> Unit
) {
    val isIngredientsNote = title.equals("Ingrédients", ignoreCase = true)
    val isTodoListNote = title.equals("Todo list", ignoreCase = true)
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
                if (newText != textFieldState.text.toString()) onSaveContent(newText)
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
                    onDoubleTap = { onEnterEditAt(textFieldState.text.length) }
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
                                        onEnterEditAt(lineStarts[lineIndex] + line.length)
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
                                onClick = { onDeleteLine(lineIndex) },
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
                                .clickable { onToggleLine(lineIndex) }
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggleLine(lineIndex) })
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
                                            onTap = { onToggleLine(lineIndex) },
                                            onDoubleTap = { pos ->
                                                val inLine = textLayout[0]?.getOffsetForPosition(pos)
                                                    ?: line.checkboxText().length
                                                onEnterEditAt(
                                                    lineStarts[lineIndex] + UNCHECKED_PREFIX.length +
                                                        inLine.coerceIn(0, line.checkboxText().length)
                                                )
                                            }
                                        )
                                    }
                            )
                            if (isIngredientsNote) {
                                IconButton(
                                    onClick = { onMoveToCourses(lineIndex) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ShoppingCart,
                                        contentDescription = "Déplacer vers Courses",
                                        tint = Color.Gray
                                    )
                                }
                            }
                            if (isTodoListNote && muscuDayMatch(line.checkboxText()) != null) {
                                IconButton(
                                    onClick = { onAdvanceMuscu(lineIndex) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.FitnessCenter,
                                        contentDescription = "Jour suivant",
                                        tint = Color.Gray
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteLine(lineIndex) },
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
                                                onEnterEditAt(lineStarts[lineIndex] + inLine.coerceIn(0, line.length))
                                            }
                                        )
                                    }
                            )
                            if (selected) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { onToggleLineMarker(lineIndex, "**") },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.FormatBold, contentDescription = "Gras", tint = Color.Gray)
                                    }
                                    IconButton(
                                        onClick = { onToggleLineMarker(lineIndex, "*") },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.FormatItalic, contentDescription = "Italique", tint = Color.Gray)
                                    }
                                    IconButton(
                                        onClick = { onToggleLineMarker(lineIndex, "__") },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.FormatUnderlined, contentDescription = "Souligné", tint = Color.Gray)
                                    }
                                    if (isIngredientsNote) {
                                        IconButton(
                                            onClick = { onMoveToCourses(lineIndex); selectedLine = -1 },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ShoppingCart,
                                                contentDescription = "Déplacer vers Courses",
                                                tint = Color.Gray
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onDeleteLine(lineIndex); selectedLine = -1 },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer la ligne", tint = Color.Gray)
                                    }
                                    IconButton(
                                        onClick = {
                                            selectedLine = -1
                                            onEnterEditAt(lineStarts[lineIndex] + line.length)
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Éditer", tint = Color.Gray)
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
