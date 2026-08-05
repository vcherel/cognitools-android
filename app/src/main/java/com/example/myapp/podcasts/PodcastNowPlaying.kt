package com.example.myapp.podcasts

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Slim bar pinned at the bottom of the Podcasts tool, tap to expand. */
@Composable
fun PodcastMiniPlayerBar(
    state: PodcastPlayerUiState,
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
            PodcastArt(state.artworkUrl, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.podcastTitle.isNotBlank()) {
                    Text(
                        state.podcastTitle,
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

/** Full player overlay: big artwork, seek bar, transport, and a "mark heard" shortcut. */
@Composable
fun PodcastFullPlayerSheet(
    repo: PodcastRepository,
    state: PodcastPlayerUiState,
    onCollapse: () -> Unit
) {
    BackHandler(enabled = true, onBack = onCollapse)

    val scope = rememberCoroutineScope()
    val goHome = LocalGoHome.current
    val episodes by repo.episodes.collectAsState()
    val isSeen = episodes.firstOrNull { it.id == state.episodeId }?.seen == true

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.episodeId) {
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
        IconButton(
            onClick = { repo.stopAll(); goHome() },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Tout arrêter")
        }

        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PodcastArt(
                state.artworkUrl,
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
                state.podcastTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            IconButton(onClick = {
                val id = state.episodeId ?: return@IconButton
                scope.launch { if (isSeen) repo.markUnseen(id) else repo.markSeen(id) }
            }) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = if (isSeen) "Marquer comme non écouté" else "Marquer comme écouté",
                    tint = if (isSeen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
            val sliderPos = if (scrubbing) scrubValue else positionMs.toFloat().coerceIn(0f, sliderMax)
            Slider(
                value = sliderPos,
                onValueChange = { scrubbing = true; scrubValue = it },
                onValueChangeFinished = { repo.seekTo(scrubValue.toLong()); positionMs = scrubValue.toLong(); scrubbing = false },
                valueRange = 0f..sliderMax
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(if (scrubbing) scrubValue.toLong() else positionMs), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(durationMs), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = { repo.seekBy(-10_000L) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Reculer de 10 s", modifier = Modifier.size(36.dp))
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
                IconButton(onClick = { repo.seekBy(30_000L) }) {
                    Icon(Icons.Filled.Forward30, contentDescription = "Avancer de 30 s", modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** Shared: square podcast/episode artwork, with a neutral placeholder. */
@Composable
fun PodcastArt(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}
