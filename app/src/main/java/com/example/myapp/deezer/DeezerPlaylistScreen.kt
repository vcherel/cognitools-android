package com.example.myapp.deezer

import android.content.Context
import android.content.Intent
import com.example.myapp.userMessage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.myapp.ErrorText
import com.example.myapp.ScreenTopBar
import com.example.myapp.matchNormalized
import kotlinx.coroutines.launch

/**
 * Reusable ordered track list. [loader] fetches the tracks; tapping a row plays from there (at random
 * behind it while shuffle is on), and the header shuffle button plays the whole list at random. When
 * [playlistId] is set, each row's cross removes the track from that playlist. The heart and diamond
 * are always available. [source] defaults to that playlist, and is what a list with no playlist id
 * (Favoris) sets by hand.
 */
@Composable
fun DeezerTrackListScreen(
    repo: DeezerRepository,
    title: String,
    loader: suspend () -> List<DeezerTrack>,
    onBack: () -> Unit,
    playlistId: String? = null,
    source: TrackSource? = playlistId?.let { TrackSource.Playlist(it) }
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tracks by remember { mutableStateOf<List<DeezerTrack>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isBestPepites by remember { mutableStateOf(false) }
    var pickerTrack by remember { mutableStateOf<DeezerTrack?>(null) }
    var query by remember { mutableStateOf("") }
    val favoriteIds by repo.favoriteIds.collectAsState()
    val playerState by repo.playerState.collectAsState()

    LaunchedEffect(title) {
        runCatching { tracks = loader() }.onFailure { error = userMessage(it) }
        isLoading = false
        // Warm the favorites cache so the hearts show the correct filled/empty state.
        runCatching { repo.ensureFavorites() }
        // Hide the "add to Best pépites" action when this very playlist is Best pépites.
        isBestPepites = playlistId != null && runCatching { repo.bestPepitesPlaylistId() }.getOrNull() == playlistId
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = title, onBack = onBack)
        when (val t = tracks) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val currentError = error
                when {
                    currentError != null -> ErrorText(message = "Erreur: $currentError", onDismiss = { error = null })
                    isLoading -> CircularProgressIndicator()
                }
            }
            else -> {
                // What you see is what plays: a filtered list queues only the tracks left on screen.
                val shown = remember(t, query) {
                    val q = query.trim()
                    if (q.isBlank()) t
                    else {
                        val needle = q.matchNormalized()
                        t.filter { it.title.matchNormalized().contains(needle) || it.artist.matchNormalized().contains(needle) }
                    }
                }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Filtrer: titre, artiste…") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${shown.size} titres", style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                            if (playlistId != null) {
                                IconButton(onClick = { sharePlaylist(context, playlistId, title) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Partager la playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { scope.launch { repo.shuffleTracks(shown, source) } }) {
                                Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    itemsIndexed(shown, key = { _, track -> track.sngId }) { index, track ->
                        TrackRow(
                            track = track,
                            onClick = { scope.launch { repo.playTracks(shown, index, source) } },
                            showActions = true,
                            isFavorite = favoriteIds.contains(track.sngId),
                            isPlaying = playerState.hasItem && playerState.sngId == track.sngId,
                            onToggleFavorite = { scope.launch { runCatching { repo.toggleFavorite(track) } } },
                            onAddToQueue = {
                                scope.launch {
                                    Toast.makeText(context, addToQueueMessage(repo, track), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onToggleBestPepites = if (isBestPepites) null else {
                                {
                                    scope.launch {
                                        Toast.makeText(context, toggleBestPepitesMessage(repo, track), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onAddToPlaylist = { pickerTrack = track },
                            onRemoveFromPlaylist = playlistId?.let { pid ->
                                {
                                    scope.launch {
                                        runCatching { repo.removeFromPlaylist(pid, track.sngId) }
                                            .onSuccess { tracks = tracks?.filterNot { it.sngId == track.sngId } }
                                    }
                                }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    pickerTrack?.let { track ->
        PlaylistPickerDialog(
            repo = repo,
            onDismiss = { pickerTrack = null },
            onPick = { playlist ->
                pickerTrack = null
                scope.launch {
                    Toast.makeText(context, addToPlaylistMessage(repo, playlist, track), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * Send the playlist to the system share sheet. Unlike a track, a playlist has no song.link
 * equivalent, so the link is Deezer's own; it only opens for the person receiving it if the playlist
 * is public on the account.
 */
private fun sharePlaylist(context: Context, playlistId: String, title: String) {
    val link = "https://www.deezer.com/playlist/$playlistId"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$title\n$link")
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    context.startActivity(Intent.createChooser(send, "Partager la playlist"))
}
