package com.example.myapp.podcasts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlin.math.roundToInt

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
    val downloadedKeys by repo.downloadedKeys.collectAsState()
    val downloadProgress by repo.downloadProgress.collectAsState()
    val sleepTimerEndAt by repo.sleepTimerEndAt.collectAsState()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerRemainingMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(sleepTimerEndAt) {
        while (sleepTimerEndAt != null) {
            sleepTimerRemainingMs = (sleepTimerEndAt!! - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(1000)
        }
        sleepTimerRemainingMs = null
    }

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
        Row(modifier = Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
            sleepTimerRemainingMs?.let { remainingMs ->
                // How much of the audio needed to reach the timer's end is already on disk (starting
                // the timer kicks off that download). At 100% the rest plays from the file, so it's
                // safe to switch to airplane mode; below that the stream would cut out.
                val downloadedFraction = when {
                    state.episodeId == null -> 0f
                    repo.isDownloaded(state.episodeId, downloadedKeys) -> 1f
                    else -> downloadProgress[state.episodeId] ?: 0f
                }
                val neededMs = if (durationMs > 0) (positionMs + remainingMs).coerceAtMost(durationMs) else 0L
                val covered = when {
                    downloadedFraction >= 1f -> 1f
                    neededMs <= 0L -> 0f
                    else -> (downloadedFraction * durationMs / neededMs).coerceIn(0f, 1f)
                }
                if (covered >= 1f) {
                    Icon(
                        Icons.Filled.DownloadDone,
                        contentDescription = "Téléchargé jusqu'à la fin de la minuterie, mode avion possible",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { covered },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "${(covered * 100).roundToInt()} %",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    "${remainingMs / 60_000L + 1} min",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { showSleepTimerDialog = true }) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = "Minuterie de veille",
                    tint = if (sleepTimerRemainingMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { repo.stopAll(); goHome() }) {
                Icon(Icons.Filled.Stop, contentDescription = "Tout arrêter")
            }
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    val id = state.episodeId ?: return@clickable
                    scope.launch { if (isSeen) repo.markUnseen(id) else repo.markSeen(id) }
                }
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isSeen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (isSeen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (isSeen) "Écouté" else "Marquer comme écouté",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSeen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            isRunning = sleepTimerEndAt != null,
            onDismiss = { showSleepTimerDialog = false },
            onStart = { minutes -> repo.startSleepTimer(minutes); showSleepTimerDialog = false },
            onCancel = { repo.cancelSleepTimer(); showSleepTimerDialog = false }
        )
    }
}

private val SLEEP_TIMER_PRESETS_MIN = listOf(5, 10, 15, 20, 30, 45, 60)

/** Minute presets (20 selected by default) plus a custom field; "Arrêter la minuterie" only shows while one is running. */
@Composable
private fun SleepTimerDialog(isRunning: Boolean, onDismiss: () -> Unit, onStart: (Int) -> Unit, onCancel: () -> Unit) {
    var minutes by remember { mutableStateOf(20) }
    var customText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minuterie de veille") },
        text = {
            Column {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SLEEP_TIMER_PRESETS_MIN.forEach { preset ->
                        val selected = customText.isBlank() && minutes == preset
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { minutes = preset; customText = "" }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "$preset min",
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Durée personnalisée (min)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(customText.toIntOrNull()?.takeIf { it > 0 } ?: minutes) }) {
                Text("Démarrer")
            }
        },
        dismissButton = {
            Row {
                if (isRunning) {
                    TextButton(onClick = onCancel) { Text("Arrêter") }
                }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        }
    )
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
