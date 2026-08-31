package com.example.myapp.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.launch

/**
 * Every pinned picture as a grid, the other way into the pinned set: the hero card still opens the
 * viewer straight away, this shows them all at once. Tapping one opens the viewer on it, a long
 * press unpins it with an undo.
 */
@Composable
fun GalleryPinnedGridScreen(onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
    val pinnedRows by pinDao.observePinned().collectAsState(initial = emptyList())
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    val refreshVersion by GalleryRefresh.version.collectAsState()

    LaunchedEffect(pinnedRows, refreshVersion) {
        items = resolvedPinnedMediaItems(context, pinnedRows)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Épinglées", onBack = onBack, modifier = Modifier.padding(16.dp))

        val currentItems = items
        when {
            currentItems == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            currentItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune photo épinglée", style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(currentItems, key = { it.id }) { item ->
                    SelectableMediaThumbnail(
                        item = item,
                        selected = false,
                        showSelectionIndicator = false,
                        onClick = { onOpenItem(item.id) },
                        onLongClick = {
                            scope.launch {
                                unpinItem(context, item.id)
                                AppSnackbar.show(
                                    message = "Photo détachée",
                                    actionLabel = "Annuler",
                                    onAction = { pinItems(context, listOf(item.id)) }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
