package com.example.myapp.gallery

import android.content.Context
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photos and videos waiting in the system trash. Tapping a thumbnail selects it (there is no
 * viewer here); the selection can then be restored or deleted for good. Anything left alone is
 * removed by Android [TRASH_RETENTION_DAYS] days after it was trashed.
 */
@Composable
fun GalleryTrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current
    val gridState = rememberLazyGridState()
    // Swallows the click the sweep's long press would otherwise fire on the thumbnail under it,
    // see sweepSelection.
    val suppressClick = remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showPurgeDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val refreshVersion by GalleryRefresh.version.collectAsState()

    LaunchedEffect(refreshVersion) {
        val result = withContext(Dispatchers.IO) { queryTrashedItems(context) }
        items = result
        selectedIds = selectedIds intersect result.map { it.id }.toSet()
    }

    val selectionMode = selectedIds.isNotEmpty()
    val currentItems = items

    BackHandler {
        if (selectionMode) selectedIds = emptySet() else onBack()
    }

    fun selected(): List<MediaItem> = currentItems.orEmpty().filter { it.id in selectedIds }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedIds = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = "Annuler la sélection")
                }
                Text(
                    "${selectedIds.size} sélectionné${if (selectedIds.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                IconButton(onClick = {
                    val toRestore = selected()
                    scope.launch {
                        if (performRestoreBatch(context, toRestore, requestConsent)) {
                            selectedIds = emptySet()
                            GalleryRefresh.bump()
                            AppSnackbar.show(restoredMessage(toRestore.size))
                        } else {
                            errorMessage = "Restauration impossible"
                        }
                    }
                }) {
                    Icon(Icons.Default.Restore, contentDescription = "Restaurer")
                }
                IconButton(onClick = { showPurgeDialog = true }) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Supprimer définitivement")
                }
            }
        } else {
            ScreenTopBar(
                title = "Corbeille",
                onBack = onBack,
                modifier = Modifier.padding(16.dp),
                titleSuffix = {
                    if (!currentItems.isNullOrEmpty()) {
                        Box(modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            selectedIds = currentItems.map { it.id }.toSet()
                            showPurgeDialog = true
                        }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Vider la corbeille")
                        }
                    }
                }
            )
        }

        if (!currentItems.isNullOrEmpty()) {
            Text(
                "Supprimés définitivement après $TRASH_RETENTION_DAYS jours.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
            )
        }

        when {
            currentItems == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            currentItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Corbeille vide")
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
                    val isSelected = item.id in selectedIds
                    SelectableMediaThumbnail(
                        item = item,
                        selected = isSelected,
                        showSelectionIndicator = true,
                        onClick = {
                            if (suppressClick.value) {
                                suppressClick.value = false
                            } else {
                                selectedIds =
                                    if (isSelected) selectedIds - item.id else selectedIds + item.id
                            }
                        }
                    ) {
                        remainingDays(item.dateExpires)?.let { days ->
                            Text(
                                "${days}j",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.45f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPurgeDialog) ShowAlertDialog(
        onDismiss = { showPurgeDialog = false },
        title = if (selectedIds.size > 1) {
            "Supprimer définitivement ${selectedIds.size} fichiers ?"
        } else {
            "Supprimer définitivement ce fichier ?"
        },
        onCancel = { showPurgeDialog = false },
        onConfirm = {
            showPurgeDialog = false
            val toDelete = selected()
            scope.launch {
                if (performDeleteBatch(context, toDelete, requestConsent)) {
                    selectedIds = emptySet()
                    GalleryRefresh.bump()
                } else {
                    errorMessage = "Suppression impossible"
                }
            }
        }
    )

    errorMessage?.let { message ->
        ShowAlertDialog(
            onDismiss = { errorMessage = null },
            title = message,
            onConfirm = { errorMessage = null }
        )
    }
}

/**
 * Trashes [items] and tells the user, refreshing every gallery screen in between. The one way in:
 * a call site that trashed on its own could forget the refresh, and then the grid it came from
 * would keep showing what is no longer there. [onTrashed] runs after the write and before the
 * refresh, for a screen that has its own list to fix up first. Returns false, having said nothing,
 * when the write did not go through.
 */
suspend fun trashAndAnnounce(
    context: Context,
    items: List<MediaItem>,
    requestConsent: suspend (IntentSender) -> Boolean,
    onTrashed: suspend () -> Unit = {},
    onRestored: suspend () -> Unit = {}
): Boolean {
    if (!performTrashBatch(context, items, requestConsent)) return false
    onTrashed()
    GalleryRefresh.bump()
    showTrashedSnackbar(context, items, requestConsent, onRestored)
    return true
}

/**
 * Confirms a delete that just moved items to the trash, with the undo that puts them back. Below
 * API 30 the delete was permanent, so there is nothing to undo and the message says so.
 */
private fun showTrashedSnackbar(
    context: Context,
    items: List<MediaItem>,
    requestConsent: suspend (IntentSender) -> Boolean,
    onRestored: suspend () -> Unit = {}
) {
    if (items.isEmpty()) return
    val plural = items.size > 1
    if (!trashSupported()) {
        AppSnackbar.show(if (plural) "${items.size} fichiers supprimés" else "Fichier supprimé")
        return
    }
    AppSnackbar.show(
        message = if (plural) "${items.size} fichiers dans la corbeille" else "Fichier dans la corbeille",
        actionLabel = "Annuler",
        onAction = {
            if (performRestoreBatch(context, items, requestConsent)) {
                GalleryRefresh.bump()
                onRestored()
            }
        }
    )
}

/** Days left before Android deletes a trashed item, at least 1, or null if it has no expiry. */
private fun remainingDays(dateExpires: Long): Int? {
    if (dateExpires <= 0) return null
    val millisLeft = dateExpires * 1000 - System.currentTimeMillis()
    return ((millisLeft + 86_400_000L - 1) / 86_400_000L).toInt().coerceAtLeast(1)
}

private fun restoredMessage(count: Int): String =
    if (count > 1) "$count fichiers restaurés" else "Fichier restauré"
