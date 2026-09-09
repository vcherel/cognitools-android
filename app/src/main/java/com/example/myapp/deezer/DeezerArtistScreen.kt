package com.example.myapp.deezer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.MediaListRow
import com.example.myapp.MediaRowSubtitle
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One artist, opened from the search card, a track row's menu or the full player. Top tracks on top
 * (play in order, shuffle, or act on a single row like anywhere else), the whole discography below,
 * grouped albums / EP / singles, each release opening its own track list.
 *
 * [artistId] and [artistName] come from the caller: a [DeezerArtist] from search already has the id,
 * a track row only has a name and resolves the id with [DeezerRepository.searchArtist] first.
 */
@Composable
fun DeezerArtistScreen(
    repo: DeezerRepository,
    artistId: String,
    artistName: String,
    pictureUrl: String?,
    onBack: () -> Unit,
    onOpenRelease: (DeezerRelease) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val favoriteIds by repo.favoriteIds.collectAsState()
    val playerState by repo.playerState.collectAsState()

    var topTracks by remember { mutableStateOf<List<DeezerTrack>?>(null) }
    var releases by remember { mutableStateOf<List<DeezerRelease>?>(null) }
    var pickerTrack by remember { mutableStateOf<DeezerTrack?>(null) }

    LaunchedEffect(artistId) {
        coroutineScope {
            val topDeferred = async { runCatching { repo.artistTopTracks(artistId) }.getOrDefault(emptyList()) }
            val relDeferred = async { runCatching { repo.artistReleases(artistId, artistName) }.getOrDefault(emptyList()) }
            topTracks = topDeferred.await()
            releases = relDeferred.await()
        }
        runCatching { repo.ensureFavorites() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = artistName, onBack = onBack)

        val tracks = topTracks
        if (tracks == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pictureUrl != null) {
                            AsyncImage(model = pictureUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        artistName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (tracks.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeaderAction(
                            text = "Lire",
                            icon = Icons.Filled.PlayArrow,
                            modifier = Modifier.weight(1f),
                            onClick = { scope.launch { repo.playTracks(tracks, 0, shuffle = false) } }
                        )
                        HeaderAction(
                            text = "Aléatoire",
                            icon = Icons.Filled.Shuffle,
                            modifier = Modifier.weight(1f),
                            onClick = { scope.launch { repo.shuffleTracks(tracks) } }
                        )
                    }
                }
                item { SectionHeader("Titres populaires") }
                itemsIndexed(tracks, key = { _, t -> "top-${t.sngId}" }) { index, track ->
                    TrackRow(
                        track = track,
                        onClick = { scope.launch { repo.playTracks(tracks, index) } },
                        showActions = true,
                        isFavorite = favoriteIds.contains(track.sngId),
                        isPlaying = playerState.hasItem && playerState.sngId == track.sngId,
                        onToggleFavorite = { scope.launch { runCatching { repo.toggleFavorite(track) } } },
                        onAddToQueue = {
                            scope.launch { Toast.makeText(context, addToQueueMessage(repo, track), Toast.LENGTH_SHORT).show() }
                        },
                        onToggleBestPepites = {
                            scope.launch { Toast.makeText(context, toggleBestPepitesMessage(context, repo, track), Toast.LENGTH_SHORT).show() }
                        },
                        onAddToPlaylist = { pickerTrack = track }
                    )
                }
            }

            val grouped = releaseGroups(releases.orEmpty())
            if (grouped.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)); SectionHeader("Discographie") }
                grouped.forEach { (label, group) ->
                    item(key = "grp-$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(group, key = { it.albumId }) { release ->
                        MediaListRow(
                            artworkUrl = release.coverUrl(),
                            title = release.title,
                            isPlaying = false,
                            onClick = { onOpenRelease(release) },
                            below = { if (release.year.isNotBlank()) MediaRowSubtitle(release.year) }
                        )
                    }
                }
            } else if (releases != null && tracks.isEmpty()) {
                item {
                    Text(
                        "Rien trouvé pour cet artiste.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    pickerTrack?.let { track ->
        PlaylistPickerDialog(
            repo = repo,
            onDismiss = { pickerTrack = null },
            onPick = { playlist ->
                pickerTrack = null
                scope.launch { Toast.makeText(context, addToPlaylistMessage(repo, playlist, track), Toast.LENGTH_SHORT).show() }
            }
        )
    }
}

@Composable
private fun HeaderAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

/** Deezer's `record_type` is "album" / "ep" / "single" / "compilation". Grouped in that order,
 *  newest first inside each group (the list already arrives sorted by date). */
private fun releaseGroups(releases: List<DeezerRelease>): List<Pair<String, List<DeezerRelease>>> {
    val order = listOf("album" to "Albums", "ep" to "EP", "single" to "Singles", "compilation" to "Compilations")
    val byType = releases.groupBy { it.recordType.lowercase() }
    val known = order.mapNotNull { (type, label) -> byType[type]?.let { label to it } }
    val other = byType.filterKeys { key -> order.none { it.first == key } }.values.flatten()
    return if (other.isEmpty()) known else known + ("Autres" to other)
}
