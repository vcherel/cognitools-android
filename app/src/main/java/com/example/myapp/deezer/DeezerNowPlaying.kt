package com.example.myapp.deezer

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The full track playing right now: the player state only carries what the MediaItem shows, so we ask
 * the repository for the queued track (album, cover md5) and fall back to a thin one if it is gone.
 */
private fun currentTrack(repo: DeezerRepository, state: PlayerUiState): DeezerTrack? {
    val id = state.sngId ?: return null
    return repo.trackById(id) ?: DeezerTrack(id, state.title, state.artist, "", 0, null)
}

/**
 * Send a track to the system share sheet. The link goes through song.link (Odesli) so it lands on a
 * page offering Spotify / Apple Music / YouTube / Deezer, rather than forcing a Deezer account on the
 * person receiving it.
 */
private fun shareTrack(context: Context, track: DeezerTrack) {
    val link = "https://song.link/https://www.deezer.com/track/${track.sngId}"
    val text = if (track.artist.isBlank()) "${track.title}\n$link" else "${track.title} par ${track.artist}\n$link"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, track.title)
    }
    context.startActivity(Intent.createChooser(send, "Partager le titre"))
}

/** Slim bar pinned above nothing (it sits at the bottom of the Deezer tool), tap to expand. */
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArt(state.coverUrl, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.artist.isNotBlank()) {
                    Text(
                        state.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onTogglePlay) {
                if (state.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Lecture"
                    )
                }
            }
        }
    }
}

/** Full player overlay: big cover, seek bar, transport. */
@Composable
fun FullPlayerSheet(
    repo: DeezerRepository,
    state: PlayerUiState,
    onCollapse: () -> Unit
) {
    BackHandler(enabled = true, onBack = onCollapse)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteIds by repo.favoriteIds.collectAsState()
    val isFav = state.sngId != null && favoriteIds.contains(state.sngId)

    // Keep the favorites cache warm so the heart reflects the real like-state.
    LaunchedEffect(Unit) { runCatching { repo.ensureFavorites() } }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    // Poll position while the sheet is open.
    LaunchedEffect(state.sngId) {
        while (true) {
            if (!scrubbing) {
                positionMs = repo.positionMs()
                durationMs = repo.durationMs()
            }
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp)
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Réduire")
        }

        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CoverArt(
                state.coverUrl,
                Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(24.dp))
            Text(
                state.title.ifBlank { "…" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                state.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = {
                    val track = currentTrack(repo, state) ?: return@IconButton
                    scope.launch { runCatching { repo.toggleFavorite(track) } }
                }) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFav) "Retirer des favoris" else "Ajouter aux favoris",
                        tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    val track = currentTrack(repo, state) ?: return@IconButton
                    scope.launch {
                        Toast.makeText(context, addToBestPepitesMessage(repo, track), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Diamond, contentDescription = "Ajouter à Best pépites", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val track = currentTrack(repo, state) ?: return@IconButton
                    shareTrack(context, track)
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Partager", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(24.dp))

            val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
            val sliderPos = if (scrubbing) scrubValue else positionMs.toFloat().coerceIn(0f, sliderMax)
            Slider(
                value = sliderPos,
                onValueChange = { scrubbing = true; scrubValue = it },
                onValueChangeFinished = { repo.seekTo(scrubValue.toLong()); positionMs = scrubValue.toLong(); scrubbing = false },
                valueRange = 0f..sliderMax
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((if (scrubbing) scrubValue.toLong() else positionMs)), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(durationMs), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { repo.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { repo.togglePlay() }) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                IconButton(onClick = { repo.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
