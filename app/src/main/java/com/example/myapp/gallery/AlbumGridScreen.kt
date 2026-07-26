package com.example.myapp.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                    // Long-press a thumbnail to start selecting, then drag to sweep a range of
                    // items into the selection (added on top of whatever was already selected).
                    .pointerInput(currentItems) {
                        var anchor: Int? = null
                        var baseline: Set<Long> = emptySet()
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                gridState.itemIndexAt(pos)?.let { idx ->
                                    anchor = idx
                                    baseline = selectedIds
                                    selectedIds = selectedIds + currentItems[idx].id
                                    suppressClick.value = true
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val start = anchor ?: return@detectDragGesturesAfterLongPress
                                val idx = gridState.itemIndexAt(change.position)
                                    ?: return@detectDragGesturesAfterLongPress
                                val range = minOf(start, idx)..maxOf(start, idx)
                                selectedIds = baseline + range.mapNotNull { currentItems.getOrNull(it)?.id }
                            },
                            // The thumbnail's click is dispatched before these, so disarming here
                            // only affects a sweep whose click got cancelled by the drag.
                            onDragEnd = { anchor = null; suppressClick.value = false },
                            onDragCancel = { anchor = null; suppressClick.value = false }
                        )
                    }
            ) {
                items(currentItems, key = { it.id }) { item ->
                    val selected = item.id in selectedIds
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                if (suppressClick.value) {
                                    suppressClick.value = false
                                    return@clickable
                                }
                                if (selectionMode) {
                                    selectedIds = if (selected) selectedIds - item.id else selectedIds + item.id
                                } else {
                                    onOpenItem(item.id)
                                }
                            }
                    ) {
                        GalleryAsyncImage(
                            uri = item.uri,
                            dateModified = item.dateModified,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (item.type == MediaType.VIDEO) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(28.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (selectionMode) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                )
                            }
                            Icon(
                                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                            )
                        }
                    }
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
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
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
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
        }
    }
}

// Index of the grid item under a pointer position (in the grid's own coordinate space), or null
// when the finger is over a gap or outside any item.
private fun LazyGridState.itemIndexAt(offset: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { info ->
        offset.x >= info.offset.x && offset.x < info.offset.x + info.size.width &&
            offset.y >= info.offset.y && offset.y < info.offset.y + info.size.height
    }?.index
