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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.podcastRepository
import com.example.myapp.podcasts.PodcastArt
import com.example.myapp.podcasts.PodcastCatalogItem
import com.example.myapp.podcasts.PodcastRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SearchMode { MUSIQUE, PODCAST }

@Composable
fun DeezerSearchScreen(repo: DeezerRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val podcastRepo = context.podcastRepository
    var mode by remember { mutableStateOf(SearchMode.MUSIQUE) }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Recherche", onBack = onBack)
        ModeToggle(mode = mode, onSelect = { mode = it; query = "" })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(if (mode == SearchMode.MUSIQUE) "Titre, artiste, album…" else "Rechercher un podcast…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when (mode) {
            SearchMode.MUSIQUE -> MusicSearch(repo = repo, query = query, scope = scope)
            SearchMode.PODCAST -> PodcastSearch(podcastRepo = podcastRepo, query = query, scope = scope)
        }
    }
}

@Composable
private fun ModeToggle(mode: SearchMode, onSelect: (SearchMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        ModeToggleSegment(
            text = "Musique",
            icon = Icons.Filled.LibraryMusic,
            selected = mode == SearchMode.MUSIQUE,
            onClick = { onSelect(SearchMode.MUSIQUE) },
            modifier = Modifier.weight(1f)
        )
        ModeToggleSegment(
            text = "Podcast",
            icon = Icons.Filled.Podcasts,
            selected = mode == SearchMode.PODCAST,
            onClick = { onSelect(SearchMode.PODCAST) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeToggleSegment(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Unchanged behavior: debounced track + best matching artist search. */
@Composable
private fun MusicSearch(repo: DeezerRepository, query: String, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var artist by remember { mutableStateOf<DeezerArtist?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pickerTrack by remember { mutableStateOf<DeezerTrack?>(null) }
    val favoriteIds by repo.favoriteIds.collectAsState()

    // Warm the favorites cache so the hearts show the correct filled/empty state.
    LaunchedEffect(Unit) { runCatching { repo.ensureFavorites() } }

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
        itemsIndexed(results, key = { _, track -> track.sngId }) { index, track ->
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

/** Empty query: Deezer's trending shows as recommendations. Non-empty: merged iTunes+Deezer search. */
@Composable
private fun PodcastSearch(podcastRepo: PodcastRepository, query: String, scope: kotlinx.coroutines.CoroutineScope) {
    var results by remember { mutableStateOf<List<PodcastCatalogItem>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<PodcastCatalogItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val favorites by podcastRepo.favorites.collectAsState(initial = emptyList())
    val followedIds = favorites.map { it.id }.toSet()

    LaunchedEffect(Unit) {
        recommendations = runCatching { podcastRepo.recommendations() }.getOrDefault(emptyList())
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        loading = true
        delay(350)
        results = runCatching { podcastRepo.searchCatalog(q) }.getOrDefault(emptyList())
        loading = false
    }

    val shown = query.trim().let { if (it.isBlank()) recommendations else results }
    val title = if (query.trim().isBlank()) "Recommandations" else null

    if (loading && results.isEmpty() && query.isNotBlank()) {
        Text("Recherche…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        title?.let { t ->
            item {
                Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
        }
        items(shown, key = { "${it.source}-${it.id}" }) { item ->
            PodcastCatalogRow(
                item = item,
                isFollowing = item.id in followedIds,
                onToggleFollow = {
                    scope.launch {
                        if (item.id in followedIds) {
                            podcastRepo.removeFavorite(item.id)
                        } else if (podcastRepo.addFavorite(item)) {
                            AppSnackbar.show("« ${item.title} » ajouté")
                        } else {
                            AppSnackbar.show("Impossible d'ajouter ce podcast")
                        }
                    }
                }
            )
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
private fun PodcastCatalogRow(item: PodcastCatalogItem, isFollowing: Boolean, onToggleFollow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArt(item.artworkUrl, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.author.isNotBlank()) {
                Text(
                    item.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleFollow) {
            Icon(
                if (isFollowing) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (isFollowing) "Déjà suivi" else "Suivre ce podcast",
                tint = if (isFollowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
