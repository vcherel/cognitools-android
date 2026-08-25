package com.example.myapp.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.runtime.DisposableEffect
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Where GalleryViewerScreen gets its items from: a normal album, the pinned set (hero first),
// the Wallet shortcut (the album named "Wallet", opened straight to its first item), or a single
// item on its own (the locked quick view, where nothing around it should be reachable).
sealed interface ViewerSource {
    data class Album(val bucketId: Long) : ViewerSource
    data object Pinned : ViewerSource
    data object Wallet : ViewerSource
    data class Single(val itemId: Long) : ViewerSource
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
            is ViewerSource.Single -> withContext(Dispatchers.IO) {
                listOfNotNull(queryMediaItemById(context, source.itemId))
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
    // One at a time, so the tools bar just names the one it opens instead of juggling four flags.
    var openDialog by remember { mutableStateOf<ViewerDialog?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentItem = items[pagerState.currentPage.coerceIn(items.indices)]

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
            // Put the window back exactly as it was found. Both halves matter: showing the bars
            // again without clearing the transient behaviour leaves them drawn over the content and
            // contributing no insets, which strands every other screen (the whole app takes its
            // navigation bar padding from the one Scaffold in AppNavHost) underneath them.
            val w = view.context.findActivity()?.window ?: return@onDispose
            val controller = WindowInsetsControllerCompat(w, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
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
        // Raw drag deltas arrive on nearly every pointer-move frame; funnel them through a
        // conflated channel into one long-lived collector instead of launching a fresh
        // coroutine per event, which would otherwise allocate a Job on every move.
        val dragChannel = remember { Channel<Float>(Channel.CONFLATED) }
        LaunchedEffect(dragChannel) {
            for (next in dragChannel) dismissOffset.snapTo(next)
        }
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
                            dragChannel.trySend(next)
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
                onRename = { openDialog = ViewerDialog.Rename },
                onMove = { openDialog = ViewerDialog.Move },
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
                onShare = { openDialog = ViewerDialog.Share },
                onShowInfo = { openDialog = ViewerDialog.Info },
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
                        val trashed = trashAndAnnounce(
                            context, toTrash, requestConsent,
                            onTrashed = {
                                val remaining = items.filterNot { it.id == currentItem.id }
                                items = remaining
                                if (remaining.isEmpty()) {
                                    leave()
                                } else {
                                    pagerState.scrollToPage(deletedIndex.coerceIn(0, remaining.size - 1))
                                }
                            },
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
                        if (!trashed) errorMessage = "Suppression impossible"
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

    ViewerDialogs(
        dialog = openDialog,
        item = currentItem,
        source = source,
        onDismiss = { openDialog = null },
        onMoved = leave,
        onError = { errorMessage = it }
    )

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
    onShowInfo: () -> Unit,
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
                    text = { Text("Infos") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = { showMoreMenu = false; onShowInfo() }
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
