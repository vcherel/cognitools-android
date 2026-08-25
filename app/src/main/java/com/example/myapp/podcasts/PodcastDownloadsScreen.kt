package com.example.myapp.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.launch

/**
 * Every downloaded episode, all followed shows merged, newest first. Reads the downloads table
 * rather than the feeds, so it is exactly what plays with no connection; the show each episode
 * belongs to is printed in the subtitle since the list crosses several.
 */
@Composable
fun PodcastDownloadsScreen(repo: PodcastRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val downloads by repo.downloads.episodes.collectAsState(initial = emptyList())
    val episodes by repo.episodes.collectAsState()
    val podcastPlayerState by repo.playerState.collectAsState()
    val downloadedIds by repo.downloads.ids.collectAsState()
    val listeningProgress by repo.progress.collectAsState(initial = emptyMap())
    var confirmRemove by remember { mutableStateOf<PodcastEpisode?>(null) }

    // The live episode carries the seen state; the stored row is the fallback when no feed was read.
    val downloadedEpisodes = downloads
        .filter { repo.downloads.isDownloaded(it.episodeId, downloadedIds) }
        .map { row -> episodes.firstOrNull { it.id == row.episodeId } ?: row.toEpisode(seen = false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Téléchargés", onBack = onBack)

        confirmRemove?.let { episode ->
            ShowAlertDialog(
                onDismiss = { confirmRemove = null },
                title = "Supprimer le téléchargement de « ${episode.title} » ?",
                onCancel = { confirmRemove = null },
                onConfirm = {
                    confirmRemove = null
                    scope.launch { repo.downloads.remove(episode.id) }
                },
                cancelText = "Annuler",
                confirmText = "Supprimer"
            )
        }

        if (downloadedEpisodes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aucun épisode téléchargé.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(downloadedEpisodes, key = { it.id }) { episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        isPlaying = podcastPlayerState.episodeId == episode.id,
                        withPodcastTitle = true,
                        isDownloaded = true,
                        isDownloading = false,
                        downloadProgress = null,
                        listeningProgress = listeningProgress[episode.id],
                        onClick = {
                            scope.launch {
                                runCatching { repo.playEpisode(episode, downloadedEpisodes) }
                                    .onFailure { AppSnackbar.show(it.message ?: "Erreur de lecture") }
                            }
                        },
                        onToggleDownload = { confirmRemove = episode },
                        onToggleSeen = {
                            scope.launch {
                                if (episode.seen) {
                                    repo.markUnseen(episode.id)
                                } else {
                                    repo.markSeen(episode.id)
                                    AppSnackbar.show(
                                        message = "Marqué comme écouté",
                                        actionLabel = "Annuler",
                                        onAction = { repo.markUnseen(episode.id) }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
