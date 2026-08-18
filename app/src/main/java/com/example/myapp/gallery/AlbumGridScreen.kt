package com.example.myapp.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GalleryAlbumGridScreen(bucketId: Long, onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current
    val gridState = rememberLazyGridState()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var albumName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // A plain clickable still fires on release however long the press was, so a long press would
    // select a thumbnail and the release would immediately toggle it back off. This swallows that
    // one click. Thumbnails read it on click, the long press gesture arms it.
    val suppressClick = remember { mutableStateOf(false) }
    val refreshVersion by GalleryRefresh.version.collectAsState()

    LaunchedEffect(bucketId, refreshVersion) {
        val result = withContext(Dispatchers.IO) { queryMediaItems(context, bucketId = bucketId) }
        items = result
        result.firstOrNull()?.let { albumName = it.bucketName }
        // Drop any selection pointing at items that no longer exist after a refresh.
        selectedIds = selectedIds intersect result.map { it.id }.toSet()
    }

    val selectionMode = selectedIds.isNotEmpty()

    BackHandler {
        if (selectionMode) selectedIds = emptySet() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionTopBar(
                count = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onMove = { showMoveDialog = true },
                onPin = {
                    val toPin = selectedIds.toList()
                    scope.launch {
                        pinItems(context, toPin)
                        selectedIds = emptySet()
                        AppSnackbar.show(
                            if (toPin.size > 1) "${toPin.size} photos épinglées" else "Photo épinglée"
                        )
                    }
                },
                onDelete = {
                    // Straight to the trash, no confirmation: the snackbar undo and the trash
                    // itself are the safety net.
                    val toTrash = (items ?: emptyList()).filter { it.id in selectedIds }
                    scope.launch {
                        if (performTrashBatch(context, toTrash, requestConsent)) {
                            selectedIds = emptySet()
                            GalleryRefresh.bump()
                            showTrashedSnackbar(context, toTrash, requestConsent)
                        } else {
                            errorMessage = "Suppression impossible"
                        }
                    }
                }
            )
        } else {
            ScreenTopBar(title = albumName.ifEmpty { "Album" }, onBack = onBack, modifier = Modifier.padding(16.dp))
        }

        val currentItems = items
        when {
            currentItems == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            currentItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Album vide")
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .sweepSelection(
                        gridState = gridState,
                        items = currentItems,
                        selectedIds = { selectedIds },
                        onSelectionChange = { selectedIds = it },
                        suppressClick = suppressClick
                    )
            ) {
                items(currentItems, key = { it.id }) { item ->
                    val selected = item.id in selectedIds
                    SelectableMediaThumbnail(
                        item = item,
                        selected = selected,
                        showSelectionIndicator = selectionMode,
                        onClick = {
                            if (suppressClick.value) {
                                suppressClick.value = false
                                return@SelectableMediaThumbnail
                            }
                            if (selectionMode) {
                                selectedIds = if (selected) selectedIds - item.id else selectedIds + item.id
                            } else {
                                onOpenItem(item.id)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showMoveDialog) {
        MoveDialog(
            currentBucketId = bucketId,
            onDismiss = { showMoveDialog = false },
            onConfirm = { targetRelativePath ->
                showMoveDialog = false
                val toMove = (items ?: emptyList()).filter { it.id in selectedIds }
                scope.launch {
                    val ok = performMoveBatch(context, toMove, targetRelativePath, requestConsent)
                    if (ok) {
                        selectedIds = emptySet()
                        GalleryRefresh.bump()
                    } else {
                        errorMessage = "Déplacement impossible"
                    }
                }
            }
        )
    }

    errorMessage?.let { message ->
        ShowAlertDialog(
            onDismiss = { errorMessage = null },
            title = message,
            onConfirm = { errorMessage = null }
        )
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onMove: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Annuler la sélection")
        }
        Text(
            "$count sélectionné${if (count > 1) "s" else ""}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        IconButton(onClick = onMove) {
            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Déplacer")
        }
        IconButton(onClick = onPin) {
            Icon(Icons.Default.PushPin, contentDescription = "Épingler")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
        }
    }
}

/**
 * Long-press a thumbnail to start selecting, then drag to sweep a range of items into the
 * selection (added on top of whatever was already selected). Applied to the grid itself, so it
 * needs [gridState] to tell which item a finger is over, and [suppressClick] to let the thumbnail
 * underneath swallow the click the long press would otherwise fire.
 */
internal fun Modifier.sweepSelection(
    gridState: LazyGridState,
    items: List<MediaItem>,
    selectedIds: () -> Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    suppressClick: MutableState<Boolean>
): Modifier = pointerInput(items) {
    var anchor: Int? = null
    var baseline: Set<Long> = emptySet()
    detectDragGesturesAfterLongPress(
        onDragStart = { pos ->
            gridState.itemIndexAt(pos)?.let { idx ->
                anchor = idx
                baseline = selectedIds()
                onSelectionChange(baseline + items[idx].id)
                suppressClick.value = true
            }
        },
        onDrag = { change, _ ->
            change.consume()
            val start = anchor ?: return@detectDragGesturesAfterLongPress
            val idx = gridState.itemIndexAt(change.position) ?: return@detectDragGesturesAfterLongPress
            val range = minOf(start, idx)..maxOf(start, idx)
            onSelectionChange(baseline + range.mapNotNull { items.getOrNull(it)?.id })
        },
        // The thumbnail's click is dispatched before these, so disarming here only affects a
        // sweep whose click got cancelled by the drag.
        onDragEnd = { anchor = null; suppressClick.value = false },
        onDragCancel = { anchor = null; suppressClick.value = false }
    )
}

// Index of the grid item under a pointer position (in the grid's own coordinate space), or null
// when the finger is over a gap or outside any item.
internal fun LazyGridState.itemIndexAt(offset: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { info ->
        offset.x >= info.offset.x && offset.x < info.offset.x + info.size.width &&
            offset.y >= info.offset.y && offset.y < info.offset.y + info.size.height
    }?.index
