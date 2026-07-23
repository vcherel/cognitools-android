package com.example.myapp.deezer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

/** Landing screen: search entry, a Favoris preview row, and a Playlists preview row. */
@Composable
fun DeezerLibraryScreen(
    repo: DeezerRepository,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenPlaylist: (DeezerPlaylist) -> Unit
) {
    val scope = rememberCoroutineScope()
    var favorites by remember { mutableStateOf<List<DeezerTrack>?>(null) }
    var playlists by remember { mutableStateOf<List<DeezerPlaylist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!repo.hasArl()) { showSettings = true; return@LaunchedEffect }
        runCatching { favorites = repo.favorites() }.onFailure { error = it.message }
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

            SectionHeader(title = "Favoris", onSeeAll = onOpenFavorites)
            when (val f = favorites) {
                null -> LoadingRow()
                else -> Column {
                    f.take(5).forEachIndexed { index, track ->
                        TrackRow(track) { scope.launch { repo.playTracks(f, index) } }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Playlists", onSeeAll = null)
            when (val p = playlists) {
                null -> LoadingRow()
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    items(p) { pl -> PlaylistCard(pl) { onOpenPlaylist(pl) } }
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
                    runCatching { favorites = repo.favorites() }.onFailure { error = it.message }
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

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PlaylistCard(playlist: DeezerPlaylist, onClick: () -> Unit) {
    Column(
        Modifier.width(140.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverArt(playlist.coverUrl(), Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(6.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            "${playlist.trackCount} titres",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Shared: one tappable track line. */
@Composable
fun TrackRow(track: DeezerTrack, onClick: () -> Unit) {
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
    }
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
