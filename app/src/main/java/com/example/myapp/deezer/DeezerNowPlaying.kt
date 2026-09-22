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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.myapp.LocalGoHome
import com.example.myapp.MediaArt
import com.example.myapp.PlayPauseButton
import com.example.myapp.PlayerSeekBar
import com.example.myapp.runIgnoringErrors
import com.example.myapp.notes.appendToDjNote
import kotlinx.coroutines.launch

/**
 * The full track playing right now: the player state only carries what the MediaItem shows, so we ask
 * the repository for the queued track (album, cover md5) and fall back to a thin one if it is gone.
 */
private fun currentTrack(repo: DeezerRepository, state: PlayerUiState): DeezerTrack? {
    val id = state.sngId ?: return null
    return repo.player.trackById(id) ?: DeezerTrack(id, state.title, state.artist, "", 0, null)
}

/**
 * Send a track to the system share sheet. The link goes through song.link (Odesli) so it lands on a
 * page offering Spotify / Apple Music / YouTube / Deezer, rather than forcing a Deezer account on the
 * person receiving it.
 *
 * It is the short `song.link/d/<id>` form, not the `song.link/<full deezer url>` one Odesli also
 * accepts: the second embeds a `https://` inside the link, and a chat app that auto-linkifies the
 * message cuts it there, so what the friend taps is a truncated, dead URL.
 */
private fun shareTrack(context: Context, track: DeezerTrack) {
    val link = "https://song.link/d/${track.sngId}"
    val text = if (track.artist.isBlank()) "${track.title}\n$link" else "${track.title} par ${track.artist}\n$link"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, track.title)
    }
    context.startActivity(Intent.createChooser(send, "Partager le titre"))
}

/** Full player overlay: big cover, seek bar, transport. */
@Composable
fun FullPlayerSheet(
    repo: DeezerRepository,
    state: PlayerUiState,
    sourceLabel: String? = null,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit = {}
) {
    BackHandler(enabled = true, onBack = onCollapse)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val goHome = LocalGoHome.current
    val favoriteIds by repo.favoriteIds.collectAsState()
    val isFav = state.sngId != null && favoriteIds.contains(state.sngId)
    val shuffle by repo.player.shuffleEnabled.collectAsState()

    // Keep the favorites cache warm so the heart reflects the real like-state.
    LaunchedEffect(Unit) { runIgnoringErrors { repo.ensureFavorites() } }

    var showQueue by remember { mutableStateOf(false) }

    // Same for the diamond: it is filled when the track is already in Best pépites, and tapping it
    // takes the track back out only when playing from Best pépites. [pepitesTick] re-reads it after
    // the tap has landed.
    var pepitesTick by remember { mutableIntStateOf(0) }
    var inPepites by remember { mutableStateOf(false) }
    var removesFromPepites by remember { mutableStateOf(false) }
    LaunchedEffect(state.sngId, state.source, pepitesTick) {
        runIgnoringErrors { repo.ensureBestPepitesLoaded() }
        inPepites = state.sngId != null && repo.bestPepitesContains(state.sngId) == true
        removesFromPepites = inPepites && repo.isBestPepites(state.source)
    }

    if (showQueue) QueueSheet(repo = repo, onDismiss = { showQueue = false })

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
            onClick = { repo.player.stopAll(); goHome() },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Tout arrêter")
        }

        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MediaArt(
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = state.artist.isNotBlank()) { onOpenArtist(state.artist) }
            )
            if (sourceLabel != null) {
                Text(
                    "Lecture depuis : $sourceLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Toast.makeText(context, bestPepitesMessage(context, repo, track, state.source), Toast.LENGTH_SHORT).show()
                        pepitesTick++
                    }
                }) {
                    Icon(
                        Icons.Filled.Diamond,
                        contentDescription = if (removesFromPepites) "Retirer de Best pépites" else "Ajouter à Best pépites",
                        tint = if (inPepites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Sends the track to the DJ note, the list of what to download. The diamond does
                // it too (see bestPepitesMessage), this is the way to ask for it alone.
                IconButton(onClick = {
                    val track = currentTrack(repo, state) ?: return@IconButton
                    scope.launch {
                        val added = runCatching { appendToDjNote(context, track.artist, track.title) }.getOrDefault(false)
                        Toast.makeText(context, if (added) "Ajouté à DJ" else "Déjà dans DJ", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = "Ajouter à DJ", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val track = currentTrack(repo, state) ?: return@IconButton
                    shareTrack(context, track)
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Partager", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(24.dp))

            PlayerSeekBar(
                trackKey = state.sngId,
                isPlaying = state.isPlaying,
                positionMs = { repo.player.positionMs() },
                durationMs = { repo.player.durationMs() },
                onSeek = { repo.player.seekTo(it) }
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Shuffle and the queue sit at the ends of the transport row, where the thumb
                // already is. The shuffle icon itself says which mode is on (crossed arrows vs a
                // numbered list): a tint alone doesn't read.
                IconButton(onClick = { scope.launch { repo.player.setShuffle(!shuffle) } }) {
                    Icon(
                        if (shuffle) Icons.Filled.Shuffle else Icons.Filled.FormatListNumbered,
                        contentDescription = if (shuffle) "Lecture aléatoire activée" else "Lecture dans l'ordre",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { repo.player.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(40.dp))
                }
                PlayPauseButton(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onClick = { repo.player.togglePlay() },
                    iconSize = 56.dp
                )
                IconButton(onClick = { repo.player.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        contentDescription = "File d'attente",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
