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
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
    val favorites by repo.favorites.collectAsState()
    var playlists by remember { mutableStateOf<List<DeezerPlaylist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!repo.hasArl()) { showSettings = true; return@LaunchedEffect }
        runCatching { repo.ensureFavorites() }.onFailure { error = it.message }
        runCatching { playlists = repo.playlists() }.onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Deezer", onBack = onBack) {
            Spacer(Modifier.weight(1f))
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
                    runCatching { repo.ensureFavorites(force = true) }.onFailure { error = it.message }
                    runCatching { playlists = repo.playlists() }.onFailure { error = it.message }
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

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** One stacked playlist row: cover + name + count, tap to open, shuffle button on the right. */
@Composable
private fun PlaylistRow(playlist: DeezerPlaylist, onOpen: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(playlist.coverUrl(), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
 * Shared: one tappable track line. Tap plays. When [showActions] is on, inline icons appear on the
 * right: heart (like/unlike, filled when [isFavorite]), diamond (add to Best pépites), a plus (add to
 * any playlist via a picker), and, only if [onRemoveFromPlaylist] is provided, a cross that removes it
 * from the current playlist.
 */
@Composable
fun TrackRow(
    track: DeezerTrack,
    onClick: () -> Unit,
    showActions: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToBestPepites: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(track.coverUrl(), Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            if (onAddToBestPepites != null) {
                IconButton(onClick = onAddToBestPepites, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Diamond, contentDescription = "Ajouter à Best pépites", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onAddToPlaylist != null) {
                IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Ajouter à une playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onRemoveFromPlaylist != null) {
                IconButton(onClick = onRemoveFromPlaylist, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Retirer de la playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

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
    LaunchedEffect(Unit) { runCatching { playlists = repo.playlists() } }

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
