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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.myapp.LocalGoHome
import com.example.myapp.MediaArt
import com.example.myapp.PlayPauseButton
import com.example.myapp.PlayerSeekBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val sleepCacheProgress by repo.sleepTimer.cacheProgress.collectAsState()
    val sleepTimerEndAt by repo.sleepTimer.endAt.collectAsState()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerRemainingMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(sleepTimerEndAt) {
        while (sleepTimerEndAt != null) {
            sleepTimerRemainingMs = (sleepTimerEndAt!! - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(1000)
        }
        sleepTimerRemainingMs = null
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
                SleepCoverageBadge(covered = sleepCacheProgress ?: 0f, remainingMs = remainingMs)
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
            MediaArt(
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
            MarkHeardToggle(
                isSeen = isSeen,
                onToggle = {
                    val id = state.episodeId ?: return@MarkHeardToggle
                    scope.launch { if (isSeen) repo.markUnseen(id) else repo.markSeen(id) }
                }
            )

            Spacer(Modifier.height(16.dp))

            PlayerSeekBar(
                trackKey = state.episodeId,
                isPlaying = state.isPlaying,
                positionMs = { repo.positionMs() },
                durationMs = { repo.durationMs() },
                onSeek = { repo.seekTo(it) }
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = { repo.seekBy(-10_000L) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Reculer de 10 s", modifier = Modifier.size(36.dp))
                }
                PlayPauseButton(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onClick = { repo.togglePlay() },
                    iconSize = 56.dp
                )
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
            onStart = { minutes -> repo.sleepTimer.start(minutes); showSleepTimerDialog = false },
            onCancel = { repo.sleepTimer.cancel(); showSleepTimerDialog = false }
        )
    }
}

/**
 * How much of the audio needed to reach the sleep timer's end is already on the phone: starting the
 * timer pre-fetches exactly that stretch, nothing more. At 100% the rest plays from the cache, so
 * it's safe to switch to airplane mode; below that the stream would cut out.
 */
@Composable
private fun SleepCoverageBadge(covered: Float, remainingMs: Long) {
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

@Composable
private fun MarkHeardToggle(isSeen: Boolean, onToggle: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onToggle)
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
}

/** Four presets, few enough to fit one row without scrolling: anything else goes in the custom field. */
private val SLEEP_TIMER_PRESETS_MIN = listOf(10, 20, 30, 45)

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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SLEEP_TIMER_PRESETS_MIN.forEach { preset ->
                        val selected = customText.isBlank() && minutes == preset
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { minutes = preset; customText = "" }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$preset min",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
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
