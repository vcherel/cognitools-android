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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapp.AppSnackbar
import com.example.myapp.LocalGoHome
import com.example.myapp.ScreenTopBar
import com.example.myapp.podcastRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Host for the Podcasts tool. A nested NavHost drives the episode list / search / heard screens,
 * while the mini-player and full-player sheet live here so they persist across every sub-screen,
 * the same shape as DeezerScreen.
 */
@Composable
fun PodcastScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.podcastRepository
    val nav = rememberNavController()
    val playerState by repo.playerState.collectAsState()
    var showFullPlayer by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = "list",
                modifier = Modifier.fillMaxSize().weight(1f)
            ) {
                composable("list") {
                    PodcastListScreen(
                        repo = repo,
                        onBack = onBack,
                        onOpenSearch = { nav.navigate("search") },
                        onOpenSeen = { nav.navigate("seen") }
                    )
                }
                composable("search") {
                    PodcastSearchScreen(repo = repo, onBack = { nav.popBackStack() })
                }
                composable("seen") {
                    PodcastSeenScreen(repo = repo, onBack = { nav.popBackStack() })
                }
            }

            if (playerState.hasItem) {
                PodcastMiniPlayerBar(
                    state = playerState,
                    onExpand = { showFullPlayer = true },
                    onTogglePlay = { repo.togglePlay() }
                )
            }
        }

        if (showFullPlayer) {
            PodcastFullPlayerSheet(
                repo = repo,
                state = playerState,
                onCollapse = { showFullPlayer = false }
            )
        }
    }
}

/** Main list: every unseen episode across the followed podcasts, most recent first. */
@Composable
private fun PodcastListScreen(
    repo: PodcastRepository,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSeen: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val goHome = LocalGoHome.current
    val favorites by repo.favorites.collectAsState(initial = emptyList())
    val episodes by repo.episodes.collectAsState()
    val loading by repo.loading.collectAsState()
    val playerState by repo.playerState.collectAsState()

    LaunchedEffect(Unit) { repo.refreshEpisodes() }

    val unseen = episodes.filter { !it.seen }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Podcasts", onBack = onBack) {
            Spacer(Modifier.weight(1f))
            if (playerState.hasItem) {
                IconButton(onClick = { repo.stopAll(); goHome() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Tout arrêter")
                }
            }
            IconButton(onClick = onOpenSeen) {
                Icon(Icons.Filled.History, contentDescription = "Épisodes écoutés")
            }
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Rechercher un podcast")
            }
        }

        if (loading && episodes.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            favorites.isEmpty() && !loading -> EmptyState(
                icon = Icons.Filled.Podcasts,
                text = "Aucun podcast suivi.\nAppuie sur la loupe pour en chercher un.",
                onClick = onOpenSearch
            )
            unseen.isEmpty() && !loading -> EmptyState(
                icon = Icons.Filled.CheckCircle,
                text = "Tout est écouté !",
                onClick = null
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(unseen, key = { it.id }) { episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        isPlaying = playerState.episodeId == episode.id,
                        onClick = { scope.launch { repo.playEpisode(episode, unseen) } },
                        onToggleSeen = {
                            scope.launch {
                                repo.markSeen(episode.id)
                                AppSnackbar.show(
                                    message = "Marqué comme écouté",
                                    actionLabel = "Annuler",
                                    onAction = { repo.markUnseen(episode.id) }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: (() -> Unit)?) {
    Box(
        Modifier
            .fillMaxSize()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private val episodeDateFormat = SimpleDateFormat("d MMM yyyy", Locale.FRENCH)

/** One episode line: artwork, title, podcast + date, tap to play, trailing button to hide/unhide. */
@Composable
fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    isPlaying: Boolean,
    onClick: () -> Unit,
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
        Column(Modifier.weight(1f)) {
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
                episode.podcastTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                episodeSubtitle(episode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onToggleSeen) {
            Icon(
                if (episode.seen) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline,
                contentDescription = if (episode.seen) "Marquer comme non écouté" else "Marquer comme écouté",
                tint = if (episode.seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
