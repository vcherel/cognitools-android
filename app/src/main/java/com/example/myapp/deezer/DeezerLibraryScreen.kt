package com.example.myapp.deezer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.LocalGoHome
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

/** Landing screen: search entry, a Favoris card (count + shuffle), and a Playlists preview row. */
@Composable
fun DeezerLibraryScreen(
    repo: DeezerRepository,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlaylist: (DeezerPlaylist) -> Unit
) {
    val scope = rememberCoroutineScope()
    val goHome = LocalGoHome.current
    val favorites by repo.favorites.collectAsState()
    val playlists by repo.playlists.collectAsState()
    val offlineState by repo.offline.state.collectAsState()
    val playerState by repo.playerState.collectAsState()
    val playingPlaylistId = (playerState.source as? TrackSource.Playlist)?.id
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var downloadedCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (!repo.hasArl()) { showSettings = true; return@LaunchedEffect }
        runCatching { repo.ensureLibrary() }.onFailure { error = it.message }
        // Incremental: after the first run this downloads only what was added to Best pépites since.
        repo.offline.syncInBackground()
        downloadedCount = runCatching { repo.downloadedTracks().size }.getOrDefault(0)
    }
    // Recomputed as the Best pépites sync progresses, so newly finished downloads show up without
    // needing to leave and re-enter the screen.
    LaunchedEffect(offlineState.downloaded, offlineState.syncing) {
        downloadedCount = runCatching { repo.downloadedTracks().size }.getOrDefault(downloadedCount)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Deezer", onBack = onBack) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { repo.stopAll(); goHome() }) {
                Icon(Icons.Filled.Stop, contentDescription = "Tout arrêter")
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Réglages")
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Search entry (a tappable fake field, opens the search screen).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onOpenSearch)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("Rechercher un titre, un artiste…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            error?.let {
                Text("Erreur: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }

            SectionHeader(title = "Favoris", onSeeAll = null)
            FavoritesCard(
                count = favorites?.size,
                onShuffle = { scope.launch { runCatching { repo.shuffleFavorites() }.onFailure { error = it.message } } }
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Playlists", onSeeAll = null)
            when (val p = playlists) {
                null -> LoadingRow()
                else -> Column(Modifier.padding(vertical = 4.dp)) {
                    p.forEach { pl ->
                        PlaylistRow(
                            playlist = pl,
                            isPlaying = pl.id == playingPlaylistId,
                            onOpen = { onOpenPlaylist(pl) },
                            onShuffle = {
                                scope.launch {
                                    runCatching { repo.shufflePlaylist(pl.id) }.onFailure { error = it.message }
                                }
                            }
                        )
                    }
                }
            }

            OfflineStatusLine(offlineState)

            // Least important entry point on the screen: a plain track count is enough here, the
            // Playlists row above already names it and shows its cover.
            if (downloadedCount > 0) {
                Spacer(Modifier.height(24.dp))
                OfflineLibraryCard(
                    count = downloadedCount,
                    onShuffle = { scope.launch { runCatching { repo.shuffleDownloaded() }.onFailure { error = it.message } } }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSettings) {
        DeezerSettingsDialog(
            repo = repo,
            onDismiss = { showSettings = false },
            onSaved = {
                showSettings = false
                scope.launch {
                    error = null
                    runCatching { repo.refreshLibrary() }.onFailure { error = it.message }
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Text(
                "voir tout",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onSeeAll).padding(4.dp)
            )
        }
    }
}

/** Favoris surface: no track list, just the total count and a shuffle-all entry point (whole card taps to shuffle). */
@Composable
private fun FavoritesCard(count: Int?, onShuffle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onShuffle)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(
            count?.let { "$it titres" } ?: "…",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire", tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("Aléatoire", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Every track currently fully downloaded from anywhere (Best pépites plus whatever liked track
 * ordinary playback has cached), ready to shuffle with zero network: the whole card taps to play, same layout as
 * [FavoritesCard]. Hidden until at least one track is down. Deliberately not the pépites diamond: this
 * card is broader than that one playlist.
 */
@Composable
private fun OfflineLibraryCard(count: Int, onShuffle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onShuffle)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.OfflinePin, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(
            "$count titres · disponible hors ligne",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire", tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("Aléatoire", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Best pépites offline mirror, shown only when it has something to say: while tracks are actually
 * downloading, or after a failure. An up to date mirror renders nothing, the sync being automatic.
 */
@Composable
private fun OfflineStatusLine(state: OfflineState) {
    val downloading = state.syncing && state.total > 0 && state.downloaded < state.total
    if (!downloading && state.error == null) return

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (downloading) "Best pépites · ${state.downloaded}/${state.total}" else state.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (downloading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        if (downloading) {
            LinearProgressIndicator(
                progress = { state.downloaded.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp)
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** One stacked playlist row: cover + name + count, tap to open, shuffle button on the right. Tinted when it is the one currently playing. */
@Composable
private fun PlaylistRow(playlist: DeezerPlaylist, isPlaying: Boolean, onOpen: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(playlist.coverUrl(), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "En cours de lecture",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${playlist.trackCount} titres",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Shared: one tappable track line. Tap plays. When [showActions] is on, a heart (like/unlike, filled
 * when [isFavorite]) sits on the right, plus a three dot menu with the rest: add to queue, add to Best
 * pépites, add to a playlist, and, only if [onRemoveFromPlaylist] is provided, remove from the current
 * playlist. [isPlaying] tints the row and adds a playing icon for the track currently loaded in the player.
 */
@Composable
fun TrackRow(
    track: DeezerTrack,
    onClick: () -> Unit,
    showActions: Boolean = false,
    isFavorite: Boolean = false,
    isPlaying: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToBestPepites: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(track.coverUrl(), Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "En cours de lecture",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showActions) {
            IconButton(onClick = { onToggleFavorite?.invoke() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onAddToQueue != null) {
                        DropdownMenuItem(
                            text = { Text("Ajouter à la file d'attente") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToQueue() }
                        )
                    }
                    if (onAddToBestPepites != null) {
                        DropdownMenuItem(
                            text = { Text("Ajouter à Best pépites") },
                            leadingIcon = { Icon(Icons.Filled.Diamond, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToBestPepites() }
                        )
                    }
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Ajouter à une playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToPlaylist() }
                        )
                    }
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Retirer de la playlist") },
                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                            onClick = { menuExpanded = false; onRemoveFromPlaylist() }
                        )
                    }
                }
            }
        }
    }
}

/** Shared: runs the "add to queue" action and returns the toast to show. */
suspend fun addToQueueMessage(repo: DeezerRepository, track: DeezerTrack): String =
    runCatching { repo.addToQueue(track) }.fold(
        onSuccess = { "Ajouté à la file d'attente" },
        onFailure = { "Échec de l'ajout" }
    )

/**
 * Shared: runs the "add to Best pépites" action and returns the toast to show. Every entry point (row
 * action, now playing sheet, notification) reports the same three outcomes, duplicate included.
 */
suspend fun addToBestPepitesMessage(repo: DeezerRepository, track: DeezerTrack): String =
    runCatching { repo.addToBestPepites(track) }.fold(
        onSuccess = {
            when (it) {
                PlaylistAddResult.ADDED -> "Ajouté à Best pépites"
                PlaylistAddResult.DUPLICATE -> "Déjà dans Best pépites"
                PlaylistAddResult.NO_PLAYLIST -> "Playlist Best pépites introuvable"
            }
        },
        onFailure = { "Échec de l'ajout" }
    )

/** Shared: same for the playlist picked in [PlaylistPickerDialog]. */
suspend fun addToPlaylistMessage(repo: DeezerRepository, playlist: DeezerPlaylist, track: DeezerTrack): String =
    runCatching { repo.addToPlaylist(playlist.id, track) }.fold(
        onSuccess = {
            if (it == PlaylistAddResult.DUPLICATE) "Déjà dans ${playlist.title}" else "Ajouté à ${playlist.title}"
        },
        onFailure = { "Échec de l'ajout" }
    )

/**
 * Shared: a dialog listing all of the owner's playlists so a track can be added to any of them.
 * Loads the playlists on open; [onPick] fires with the chosen playlist.
 */
@Composable
fun PlaylistPickerDialog(
    repo: DeezerRepository,
    onDismiss: () -> Unit,
    onPick: (DeezerPlaylist) -> Unit
) {
    var playlists by remember { mutableStateOf<List<DeezerPlaylist>?>(null) }
    LaunchedEffect(Unit) { runCatching { playlists = repo.fetchPlaylists() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Ajouter à une playlist") },
        text = {
            when (val p = playlists) {
                null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(p) { pl ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(pl) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArt(pl.coverUrl(), Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${pl.trackCount} titres",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/** Shared: square cover art from a Deezer image URL, with a neutral placeholder. */
@Composable
fun CoverArt(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}
