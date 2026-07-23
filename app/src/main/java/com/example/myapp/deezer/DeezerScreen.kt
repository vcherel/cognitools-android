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
import com.example.myapp.MyApplication

/**
 * Host for the Deezer tool. A nested NavHost drives library / search / track lists, while the
 * mini-player bar and the expandable full-player sheet live at this level so they persist across
 * every sub-screen.
 */
@Composable
fun DeezerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as MyApplication).deezerRepository
    val nav = rememberNavController()
    val playerState by repo.playerState.collectAsState()
    var showFullPlayer by remember { mutableStateOf(false) }

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
                        onOpenFavorites = { nav.navigate("favorites") },
                        onOpenPlaylist = { pl -> nav.navigate("playlist/${pl.id}/${java.net.URLEncoder.encode(pl.title, "UTF-8")}") }
                    )
                }
                composable("search") {
                    DeezerSearchScreen(repo = repo, onBack = { nav.popBackStack() })
                }
                composable("favorites") {
                    DeezerTrackListScreen(
                        repo = repo,
                        title = "Favoris",
                        loader = { repo.favorites() },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable("playlist/{id}/{title}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val title = entry.arguments?.getString("title")?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    DeezerTrackListScreen(
                        repo = repo,
                        title = title,
                        loader = { repo.playlistTracks(id) },
                        onBack = { nav.popBackStack() }
                    )
                }
            }

            if (playerState.hasItem) {
                MiniPlayerBar(
                    state = playerState,
                    onExpand = { showFullPlayer = true },
                    onTogglePlay = { repo.togglePlay() }
                )
            }
        }

        if (showFullPlayer && playerState.hasItem) {
            FullPlayerSheet(
                repo = repo,
                state = playerState,
                onCollapse = { showFullPlayer = false }
            )
        }
    }
}
