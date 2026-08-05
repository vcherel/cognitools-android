package com.example.myapp.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as Media3Item

// Where GalleryViewerScreen gets its items from: a normal album, the pinned set (hero first),
// or the Wallet shortcut (the album named "Wallet", opened straight to its first item).
sealed interface ViewerSource {
    data class Album(val bucketId: Long) : ViewerSource
    data object Pinned : ViewerSource
    data object Wallet : ViewerSource
}

const val WALLET_ALBUM_NAME = "Wallet"

@Composable
fun GalleryViewerScreen(
    source: ViewerSource,
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
    val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
    // Null until the Room Flow's first real emission arrives: collectAsState's own initial value
    // (an empty list) is indistinguishable from "really no pins", which would otherwise bounce a
    // Pinned-source viewer straight back out before the actual pins ever get read.
    val pinnedRowsState by pinDao.observePinned().collectAsState(initial = null)
    val pinnedRows = pinnedRowsState ?: emptyList()
    val pinnedIds = remember(pinnedRows) { pinnedRows.map { it.mediaItemId }.toSet() }
    val heroId = remember(pinnedRows) { orderedPinnedIds(pinnedRows).firstOrNull() }

    LaunchedEffect(source, refreshVersion, pinnedRowsState) {
        if (source is ViewerSource.Pinned && pinnedRowsState == null) return@LaunchedEffect
        items = when (source) {
            is ViewerSource.Album -> withContext(Dispatchers.IO) { queryMediaItems(context, bucketId = source.bucketId) }
            ViewerSource.Pinned -> resolvedPinnedMediaItems(context, pinnedRows)
            ViewerSource.Wallet -> withContext(Dispatchers.IO) {
                val wallet = queryAlbums(context).firstOrNull { it.name.equals(WALLET_ALBUM_NAME, ignoreCase = true) }
                if (wallet != null) queryMediaItems(context, bucketId = wallet.bucketId) else emptyList()
            }
        }
        loaded = true
    }

    // Several things independently decide "nothing left to show here, leave": the trash/move/unpin
    // handlers below splice their item out locally and go straight back, and the resulting
    // refreshVersion/pinnedRows bump then also retriggers the loader above with an empty result,
    // which would otherwise ask to leave a second time. Guarding onBack to fire once keeps a
    // straggling second call from popping past the screen it already returned to.
    var hasLeft by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!hasLeft) {
            hasLeft = true
            onBack()
        }
    }

    BackHandler { leave() }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (items.isEmpty()) {
        // Everything in this album was deleted or moved away, or every pin was removed: nothing
        // left to show.
        LaunchedEffect(Unit) { leave() }
        return
    }

    val initialIndex = remember(source) {
        if (source is ViewerSource.Pinned || source is ViewerSource.Wallet) 0
        else items.indexOfFirst { it.id == initialItemId }.coerceAtLeast(0)
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
                            if (dismissOffset.value > 220f) leave()
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
                key = { page -> items[page].id },
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
                            onChromeVisibleChange = { chromeVisible = it },
                            onZoomChanged = { isZoomed = it }
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
                onBack = leave,
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
                isPinned = currentItem.id in pinnedIds,
                isHero = currentItem.id == heroId,
                onRename = { showRenameDialog = true },
                onMove = { showMoveDialog = true },
                onCrop = { onCrop(currentItem.id) },
                onTrim = { onTrim(currentItem.id) },
                onTogglePin = {
                    val id = currentItem.id
                    val wasPinned = id in pinnedIds
                    scope.launch {
                        if (wasPinned) {
                            unpinItem(context, id)
                            // Browsing the pinned pager itself: splice the now unpinned item out
                            // locally right away instead of waiting on the Room Flow update, same
                            // reasoning as the delete flow below.
                            if (source is ViewerSource.Pinned) {
                                val removedIndex = items.indexOfFirst { it.id == id }
                                val remaining = items.filterNot { it.id == id }
                                items = remaining
                                if (remaining.isEmpty()) {
                                    leave()
                                } else {
                                    pagerState.scrollToPage(removedIndex.coerceIn(0, remaining.size - 1))
                                }
                            }
                        } else {
                            pinItems(context, listOf(id))
                        }
                    }
                },
                onSetHero = {
                    val id = currentItem.id
                    scope.launch {
                        setHero(context, id)
                        AppSnackbar.show("Photo mise en avant")
                    }
                },
                onShare = { showShareDialog = true },
                onDelete = {
                    // No confirmation: the item goes to the trash, the pager moves on to the next
                    // one, and the snackbar offers the undo. The item is spliced out of the local
                    // list and the pager explicitly moved right away, instead of waiting on the
                    // MediaStore requery triggered by GalleryRefresh: that one lands late enough
                    // that the pager was snapping back to the album grid in the meantime.
                    val toTrash = listOf(currentItem)
                    val deletedItem = currentItem
                    val deletedIndex = items.indexOfFirst { it.id == currentItem.id }
                    scope.launch {
                        if (performTrashBatch(context, toTrash, requestConsent)) {
                            val remaining = items.filterNot { it.id == currentItem.id }
                            items = remaining
                            if (remaining.isEmpty()) {
                                leave()
                            } else {
                                pagerState.scrollToPage(deletedIndex.coerceIn(0, remaining.size - 1))
                            }
                            GalleryRefresh.bump()
                            showTrashedSnackbar(
                                context, toTrash, requestConsent,
                                // Undo puts the item back where it was and returns the pager to it,
                                // instead of leaving the view on whatever it advanced to.
                                onRestored = {
                                    if (!hasLeft) {
                                        val restoreIndex = deletedIndex.coerceIn(0, items.size)
                                        items = items.toMutableList().apply { add(restoreIndex, deletedItem) }
                                        pagerState.scrollToPage(restoreIndex)
                                    }
                                }
                            )
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
            currentBucketId = (source as? ViewerSource.Album)?.bucketId ?: -1L,
            onDismiss = { showMoveDialog = false },
            onConfirm = { targetRelativePath ->
                showMoveDialog = false
                scope.launch {
                    val ok = performMove(context, currentItem, targetRelativePath, requestConsent)
                    if (ok) {
                        GalleryRefresh.bump()
                        leave()
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
    onChromeVisibleChange: (Boolean) -> Unit,
    onZoomChanged: (Boolean) -> Unit
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

    // Locking the phone stops the activity but doesn't stop ExoPlayer on its own,
    // so a video would keep playing audio behind the lock screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    // Pinch to zoom into the current frame, playing or paused. Only an actual pinch (two pointers)
    // or a pan while already zoomed in claims the gesture, exactly like the photo viewer, so a plain
    // single-finger tap is left unconsumed and still reaches the player's own controller underneath
    // (play/pause, seek, show/hide controls).
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    AndroidView(
        factory = { playerView },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uri) {
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
private fun ViewerActionBar(
    item: MediaItem,
    isPinned: Boolean,
    isHero: Boolean,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCrop: () -> Unit,
    onTrim: () -> Unit,
    onTogglePin: () -> Unit,
    onSetHero: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionIcon(Icons.Default.Edit, "Renommer", onRename)
        ActionIcon(Icons.AutoMirrored.Filled.DriveFileMove, "Déplacer", onMove)
        Box {
            ActionIcon(Icons.Default.MoreVert, "Plus") { showMoreMenu = true }
            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Partager") },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = { showMoreMenu = false; onShare() }
                )
                DropdownMenuItem(
                    text = { Text(if (isPinned) "Désépingler" else "Épingler") },
                    leadingIcon = {
                        Icon(if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin, contentDescription = null)
                    },
                    onClick = { showMoreMenu = false; onTogglePin() }
                )
                if (isPinned && !isHero) {
                    DropdownMenuItem(
                        text = { Text("Mettre en avant") },
                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                        onClick = { showMoreMenu = false; onSetHero() }
                    )
                }
                if (item.type == MediaType.IMAGE) {
                    DropdownMenuItem(
                        text = { Text("Rogner") },
                        leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null) },
                        onClick = { showMoreMenu = false; onCrop() }
                    )
                }
                if (item.type == MediaType.VIDEO) {
                    DropdownMenuItem(
                        text = { Text("Durée") },
                        leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                        onClick = { showMoreMenu = false; onTrim() }
                    )
                }
            }
        }
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
