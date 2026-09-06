package com.example.myapp.deezer

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapp.MediaListRow
import com.example.myapp.plural
import kotlin.math.roundToInt

// Every row is this tall, which is what makes the drag arithmetic a division: the finger has moved
// past a neighbour once the offset passes half of it.
private val ROW_HEIGHT = 64.dp

/**
 * The playback queue, reorderable and prunable. Edits go straight to the controller (see
 * [DeezerRepository.moveQueueItem]), so the list under the finger is the real queue at every moment
 * rather than a draft applied on dismiss, and playback never restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(repo: DeezerRepository, onDismiss: () -> Unit) {
    val queue by repo.queueState.collectAsState()
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    // The row being dragged, by index, and how far past its resting place the finger is.
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            val remaining = (queue.entries.size - queue.currentIndex - 1).coerceAtLeast(0)
            Text("File d'attente", style = MaterialTheme.typography.titleLarge)
            Text(
                "$remaining titre${plural(remaining)} à suivre",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(queue.entries) { index, entry ->
                    val dragging = index == draggedIndex
                    Row(
                        modifier = Modifier
                            .height(ROW_HEIGHT)
                            .zIndex(if (dragging) 1f else 0f)
                            .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MediaListRow(
                            artworkUrl = entry.coverUrl,
                            title = entry.title,
                            isPlaying = index == queue.currentIndex,
                            onClick = { repo.playQueueIndex(index) },
                            modifier = Modifier
                                .weight(1f)
                                // A track already played stays listed (jumping back to it is the point)
                                // but reads as behind the playhead.
                                .alpha(if (index < queue.currentIndex) 0.5f else 1f),
                            artworkSize = 44.dp,
                            below = {
                                Text(
                                    entry.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailing = {
                                IconButton(onClick = { repo.removeQueueItem(index) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Retirer de la file",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Déplacer",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(28.dp)
                                .pointerInput(index, queue.entries.size) {
                                    detectDragGestures(
                                        onDragStart = { draggedIndex = index; dragOffset = 0f },
                                        onDragEnd = { draggedIndex = -1; dragOffset = 0f },
                                        onDragCancel = { draggedIndex = -1; dragOffset = 0f }
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // Each half-row crossed is one swap applied for real, so the
                                        // list redraws under the finger and the offset resets by a row.
                                        while (dragOffset > rowHeightPx / 2 && draggedIndex < queue.entries.size - 1) {
                                            repo.moveQueueItem(draggedIndex, draggedIndex + 1)
                                            draggedIndex++
                                            dragOffset -= rowHeightPx
                                        }
                                        while (dragOffset < -rowHeightPx / 2 && draggedIndex > 0) {
                                            repo.moveQueueItem(draggedIndex, draggedIndex - 1)
                                            draggedIndex--
                                            dragOffset += rowHeightPx
                                        }
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}
