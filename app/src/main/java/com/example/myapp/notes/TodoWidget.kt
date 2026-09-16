package com.example.myapp.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** Pinned preview of the "Todo list" note: only the lines before its first separator, checkboxes toggle in place. */
@Composable
internal fun TodoWidgetCard(
    note: Note,
    onNavigate: () -> Unit,
    onToggleLine: (Int) -> Unit,
    onDeleteLine: (Int) -> Unit,
    /** Inserts a blank item at line [insertAt]; the editor then opens with the caret at [caretOffset]. */
    onAddItem: (insertAt: Int, caretOffset: Int) -> Unit
) {
    val (title, _) = remember(note) { noteTitleAndPreview(note) }
    val contentLines = remember(note.content) { note.content.split("\n") }
    // When the title field is blank, noteTitleAndPreview falls back to the first
    // content line; skip that line below so it isn't shown twice.
    val titleLineIndex = remember(note, contentLines) {
        if (note.title.isBlank()) contentLines.indexOfFirst { it.isNotBlank() && !it.isSeparatorLine() }
        else -1
    }
    val bodyLines = remember(contentLines, titleLineIndex) {
        contentLines.withIndex()
            .takeWhile { !it.value.isSeparatorLine() }
            .filter { it.index != titleLineIndex && it.value.isNotBlank() }
    }

    // The new item goes at the start of the active block (right under an inline title) or at its
    // end, above the first separator.
    fun addItem(atTop: Boolean) {
        val insertAt = if (atTop) {
            if (note.title.isBlank()) titleLineIndex + 1 else 0
        } else {
            contentLines.indexOfFirst { it.isSeparatorLine() }.let { if (it == -1) contentLines.size else it }
        }
        val caretOffset = contentLines.take(insertAt).sumOf { it.length + 1 } + UNCHECKED_PREFIX.length
        onAddItem(insertAt, caretOffset)
    }

    NoteCard(note = note, onClick = onNavigate) {
        if (note.locked) {
            LockedNoteTitle(note)
            return@NoteCard
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        // One add row at each end: a long list otherwise means scrolling to the bottom for the
        // item that belongs first. An empty block only needs the one below.
        if (bodyLines.isNotEmpty()) AddItemRow(onClick = { addItem(true) })
        bodyLines.forEach { (index, line) ->
            if (line.isCheckboxLine()) {
                val checked = line.isCheckedLine()
                // Same as the editor: a wrapped item keeps its box and its delete
                // button on the first line.
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleLine(index) }
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleLine(index) })
                    Text(
                        line.checkboxText().formatInline(),
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (checked) Color.Gray else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 14.dp)
                    )
                    DeleteLineButton(
                        onClick = { onDeleteLine(index) },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        line.formatInline(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    )
                    DeleteLineButton(onClick = { onDeleteLine(index) })
                }
            }
        }
        AddItemRow(onClick = { addItem(false) })
    }
}

@Composable
private fun AddItemRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Gray)
        }
        Text(
            "Nouvel élément",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
    }
}

/** Small trailing "x" to remove a single line of the Todo widget, checked or not. */
@Composable
private fun DeleteLineButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Supprimer la ligne",
            modifier = Modifier.size(18.dp),
            tint = Color.Gray
        )
    }
}
