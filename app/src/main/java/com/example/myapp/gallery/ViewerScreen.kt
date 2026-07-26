package com.example.myapp.gallery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    val requestConsent = LocalMediaConsent.current
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
    var showShareDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentItem = items.getOrNull(pagerState.currentPage.coerceIn(items.indices)) ?: items.first()

    // The photo opens clean (no tools). A single tap toggles the top/bottom bars and, with them,
    // the Android system bars for a true immersive fullscreen view.
    var chromeVisible by remember { mutableStateOf(false) }
    val view = LocalView.current
    // Keep the system bars hidden the whole time the viewer is open, even while the tools are
    // showing, so the filename and action bar sit flush against the screen edges instead of
    // leaving the status/navigation bar heights as empty gaps. The bars still swipe in transiently.
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // Restore the system bars when leaving the viewer.
            val w = view.context.findActivity()?.window ?: return@onDispose
            WindowInsetsControllerCompat(w, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val scrimColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    // When the tools are showing, inset the image by the measured heights of the top/bottom bars so
    // the whole picture stays visible between them instead of being overlaid; back to full screen
    // when the tools hide. Animated so the picture resizes smoothly with the bars fading in/out.
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableStateOf(0) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val topInset by animateDpAsState(
        targetValue = if (chromeVisible) with(density) { topBarHeightPx.toDp() } else 0.dp,
        label = "topInset"
    )
    val bottomInset by animateDpAsState(
        targetValue = if (chromeVisible) with(density) { bottomBarHeightPx.toDp() } else 0.dp,
        label = "bottomInset"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Swipe the current item down (when not zoomed) to dismiss the viewer. The offset both
        // translates and fades the content for feedback; releasing past the threshold goes back,
        // otherwise it springs back to place.
        val dismissOffset = remember { Animatable(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissOffset.value
                    alpha = 1f - (dismissOffset.value / 900f).coerceIn(0f, 0.5f)
                }
                .pointerInput(isZoomed) {
                    if (isZoomed) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dismissOffset.value > 220f) onBack()
                            else scope.launch { dismissOffset.animateTo(0f) }
                        },
                        onDragCancel = { scope.launch { dismissOffset.animateTo(0f) } },
                        onVerticalDrag = { change, delta ->
                            val next = (dismissOffset.value + delta).coerceAtLeast(0f)
                            scope.launch { dismissOffset.snapTo(next) }
                            if (next > 0f) change.consume()
                        }
                    )
                }
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isZoomed,
                modifier = Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset)
            ) { page ->
                val item = items[page]
                when (item.type) {
                    MediaType.IMAGE -> ZoomableImage(
                        item = item,
                        onZoomChanged = { isZoomed = it },
                        onToggleChrome = { chromeVisible = !chromeVisible }
                    )
                    MediaType.VIDEO -> if (page == pagerState.currentPage) {
                        VideoPlayer(
                            uri = item.uri,
                            chromeVisible = chromeVisible,
                            onChromeVisibleChange = { chromeVisible = it }
                        )
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
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ScreenTopBar(
                title = currentItem.displayName,
                onBack = onBack,
                titleStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeightPx = it.height }
                    .background(scrimColor)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp)
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ViewerActionBar(
                item = currentItem,
                onRename = { showRenameDialog = true },
                onMove = { showMoveDialog = true },
                onCrop = { onCrop(currentItem.id) },
                onTrim = { onTrim(currentItem.id) },
                onShare = { showShareDialog = true },
                onDelete = {
                    // No confirmation: the item goes to the trash, the pager moves on to the next
                    // one, and the snackbar offers the undo.
                    val toTrash = listOf(currentItem)
                    scope.launch {
                        if (performTrashBatch(context, toTrash, requestConsent)) {
                            GalleryRefresh.bump()
                            showTrashedSnackbar(context, toTrash, requestConsent)
                        } else {
                            errorMessage = "Suppression impossible"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { bottomBarHeightPx = it.height }
                    .background(scrimColor)
                    .navigationBarsPadding()
            )
        }
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

    if (showShareDialog) {
        ShareDialog(
            item = currentItem,
            onDismiss = { showShareDialog = false },
            onError = { errorMessage = it }
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

// The only apps Valentin ever shares photos/videos to. Restricting to these instead of the
// full system share sheet keeps the picker to a single tap on the app that's actually wanted.
private data class ShareTarget(val packageName: String, val label: String)

private val shareTargets = listOf(
    ShareTarget("com.whatsapp", "WhatsApp"),
    ShareTarget("com.beeper.android", "Beeper"),
    ShareTarget("com.facebook.orca", "Messenger")
)

@Composable
private fun ShareDialog(item: MediaItem, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val installedTargets = remember {
        shareTargets.filter { target ->
            try {
                packageManager.getApplicationInfo(target.packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Partager", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                if (installedTargets.isEmpty()) {
                    Text("Aucune application compatible installée")
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        installedTargets.forEach { target ->
                            ShareTargetIcon(
                                target = target,
                                onClick = {
                                    onDismiss()
                                    try {
                                        shareItemTo(context, item, target.packageName)
                                    } catch (e: ActivityNotFoundException) {
                                        onError("Partage impossible")
                                    }
                                }
                            )
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

@Composable
private fun ShareTargetIcon(target: ShareTarget, onClick: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember(target.packageName) {
        try {
            context.packageManager.getApplicationIcon(target.packageName).toBitmap().asImageBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = target.label, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(target.label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun shareItemTo(context: Context, item: MediaItem, packageName: String) {
    val mimeType = item.mimeType.ifBlank {
        if (item.type == MediaType.VIDEO) "video/*" else "image/*"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        setPackage(packageName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun ZoomableImage(
    item: MediaItem,
    onZoomChanged: (Boolean) -> Unit,
    onToggleChrome: () -> Unit
) {
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
                // Single tap toggles the viewer chrome; double tap zooms to 2.5x centered on the
                // tapped point (or back to fit if already zoomed). Both live in one detector so
                // they don't fight the pinch handler below over consuming the touch.
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { tapPos ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = (tapPos - center) * (1f - target)
                            scale = target
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                // Only claim the gesture when it's an actual pinch (two pointers) or a pan while
                // already zoomed in. Plain taps (no movement) are left unconsumed so the tap
                // detector above can handle them; a single-finger drag at scale 1 is left for the
                // enclosing HorizontalPager to swipe to the next item.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } >= 2
                        if (pinching || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                offset = if (newScale <= 1f) Offset.Zero else offset + pan
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
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
private fun VideoPlayer(
    uri: Uri,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit
) {
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

    // The player's own controller is the only tap target on a video page, so it drives the viewer
    // chrome: showing the transport controls also brings up the filename bar and the action row,
    // and hiding them (tap again, or the controller's auto-hide) takes everything away.
    val onVisibilityChange by rememberUpdatedState(onChromeVisibleChange)
    val playerView = remember(uri) {
        PlayerView(context).apply {
            player = exoPlayer
            // Open clean like a photo does, instead of flashing the controls on load.
            controllerAutoShow = false
            setShowSubtitleButton(false)
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    // Track selection and playback speed are useless on a local clip: drop the
                    // gear every time the controller comes back, since it rebuilds its own row.
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    onVisibilityChange(visibility == View.VISIBLE)
                }
            )
        }
    }
    // Keep the controller in sync when the chrome was toggled elsewhere, e.g. swiping in from a
    // photo that had its bars showing.
    LaunchedEffect(playerView, chromeVisible) {
        if (chromeVisible) playerView.showController() else playerView.hideController()
    }

    AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ViewerActionBar(
    item: MediaItem,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCrop: () -> Unit,
    onTrim: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionIcon(Icons.Default.Edit, "Renommer", onRename)
        ActionIcon(Icons.AutoMirrored.Filled.DriveFileMove, "Déplacer", onMove)
        if (item.type == MediaType.IMAGE) ActionIcon(Icons.Default.Crop, "Rogner", onCrop)
        if (item.type == MediaType.VIDEO) ActionIcon(Icons.Default.ContentCut, "Durée", onTrim)
        ActionIcon(Icons.Default.Share, "Partager", onShare)
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
fun MoveDialog(currentBucketId: Long, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
