package com.example.myapp.deezer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeezerSearchScreen(repo: DeezerRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var artist by remember { mutableStateOf<DeezerArtist?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pickerTrack by remember { mutableStateOf<DeezerTrack?>(null) }
    val favoriteIds by repo.favoriteIds.collectAsState()

    // Warm the favorites cache so the hearts show the correct filled/empty state.
    LaunchedEffect(Unit) { runCatching { repo.ensureFavorites() } }

    // Debounced search as the user types. Tracks and the best matching artist run concurrently.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); artist = null; return@LaunchedEffect }
        searching = true
        delay(350)
        coroutineScope {
            val tracksDeferred = async { runCatching { repo.search(q) }.getOrDefault(emptyList()) }
            val artistDeferred = async { runCatching { repo.searchArtist(q) }.getOrNull() }
            results = tracksDeferred.await()
            artist = artistDeferred.await()
        }
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Recherche", onBack = onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Titre, artiste, album…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (searching && results.isEmpty()) {
            Text("Recherche…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            artist?.let { a ->
                item(key = "artist-${a.id}") {
                    ArtistCard(
                        artist = a,
                        onShuffle = {
                            scope.launch {
                                runCatching { repo.shuffleArtist(a) }
                                    .onFailure { Toast.makeText(context, "Échec de la lecture", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    )
                    Spacer(Modifier.padding(4.dp))
                }
            }
            itemsIndexed(results) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { scope.launch { repo.playTracks(results, index) } },
                    showActions = true,
                    isFavorite = favoriteIds.contains(track.sngId),
                    onToggleFavorite = { scope.launch { runCatching { repo.toggleFavorite(track) } } },
                    onAddToQueue = {
                        scope.launch {
                            Toast.makeText(context, addToQueueMessage(repo, track), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAddToBestPepites = {
                        scope.launch {
                            Toast.makeText(context, addToBestPepitesMessage(repo, track), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAddToPlaylist = { pickerTrack = track }
                )
            }
            item { Spacer(Modifier.padding(8.dp)) }
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
 * The catalog's best matching artist for the query, shown above the track results. The whole card
 * taps to shuffle the artist's top tracks right away, no separate screen: the matching tracks below
 * already show what "his songs" are.
 */
@Composable
private fun ArtistCard(artist: DeezerArtist, onShuffle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onShuffle)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (artist.pictureUrl != null) {
                AsyncImage(model = artist.pictureUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire", tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("Aléatoire", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}
