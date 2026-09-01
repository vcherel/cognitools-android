package com.example.myapp.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.launch

/**
 * Every pinned picture as a grid, the other way into the pinned set: the hero card still opens the
 * viewer straight away, this shows them all at once. Tapping one opens the viewer on it, a long
 * press starts the same multi-selection as an album grid (drag to sweep a range), whose bar
 * unpins instead of pinning.
 */
@Composable
fun GalleryPinnedGridScreen(onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current
    val gridState = rememberLazyGridState()
    val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
    val pinnedRows by pinDao.observePinned().collectAsState(initial = emptyList())
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Same trick as the album grid: swallow the click the long press would otherwise fire.
    val suppressClick = remember { mutableStateOf(false) }
    val refreshVersion by GalleryRefresh.version.collectAsState()

    LaunchedEffect(pinnedRows, refreshVersion) {
        val resolved = resolvedPinnedMediaItems(context, pinnedRows)
        items = resolved
        selectedIds = selectedIds intersect resolved.map { it.id }.toSet()
    }

    val selectionMode = selectedIds.isNotEmpty()
    val selectedItems = items.orEmpty().filter { it.id in selectedIds }

    BackHandler {
        if (selectionMode) selectedIds = emptySet() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionTopBar(
                count = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onMove = { showMoveDialog = true },
                onShare = { showShareDialog = true },
                pinIcon = Icons.Default.PushPin,
                pinLabel = "Détacher",
                onPin = {
                    val toUnpin = selectedIds.toList()
                    scope.launch {
                        toUnpin.forEach { unpinItem(context, it) }
                        selectedIds = emptySet()
                        AppSnackbar.show(
                            message = if (toUnpin.size > 1) "${toUnpin.size} photos détachées" else "Photo détachée",
                            actionLabel = "Annuler",
                            onAction = { pinItems(context, toUnpin) }
                        )
                    }
                },
                onDelete = {
                    val toTrash = selectedItems
                    scope.launch {
                        val trashed = trashAndAnnounce(
                            context, toTrash, requestConsent,
                            onTrashed = { selectedIds = emptySet() }
                        )
                        if (!trashed) errorMessage = "Suppression impossible"
                    }
                }
            )
        } else {
            ScreenTopBar(title = "Épinglées", onBack = onBack, modifier = Modifier.padding(16.dp))
        }

        val currentItems = items
        when {
            currentItems == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            currentItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune photo épinglée", style = MaterialTheme.typography.bodyMedium)
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

    if (showShareDialog) {
        ShareDialog(
            items = selectedItems,
            onDismiss = { showShareDialog = false },
            onError = { errorMessage = it }
        )
    }

    if (showMoveDialog) {
        MoveDialog(
            currentBucketId = -1L,
            onDismiss = { showMoveDialog = false },
            onConfirm = { targetRelativePath ->
                showMoveDialog = false
                val toMove = selectedItems
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
