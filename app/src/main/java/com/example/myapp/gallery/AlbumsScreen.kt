package com.example.myapp.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppSnackbar
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.notes.NoteLock
import com.example.myapp.notes.PinDialog
import com.example.myapp.notes.PinPurpose
import com.example.myapp.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What entering the code is about to do to an album (see the long press and the tap on a lock). */
private enum class LockAction { Open, Lock, Unlock, DeleteContent }

@Composable
fun GalleryAlbumsScreen(
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPinned: () -> Unit,
    onOpenPinnedGrid: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current
    var hasPermission by remember { mutableStateOf(hasReadMediaPermission(context)) }
    var hasAllFiles by remember { mutableStateOf(hasAllFilesAccess()) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var trashCount by remember { mutableStateOf(0) }
    val refreshVersion by GalleryRefresh.version.collectAsState()
    val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
    val pinnedRows by pinDao.observePinned().collectAsState(initial = emptyList())
    var heroItem by remember { mutableStateOf<MediaItem?>(null) }
    var pinnedCount by remember { mutableStateOf(0) }
    val lockedBuckets by remember { GalleryLock.lockedBucketIds(context) }.collectAsState(initial = emptySet())
    // The album the code prompt is about, and what entering it will do.
    var pinRequest by remember { mutableStateOf<Pair<Album, LockAction>?>(null) }
    // The album a "are you sure?" is waiting on, and which action it would carry out. Only ever one
    // of Lock, Unlock and DeleteContent: opening a locked album asks for the code, not a confirmation.
    var pendingConfirm by remember { mutableStateOf<Pair<Album, LockAction>?>(null) }
    // Locking the first album with no code set yet is where the code gets chosen.
    var hasPin by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermission = result.values.all { it } }

    val requestAllFiles = rememberAllFilesAccessRequester { hasAllFiles = hasAllFilesAccess() }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(readMediaPermissions())
        hasPin = NoteLock.hasPin(context)
    }

    LaunchedEffect(hasPermission, refreshVersion) {
        if (hasPermission) {
            albums = withContext(Dispatchers.IO) { queryAlbums(context) }
            trashCount = withContext(Dispatchers.IO) { queryTrashedItems(context).size }
        }
    }

    LaunchedEffect(hasPermission, pinnedRows) {
        if (hasPermission) {
            val resolved = resolvedPinnedMediaItems(context, pinnedRows)
            heroItem = resolved.firstOrNull()
            pinnedCount = resolved.size
        }
    }

    BackHandler { onBack() }

    pendingConfirm?.let { (album, action) ->
        val plural = album.itemCount > 1
        val body = when (action) {
            LockAction.Lock -> "Le dossier ne s'ouvrira plus qu'avec le code des notes."
            LockAction.DeleteContent ->
                "${album.itemCount} élément${if (plural) "s" else ""} " +
                    "${if (plural) "partiront" else "partira"} à la corbeille."
            else -> null
        }
        ShowAlertDialog(
            onDismiss = { pendingConfirm = null },
            title = when (action) {
                LockAction.Lock -> "Verrouiller « ${album.name} » ?"
                LockAction.Unlock -> "Déverrouiller « ${album.name} » ?"
                else -> "Supprimer le contenu de « ${album.name} » ?"
            },
            textContent = if (body == null) null else {
                { Text(body) }
            },
            confirmText = when (action) {
                LockAction.Lock -> "Verrouiller"
                LockAction.Unlock -> "Déverrouiller"
                else -> "Supprimer"
            },
            onCancel = { pendingConfirm = null },
            onConfirm = {
                pendingConfirm = null
                when (action) {
                    // With no code set yet, choosing one is part of locking the first album.
                    LockAction.Lock ->
                        if (hasPin) scope.launch { GalleryLock.setLocked(context, album.bucketId, true) }
                        else pinRequest = album to LockAction.Lock
                    LockAction.Unlock -> pinRequest = album to LockAction.Unlock
                    else -> scope.launch {
                        val toTrash = withContext(Dispatchers.IO) {
                            queryMediaItems(context, bucketId = album.bucketId)
                        }
                        if (!trashAndAnnounce(context, toTrash, requestConsent)) {
                            AppSnackbar.show("Suppression impossible")
                        }
                    }
                }
            }
        )
    }

    pinRequest?.let { (album, action) ->
        PinDialog(
            purpose = if (action == LockAction.Lock && !hasPin) PinPurpose.Create else PinPurpose.Enter,
            onDismiss = { pinRequest = null },
            onSuccess = {
                pinRequest = null
                when (action) {
                    LockAction.Open -> onOpenAlbum(album.bucketId)
                    LockAction.Lock -> scope.launch {
                        hasPin = true
                        GalleryLock.setLocked(context, album.bucketId, true)
                    }
                    LockAction.Unlock -> scope.launch { GalleryLock.setLocked(context, album.bucketId, false) }
                    LockAction.DeleteContent -> pendingConfirm = album to LockAction.DeleteContent
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Galerie", onBack = onBack, modifier = Modifier.padding(16.dp))

        if (hasPermission && !hasAllFiles) {
            AllFilesAccessBanner(onGrant = requestAllFiles)
        }

        heroItem?.let { item ->
            PinnedHeroCard(
                item = item,
                pinnedCount = pinnedCount,
                onClick = onOpenPinned,
                onOpenGrid = onOpenPinnedGrid,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
            )
        }

        val loadedAlbums = albums
        when {
            !hasPermission -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "L'accès aux photos et vidéos est nécessaire pour afficher la galerie.",
                    textAlign = TextAlign.Center
                )
            }
            loadedAlbums == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadedAlbums.isEmpty() && trashCount == 0 -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Aucune photo ou vidéo trouvée")
            }
            else -> {
                // Tapping a locked album asks for the code before opening it, a long press opens the
                // menu where the lock is put on or taken off and where the album is emptied.
                val albumCard: @Composable (Album) -> Unit = { album ->
                    val locked = album.bucketId in lockedBuckets
                    AlbumCard(
                        album = album,
                        locked = locked,
                        onClick = {
                            if (locked) pinRequest = album to LockAction.Open else onOpenAlbum(album.bucketId)
                        },
                        onToggleLock = {
                            pendingConfirm = album to if (locked) LockAction.Unlock else LockAction.Lock
                        },
                        // Emptying a locked album goes through the code too, like opening it does.
                        onDeleteContent = {
                            if (locked) pinRequest = album to LockAction.DeleteContent
                            else pendingConfirm = album to LockAction.DeleteContent
                        }
                    )
                }
                val walletAlbum = loadedAlbums.firstOrNull { it.name.equals(WALLET_ALBUM_NAME, ignoreCase = true) }
                val regularAlbums = if (walletAlbum != null) loadedAlbums - walletAlbum else loadedAlbums
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(regularAlbums, key = { it.bucketId }) { album -> albumCard(album) }
                    // Wallet next to last, then trash last: neither should push the regular albums down.
                    if (walletAlbum != null) {
                        item(key = "wallet") { albumCard(walletAlbum) }
                    }
                    if (trashCount > 0) {
                        item(key = "trash") { TrashCard(itemCount = trashCount, onClick = onOpenTrash) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllFilesAccessBanner(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
    ) {
        Text(
            "Autorise l'accès complet aux fichiers pour supprimer et déplacer tes photos sans " +
                "la confirmation Android à chaque fois.",
            style = MaterialTheme.typography.bodyMedium
        )
        MyButton(
            text = "Autoriser",
            onClick = onGrant,
            height = 56.dp,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// Sits where an album cover would, first in the grid, and only while the trash holds something.
@Composable
private fun TrashCard(itemCount: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Corbeille",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "$itemCount élément${plural(itemCount)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

// The big hero picture at the top: the first pinned picture (or whichever was manually promoted),
// sized to be quickly tappable without dominating the album list below it.
@Composable
private fun PinnedHeroCard(
    item: MediaItem,
    pinnedCount: Int,
    onClick: () -> Unit,
    onOpenGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        GalleryAsyncImage(
            uri = item.uri,
            dateModified = item.dateModified,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (pinnedCount > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(pinnedCount.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        // The card itself opens the viewer; this opens the whole pinned set as a grid.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .clickable(onClick = onOpenGrid)
                .padding(8.dp)
        ) {
            Icon(Icons.Default.GridView, contentDescription = "Voir les épinglées en grille", tint = Color.White)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: Album,
    locked: Boolean,
    onClick: () -> Unit,
    onToggleLock: () -> Unit,
    onDeleteContent: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (locked) "Déverrouiller" else "Verrouiller") },
                leadingIcon = {
                    Icon(if (locked) Icons.Default.LockOpen else Icons.Default.Lock, contentDescription = null)
                },
                onClick = { menuOpen = false; onToggleLock() }
            )
            DropdownMenuItem(
                text = { Text("Supprimer le contenu") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onDeleteContent() }
            )
        }
        // A locked album shows no cover at all: the thumbnail alone would give away what is inside.
        if (locked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            GalleryAsyncImage(
                uri = album.coverUri,
                dateModified = album.coverDateModified,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            if (locked) "Verrouillé" else "${album.itemCount} élément${plural(album.itemCount)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}
