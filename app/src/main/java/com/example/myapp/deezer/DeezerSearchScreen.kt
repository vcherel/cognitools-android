package com.example.myapp.deezer

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeezerSearchScreen(repo: DeezerRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var pickerTrack by remember { mutableStateOf<DeezerTrack?>(null) }
    val favoriteIds by repo.favoriteIds.collectAsState()

    // Warm the favorites cache so the hearts show the correct filled/empty state.
    LaunchedEffect(Unit) { runCatching { repo.ensureFavorites() } }

    // Debounced search as the user types.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        searching = true
        delay(350)
        results = runCatching { repo.search(q) }.getOrDefault(emptyList())
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
            itemsIndexed(results) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { scope.launch { repo.playTracks(results, index) } },
                    showActions = true,
                    isFavorite = favoriteIds.contains(track.sngId),
                    onToggleFavorite = { scope.launch { runCatching { repo.toggleFavorite(track) } } },
                    onAddToBestPepites = {
                        scope.launch {
                            val ok = runCatching { repo.addToBestPepites(track) }.getOrDefault(false)
                            Toast.makeText(
                                context,
                                if (ok) "Ajouté à Best pépites" else "Playlist Best pépites introuvable",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    val ok = runCatching { repo.addToPlaylist(playlist.id, track) }.isSuccess
                    Toast.makeText(
                        context,
                        if (ok) "Ajouté à ${playlist.title}" else "Échec de l'ajout",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
