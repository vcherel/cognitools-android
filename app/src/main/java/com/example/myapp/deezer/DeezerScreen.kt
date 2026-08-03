package com.example.myapp.deezer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapp.deezerRepository

/**
 * Host for the Deezer tool. A nested NavHost drives library / search / track lists, while the
 * mini-player bar and the expandable full-player sheet live at this level so they persist across
 * every sub-screen.
 */
@Composable
fun DeezerScreen(onBack: () -> Unit, openFullPlayerInitially: Boolean = false) {
    val context = LocalContext.current
    val repo = context.deezerRepository
    val nav = rememberNavController()
    val playerState by repo.playerState.collectAsState()
    val playlists by repo.playlists.collectAsState()
    val sourceLabel = when (val source = playerState.source) {
        is TrackSource.Favorites -> "Favoris"
        is TrackSource.Playlist -> playlists?.firstOrNull { it.id == source.id }?.title
        null -> null
    }
    var showFullPlayer by remember { mutableStateOf(openFullPlayerInitially) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = "library",
                modifier = Modifier.fillMaxSize().weight(1f)
            ) {
                composable("library") {
                    DeezerLibraryScreen(
                        repo = repo,
                        onBack = onBack,
                        onOpenSearch = { nav.navigate("search") },
                        onOpenPlaylist = { pl -> nav.navigate("playlist/${pl.id}/${java.net.URLEncoder.encode(pl.title, "UTF-8")}") }
                    )
                }
                composable("search") {
                    DeezerSearchScreen(repo = repo, onBack = { nav.popBackStack() })
                }
                composable("playlist/{id}/{title}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val title = entry.arguments?.getString("title")?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    DeezerTrackListScreen(
                        repo = repo,
                        title = title,
                        playlistId = id,
                        loader = { repo.playlistTracks(id) },
                        onBack = { nav.popBackStack() }
                    )
                }
            }

            if (playerState.hasItem) {
                MiniPlayerBar(
                    state = playerState,
                    sourceLabel = sourceLabel,
                    onExpand = { showFullPlayer = true },
                    onTogglePlay = { repo.togglePlay() }
                )
            }
        }

        // Gated on showFullPlayer alone, not playerState.hasItem: stopping playback from the sheet
        // clears the player state right away, and if hasItem also hid the sheet the library screen
        // behind would flash for a frame while the pop back to the main menu catches up.
        if (showFullPlayer) {
            FullPlayerSheet(
                repo = repo,
                state = playerState,
                sourceLabel = sourceLabel,
                onCollapse = { showFullPlayer = false }
            )
        }
    }
}
