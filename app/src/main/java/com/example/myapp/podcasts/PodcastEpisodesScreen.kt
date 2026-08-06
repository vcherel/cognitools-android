package com.example.myapp.podcasts

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One followed podcast's episodes, all of them (seen and unseen alike), most recent first. Replaces
 * the old cross-show merged feed: since liked podcasts now live under Favoris on the Musique screen,
 * each show gets its own episode list instead of one combined "everything unseen" screen.
 */
@Composable
fun PodcastEpisodesScreen(repo: PodcastRepository, favoriteId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val favorites by repo.favorites.collectAsState(initial = emptyList())
    val favorite = favorites.firstOrNull { it.id == favoriteId }
    val episodes by repo.episodes.collectAsState()
    val podcastPlayerState by repo.playerState.collectAsState()
    val downloadedIds by repo.downloadedIds.collectAsState()
    val downloadingIds by repo.downloadingIds.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }

    LaunchedEffect(favoriteId) {
        loading = true
        runCatching { repo.refreshFavorite(favoriteId) }.onFailure { error = it.message }
        loading = false
    }

    val allEpisodes = episodes.filter { it.podcastId == favoriteId }.sortedByDescending { it.pubDate }
    val showEpisodes = if (showAll) allEpisodes else allEpisodes.filterNot { it.seen }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = favorite?.title ?: "Podcast", onBack = onBack) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAll = !showAll }) {
                Icon(
                    if (showAll) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showAll) "Masquer les épisodes écoutés" else "Afficher les épisodes écoutés"
                )
            }
            IconButton(onClick = { confirmUnfollow = true }) {
                Icon(Icons.Filled.Close, contentDescription = "Ne plus suivre ce podcast")
            }
        }

        if (confirmUnfollow) {
            val fav = favorite
            ShowAlertDialog(
                onDismiss = { confirmUnfollow = false },
                title = "Ne plus suivre « ${fav?.title ?: "ce podcast"} » ?",
                onCancel = { confirmUnfollow = false },
                onConfirm = {
                    confirmUnfollow = false
                    if (fav == null) return@ShowAlertDialog
                    scope.launch {
                        repo.removeFavorite(fav.id)
                        onBack()
                        AppSnackbar.show(
                            message = "« ${fav.title} » retiré",
                            actionLabel = "Annuler",
                            onAction = {
                                scope.launch {
                                    repo.addFavorite(PodcastCatalogItem(fav.id, fav.source, fav.title, fav.author, fav.artworkUrl))
                                }
                            }
                        )
                    }
                },
                cancelText = "Annuler",
                confirmText = "Ne plus suivre"
            )
        }

        if (loading && showEpisodes.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        error?.let {
            ErrorText(message = "Erreur: $it", onDismiss = { error = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        if (showEpisodes.isEmpty() && !loading) {
            val allHeard = allEpisodes.isNotEmpty() && !showAll
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (allHeard) "Tout est écouté !" else "Aucun épisode.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(showEpisodes, key = { it.id }) { episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        isPlaying = podcastPlayerState.episodeId == episode.id,
                        isDownloaded = episode.id in downloadedIds,
                        isDownloading = episode.id in downloadingIds,
                        onClick = {
                            scope.launch {
                                runCatching { repo.playEpisode(episode, showEpisodes) }
                                    .onFailure { AppSnackbar.show(it.message ?: "Erreur de lecture") }
                            }
                        },
                        onToggleDownload = {
                            if (episode.id in downloadedIds) {
                                repo.removeDownload(episode.id)
                            } else {
                                scope.launch {
                                    runCatching { repo.downloadEpisode(episode) }
                                        .onFailure { AppSnackbar.show("Échec du téléchargement") }
                                }
                            }
                        },
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

private val episodeDateFormat = SimpleDateFormat("d MMM yyyy", Locale.FRENCH)

/** One episode line: artwork, title, date, tap to play, trailing button to toggle heard/unheard. */
@Composable
fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    isPlaying: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onToggleDownload: () -> Unit,
    onToggleSeen: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArt(episode.podcastArtworkUrl, Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (episode.seen) 0.55f else 1f)) {
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
                Text(episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                episodeSubtitle(episode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (episode.source != PodcastSource.DEEZER) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !isDownloading, onClick = onToggleDownload),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isDownloading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    isDownloaded -> Icon(
                        Icons.Filled.DownloadDone,
                        contentDescription = "Téléchargé, appuyer pour supprimer",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    else -> Icon(
                        Icons.Filled.Download,
                        contentDescription = "Télécharger l'épisode",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (episode.seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onToggleSeen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (episode.seen) Icons.Filled.Check else Icons.Filled.CheckCircleOutline,
                contentDescription = if (episode.seen) "Marquer comme non écouté" else "Marquer comme écouté",
                tint = if (episode.seen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun episodeSubtitle(episode: PodcastEpisode): String {
    val date = if (episode.pubDate > 0) episodeDateFormat.format(episode.pubDate) else null
    val duration = episode.durationSec?.let { formatDuration(it) }
    return listOfNotNull(date, duration).joinToString(" · ")
}

private fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h} h ${m} min" else "${m} min"
}
