package com.example.myapp.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.MediaListRow
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
    val downloadedIds by repo.downloads.ids.collectAsState()
    val downloadingIds by repo.downloads.activeIds.collectAsState()
    val downloadProgress by repo.downloads.progress.collectAsState()
    val listeningProgress by repo.progress.collectAsState(initial = emptyMap())
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var downloadedOnly by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    var confirmRemoveDownload by remember { mutableStateOf<PodcastEpisode?>(null) }
    // Set by the "marqué comme écouté" undo: the episode comes back where it was, and the list
    // scrolls to it, otherwise undoing the top one looks like nothing happened.
    var scrollBackTo by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(favoriteId) {
        loading = true
        runCatching { repo.refreshFavorite(favoriteId) }.onFailure { error = it.message }
        loading = false
    }

    val allEpisodes = episodes.filter { it.podcastId == favoriteId }.sortedByDescending { it.pubDate }
    val showEpisodes = allEpisodes
        .filter { showAll || !it.seen }
        .filter { !downloadedOnly || repo.downloads.isDownloaded(it.id, downloadedIds) }

    LaunchedEffect(scrollBackTo, showEpisodes) {
        val id = scrollBackTo ?: return@LaunchedEffect
        val index = showEpisodes.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index)
        scrollBackTo = null
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = favorite?.title ?: "Podcast",
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleMaxLines = 2,
            titleWeight = true
        ) {
            IconButton(onClick = { downloadedOnly = !downloadedOnly }) {
                Icon(
                    if (downloadedOnly) Icons.Filled.DownloadDone else Icons.Filled.Download,
                    contentDescription = if (downloadedOnly) "Afficher tous les épisodes" else "Afficher seulement les téléchargés",
                    tint = if (downloadedOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

        confirmRemoveDownload?.let { episode ->
            ShowAlertDialog(
                onDismiss = { confirmRemoveDownload = null },
                title = "Supprimer le téléchargement de « ${episode.title} » ?",
                onCancel = { confirmRemoveDownload = null },
                onConfirm = {
                    confirmRemoveDownload = null
                    scope.launch { repo.downloads.remove(episode.id) }
                },
                cancelText = "Annuler",
                confirmText = "Supprimer"
            )
        }

        if (loading && showEpisodes.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        error?.let {
            ErrorText(message = "Erreur: $it", onDismiss = { error = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        if (showEpisodes.isEmpty() && !loading) {
            val allHeard = allEpisodes.isNotEmpty() && !showAll && !downloadedOnly
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        downloadedOnly -> "Aucun épisode téléchargé."
                        allHeard -> "Tout est écouté !"
                        else -> "Aucun épisode."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(showEpisodes, key = { it.id }) { episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        isPlaying = podcastPlayerState.episodeId == episode.id,
                        isDownloaded = repo.downloads.isDownloaded(episode.id, downloadedIds),
                        isDownloading = episode.id in downloadingIds,
                        downloadProgress = downloadProgress[episode.id],
                        listeningProgress = listeningProgress[episode.id],
                        onClick = {
                            scope.launch {
                                runCatching { repo.playEpisode(episode, showEpisodes) }
                                    .onFailure { AppSnackbar.show(it.message ?: "Erreur de lecture") }
                            }
                        },
                        onToggleDownload = {
                            when {
                                repo.downloads.isDownloaded(episode.id, downloadedIds) -> confirmRemoveDownload = episode
                                episode.id in downloadingIds -> repo.downloads.cancel(episode.id)
                                else -> repo.downloads.enqueue(episode)
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
                                        onAction = {
                                            repo.markUnseen(episode.id)
                                            scrollBackTo = episode.id
                                        }
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
    /** Shown ahead of the date on the cross-show downloaded list, where the show isn't obvious. */
    withPodcastTitle: Boolean = false,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    listeningProgress: PodcastEpisodeProgress?,
    onClick: () -> Unit,
    onToggleDownload: () -> Unit,
    onToggleSeen: () -> Unit
) {
    MediaListRow(
        artworkUrl = episode.podcastArtworkUrl,
        title = episode.title,
        isPlaying = isPlaying,
        onClick = onClick,
        artworkSize = 52.dp,
        titleMaxLines = 2,
        textAlpha = if (episode.seen) 0.55f else 1f,
        below = {
            Text(
                episodeSubtitle(episode, listeningProgress, withPodcastTitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            // Only a started, unfinished episode has a saved position, so the bar is exactly the
            // "you left off here" marker.
            episodeFraction(episode, listeningProgress)?.let { fraction ->
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        },
        trailing = {
            if (episode.source != PodcastSource.DEEZER) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isDownloading, onClick = onToggleDownload),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        // Indeterminate only until the server tells us the episode's size.
                        isDownloading && downloadProgress != null -> CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
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
    )
}

private fun episodeSubtitle(
    episode: PodcastEpisode,
    progress: PodcastEpisodeProgress?,
    withPodcastTitle: Boolean = false
): String {
    val show = episode.podcastTitle.takeIf { withPodcastTitle && it.isNotBlank() }
    val date = if (episode.pubDate > 0) episodeDateFormat.format(episode.pubDate) else null
    val duration = episode.durationSec?.let { formatDuration(it) }
    val remaining = progress?.let {
        val totalMs = episodeDurationMs(episode, it)
        if (totalMs <= 0) null else "reste ${formatDuration(((totalMs - it.positionMs) / 1000L).toInt().coerceAtLeast(60))}"
    }
    return listOfNotNull(show, date, duration, remaining).joinToString(" · ")
}

/** How far into the episode the saved position sits, 0..1, or null when there is nothing to show. */
private fun episodeFraction(episode: PodcastEpisode, progress: PodcastEpisodeProgress?): Float? {
    if (progress == null) return null
    val totalMs = episodeDurationMs(episode, progress)
    if (totalMs <= 0) return null
    return (progress.positionMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

/** The player's own duration when it got one, otherwise what the feed claims. */
private fun episodeDurationMs(episode: PodcastEpisode, progress: PodcastEpisodeProgress): Long =
    progress.durationMs.takeIf { it > 0 } ?: ((episode.durationSec ?: 0) * 1000L)

private fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h} h ${m} min" else "${m} min"
}
