package com.example.myapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The pieces the two players (Deezer tracks in `deezer/`, podcast episodes in `podcasts/`) draw
 * with. They render the same surfaces from different data, so the layout lives here once and each
 * side passes its own values in.
 */

/** Square cover art from a remote URL, with a neutral placeholder while there is none. */
@Composable
fun MediaArt(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/** m:ss, or h:mm:ss once past the hour (podcast episodes routinely are). */
fun formatPlaybackTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/**
 * One tappable media line: artwork, the title (gaining a playing icon and a tinted background when
 * [isPlaying]), whatever the caller puts under it, and its trailing actions. Every list in the
 * Musique tool is built from this: playlists, followed podcasts, tracks and episodes.
 */
@Composable
fun MediaListRow(
    artworkUrl: String?,
    title: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkSize: Dp = 48.dp,
    cornerSize: Dp = 6.dp,
    contentPadding: Dp = 6.dp,
    titleMaxLines: Int = 1,
    /** Dims the text block only, so the trailing buttons stay fully legible (a heard episode). */
    textAlpha: Float = 1f,
    below: @Composable ColumnScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerSize))
            .background(if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediaArt(artworkUrl, Modifier.size(artworkSize).clip(RoundedCornerShape(cornerSize)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(textAlpha)) {
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
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            below()
        }
        trailing()
    }
}

/** The secondary line under a [MediaListRow]'s title: artist, episode date, track count. */
@Composable
fun MediaRowSubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Slim bar pinned at the bottom of the Musique tool, tap to expand into the full player. */
@Composable
fun MiniPlayerBar(
    artworkUrl: String?,
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    /** A third, tinted line, e.g. which playlist the track is being played from. */
    note: String? = null
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaArt(artworkUrl, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) MediaRowSubtitle(subtitle)
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            PlayPauseButton(isPlaying = isPlaying, isBuffering = isBuffering, onClick = onTogglePlay)
        }
    }
}

/** Play/pause, or a spinner while the player is buffering. [iconSize] 0 keeps the default icon size. */
@Composable
fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit, iconSize: Dp = 0.dp) {
    IconButton(onClick = onClick) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (iconSize > 0.dp) iconSize.times(0.72f) else 24.dp),
                strokeWidth = if (iconSize > 0.dp) 3.dp else 2.dp
            )
        } else {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Lecture",
                modifier = if (iconSize > 0.dp) Modifier.size(iconSize) else Modifier
            )
        }
    }
}

/**
 * The seek bar of a full player: the slider plus the elapsed/total labels under it, and the polling
 * that keeps them moving. Only polls while [isPlaying]: a paused position doesn't move on its own,
 * so there is nothing to tick (and no slider to recompose) twice a second.
 *
 * [trackKey] restarts the polling when the player moves to another track/episode.
 */
@Composable
fun PlayerSeekBar(
    trackKey: Any?,
    isPlaying: Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onSeek: (Long) -> Unit
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(trackKey, isPlaying) {
        if (!scrubbing) {
            position = positionMs()
            duration = durationMs()
        }
        while (isPlaying) {
            delay(500)
            if (!scrubbing) {
                position = positionMs()
                duration = durationMs()
            }
        }
    }

    val sliderMax = duration.coerceAtLeast(1L).toFloat()
    Slider(
        value = if (scrubbing) scrubValue else position.toFloat().coerceIn(0f, sliderMax),
        onValueChange = { scrubbing = true; scrubValue = it },
        onValueChangeFinished = {
            onSeek(scrubValue.toLong())
            position = scrubValue.toLong()
            scrubbing = false
        },
        valueRange = 0f..sliderMax
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            formatPlaybackTime(if (scrubbing) scrubValue.toLong() else position),
            style = MaterialTheme.typography.bodySmall
        )
        Text(formatPlaybackTime(duration), style = MaterialTheme.typography.bodySmall)
    }
}
