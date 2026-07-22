package com.example.myapp.gallery

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.DisposableEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as Media3Item

@Composable
fun GalleryViewerScreen(
    bucketId: Long,
    initialItemId: Long,
    onBack: () -> Unit,
    onCrop: (Long) -> Unit,
    onTrim: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = rememberIntentSenderRequester()
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val refreshVersion by GalleryRefresh.version.collectAsState()

    LaunchedEffect(bucketId, refreshVersion) {
        items = withContext(Dispatchers.IO) { queryMediaItems(context, bucketId = bucketId) }
        loaded = true
    }

    BackHandler { onBack() }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (items.isEmpty()) {
        // Everything in this album was deleted or moved away: nothing left to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val initialIndex = remember(bucketId) {
        items.indexOfFirst { it.id == initialItemId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    var isZoomed by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentItem = items.getOrNull(pagerState.currentPage.coerceIn(items.indices)) ?: items.first()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = currentItem.displayName,
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isZoomed,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            val item = items[page]
            when (item.type) {
                MediaType.IMAGE -> ZoomableImage(item = item, onZoomChanged = { isZoomed = it })
                MediaType.VIDEO -> if (page == pagerState.currentPage) {
                    VideoPlayer(uri = item.uri)
                } else {
                    GalleryAsyncImage(
                        uri = item.uri,
                        dateModified = item.dateModified,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        ViewerActionBar(
            item = currentItem,
            onRename = { showRenameDialog = true },
            onMove = { showMoveDialog = true },
            onCrop = { onCrop(currentItem.id) },
            onTrim = { onTrim(currentItem.id) },
            onDelete = { showDeleteDialog = true }
        )
    }

    if (showRenameDialog) {
        RenameDialog(
            item = currentItem,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                scope.launch {
                    val ok = performRename(context, currentItem, newName, requestConsent)
                    if (ok) GalleryRefresh.bump() else errorMessage = "Renommage impossible"
                }
            }
        )
    }

    if (showMoveDialog) {
        MoveDialog(
            currentBucketId = bucketId,
            onDismiss = { showMoveDialog = false },
            onConfirm = { targetRelativePath ->
                showMoveDialog = false
                scope.launch {
                    val ok = performMove(context, currentItem, targetRelativePath, requestConsent)
                    if (ok) {
                        GalleryRefresh.bump()
                        onBack()
                    } else {
                        errorMessage = "Déplacement impossible"
                    }
                }
            }
        )
    }

    ShowAlertDialog(
        show = showDeleteDialog,
        onDismiss = { showDeleteDialog = false },
        title = "Supprimer ce fichier ?",
        onCancel = { showDeleteDialog = false },
        onConfirm = {
            showDeleteDialog = false
            scope.launch {
                val ok = performDelete(context, currentItem, requestConsent)
                if (ok) {
                    GalleryRefresh.bump()
                    onBack()
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

@Composable
private fun ZoomableImage(item: MediaItem, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(item.id) { mutableStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    GalleryAsyncImage(
        uri = item.uri,
        dateModified = item.dateModified,
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    offset = if (newScale <= 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    )
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ViewerActionBar(
    item: MediaItem,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCrop: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionIcon(Icons.Default.Edit, "Renommer", onRename)
        ActionIcon(Icons.AutoMirrored.Filled.DriveFileMove, "Déplacer", onMove)
        if (item.type == MediaType.IMAGE) ActionIcon(Icons.Default.Crop, "Rogner", onCrop)
        if (item.type == MediaType.VIDEO) ActionIcon(Icons.Default.ContentCut, "Durée", onTrim)
        ActionIcon(Icons.Default.Delete, "Supprimer", onDelete)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Icon(icon, contentDescription = label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RenameDialog(item: MediaItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val dotIndex = item.displayName.lastIndexOf('.')
    val baseName = if (dotIndex > 0) item.displayName.substring(0, dotIndex) else item.displayName
    val extension = if (dotIndex > 0) item.displayName.substring(dotIndex) else ""
    var text by remember { mutableStateOf(baseName) }

    ShowAlertDialog(
        onDismiss = onDismiss,
        title = "Renommer",
        textContent = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                suffix = { if (extension.isNotEmpty()) Text(extension) }
            )
        },
        onCancel = onDismiss,
        onConfirm = {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) onConfirm("$trimmed$extension")
        }
    )
}

@Composable
private fun MoveDialog(currentBucketId: Long, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var newFolderName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        albums = withContext(Dispatchers.IO) {
            queryAlbums(context).filter { it.bucketId != currentBucketId }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Déplacer vers", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("Nouveau dossier") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (newFolderName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm("Pictures/${newFolderName.trim()}/") }
                            .padding(vertical = 10.dp)
                    ) {
                        Text("Créer et déplacer dans « ${newFolderName.trim()} »")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val currentAlbums = albums
                when {
                    currentAlbums == null -> Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    currentAlbums.isEmpty() -> Text("Aucun autre album", modifier = Modifier.padding(vertical = 12.dp))
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(currentAlbums, key = { it.bucketId }) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onConfirm(album.relativePath) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(album.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Annuler") }
                }
            }
        }
    }
}
