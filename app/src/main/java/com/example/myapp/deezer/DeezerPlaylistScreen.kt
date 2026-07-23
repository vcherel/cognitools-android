package com.example.myapp.deezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

/**
 * Reusable ordered track list: used for a playlist and for the full favorites list. [loader] fetches
 * the tracks; tapping a row plays from there, and the header button plays the whole list.
 */
@Composable
fun DeezerTrackListScreen(
    repo: DeezerRepository,
    title: String,
    loader: suspend () -> List<DeezerTrack>,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<DeezerTrack>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(title) {
        runCatching { tracks = loader() }.onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = title, onBack = onBack)
        when (val t = tracks) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                error?.let { Text("Erreur: $it", color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator()
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${t.size} titres", style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                        // MyButton fills its parent's width, so bound it in a fixed-width Box.
                        Box(Modifier.width(130.dp)) {
                            MyButton(text = "Tout lire", height = 48.dp, fontSize = MaterialTheme.typography.labelLarge.fontSize) {
                                scope.launch { repo.playTracks(t, 0) }
                            }
                        }
                    }
                }
                itemsIndexed(t) { index, track ->
                    TrackRow(track) { scope.launch { repo.playTracks(t, index) } }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
