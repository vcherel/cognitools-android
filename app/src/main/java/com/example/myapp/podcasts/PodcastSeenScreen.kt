package com.example.myapp.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

/** Every episode marked heard, most recently heard podcast first, with a way to bring one back. */
@Composable
fun PodcastSeenScreen(repo: PodcastRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val episodes by repo.episodes.collectAsState()
    val playerState by repo.playerState.collectAsState()
    val seen = episodes.filter { it.seen }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Épisodes écoutés", onBack = onBack)

        if (seen.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Aucun épisode marqué comme écouté",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(seen, key = { it.id }) { episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        isPlaying = playerState.episodeId == episode.id,
                        onClick = { scope.launch { repo.playEpisode(episode, seen) } },
                        onToggleSeen = { scope.launch { repo.markUnseen(episode.id) } }
                    )
                }
            }
        }
    }
}
