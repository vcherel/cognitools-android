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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.myapp.AppSnackbar
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapp.MiniPlayerBar
import com.example.myapp.deezerRepository
import com.example.myapp.podcastRepository
import com.example.myapp.popBackStackOnce
import com.example.myapp.podcasts.PodcastDownloadsScreen
import com.example.myapp.podcasts.PodcastEpisodesScreen
import com.example.myapp.podcasts.PodcastFullPlayerSheet

/**
 * Host for the Musique tool. A nested NavHost drives library / search / track lists / podcast
 * episodes, while the mini-player bars and expandable full-player sheets live at this level so they
 * persist across every sub-screen. Two independent players can both have something queued (a Deezer
 * track/podcast episode, and an RSS podcast episode), so both sets are shown side by side here.
 */
@Composable
fun DeezerScreen(
    onBack: () -> Unit,
    openFullPlayerInitially: Boolean = false,
    onOpenVolume: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = context.deezerRepository
    val podcastRepo = context.podcastRepository
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val playerState by repo.player.playerState.collectAsState()

    fun openArtist(artist: DeezerArtist) {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        nav.navigate("artist/${enc(artist.id)}/${enc(artist.name)}/${enc(artist.pictureUrl ?: "")}")
    }
    fun openArtistByName(name: String) {
        scope.launch {
            val artist = runCatching { repo.searchArtist(name) }.getOrNull()
            if (artist != null) openArtist(artist) else AppSnackbar.show("Artiste introuvable")
        }
    }
    val podcastPlayerState by podcastRepo.playerState.collectAsState()
    val playlists by repo.playlists.collectAsState()
    val sourceLabel = when (val source = playerState.source) {
        is TrackSource.Favorites -> "Favoris"
        is TrackSource.Playlist -> playlists?.firstOrNull { it.id == source.id }?.title
        null -> null
    }
    var showFullPlayer by remember { mutableStateOf(openFullPlayerInitially) }
    var showFullPodcastPlayer by remember { mutableStateOf(false) }

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
                        onOpenFavoritesHistory = { nav.navigate("favoritesHistory") },
                        onOpenDownloaded = { nav.navigate("downloaded") },
                        onOpenPlaylist = { pl -> nav.navigate("playlist/${pl.id}/${java.net.URLEncoder.encode(pl.title, "UTF-8")}") },
                        onOpenPodcast = { fav -> nav.navigate("podcast/${java.net.URLEncoder.encode(fav.id, "UTF-8")}") },
                        onOpenPodcastDownloads = { nav.navigate("podcastDownloads") },
                        onOpenDiscoveries = { nav.navigate("discoveries") },
                        onOpenErrors = { nav.navigate("errors") },
                        onOpenVolume = onOpenVolume
                    )
                }
                composable("errors") {
                    DeezerErrorsScreen(repo = repo, onBack = { nav.popBackStackOnce() })
                }
                composable("favorites") {
                    DeezerTrackListScreen(
                        repo = repo,
                        title = "Favoris",
                        source = TrackSource.Favorites,
                        loader = { repo.ensureFavorites() },
                        onBack = { nav.popBackStackOnce() },
                        onOpenArtist = { track -> openArtistByName(track.artist) }
                    )
                }
                composable("downloaded") {
                    DeezerTrackListScreen(
                        repo = repo,
                        title = "Hors ligne",
                        loader = { repo.player.downloadedTracks() },
                        onBack = { nav.popBackStackOnce() },
                        onOpenArtist = { track -> openArtistByName(track.artist) }
                    )
                }
                composable("favoritesHistory") {
                    DeezerFavoritesHistoryScreen(repo = repo, onBack = { nav.popBackStackOnce() })
                }
                composable("search") {
                    DeezerSearchScreen(
                        repo = repo,
                        onBack = { nav.popBackStackOnce() },
                        onOpenArtist = ::openArtist,
                        onOpenArtistByName = ::openArtistByName
                    )
                }
                composable("artist/{id}/{name}/{pic}") { entry ->
                    val dec = { key: String -> entry.arguments?.getString(key)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty() }
                    DeezerArtistScreen(
                        repo = repo,
                        artistId = dec("id"),
                        artistName = dec("name"),
                        pictureUrl = dec("pic").ifBlank { null },
                        onBack = { nav.popBackStackOnce() },
                        onOpenRelease = { r ->
                            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
                            nav.navigate("artistAlbum/${enc(r.albumId)}/${enc(r.title)}/${enc(r.artistId)}/${enc(r.artistName)}")
                        }
                    )
                }
                composable("artistAlbum/{albumId}/{title}/{artistId}/{artistName}") { entry ->
                    val dec = { key: String -> entry.arguments?.getString(key)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty() }
                    val title = dec("title")
                    val release = DeezerRelease(
                        albumId = dec("albumId"), title = title, releaseDate = "", recordType = "",
                        coverMd5 = null, artistId = dec("artistId"), artistName = dec("artistName")
                    )
                    DeezerTrackListScreen(
                        repo = repo,
                        title = title,
                        loader = { repo.albumTracks(release) },
                        onBack = { nav.popBackStackOnce() },
                        onOpenArtist = { track -> openArtistByName(track.artist) }
                    )
                }
                composable("discoveries") {
                    DeezerDiscoveriesScreen(repo = repo, onBack = { nav.popBackStackOnce() })
                }
                composable("podcast/{favoriteId}") { entry ->
                    val favoriteId = entry.arguments?.getString("favoriteId")
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    PodcastEpisodesScreen(repo = podcastRepo, favoriteId = favoriteId, onBack = { nav.popBackStackOnce() })
                }
                composable("podcastDownloads") {
                    PodcastDownloadsScreen(repo = podcastRepo, onBack = { nav.popBackStackOnce() })
                }
                composable("playlist/{id}/{title}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val title = entry.arguments?.getString("title")?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    DeezerTrackListScreen(
                        repo = repo,
                        title = title,
                        playlistId = id,
                        loader = { repo.playlistTracks(id) },
                        onBack = { nav.popBackStackOnce() },
                        onOpenArtist = { track -> openArtistByName(track.artist) }
                    )
                }
            }

            if (playerState.hasItem) {
                MiniPlayerBar(
                    artworkUrl = playerState.coverUrl,
                    title = playerState.title,
                    subtitle = playerState.artist,
                    note = sourceLabel?.let { "Lecture depuis : $it" },
                    isPlaying = playerState.isPlaying,
                    isBuffering = playerState.isBuffering,
                    onExpand = { showFullPlayer = true },
                    onTogglePlay = { repo.player.togglePlay() }
                )
            }
            if (podcastPlayerState.hasItem) {
                MiniPlayerBar(
                    artworkUrl = podcastPlayerState.artworkUrl,
                    title = podcastPlayerState.title,
                    subtitle = podcastPlayerState.podcastTitle,
                    isPlaying = podcastPlayerState.isPlaying,
                    isBuffering = podcastPlayerState.isBuffering,
                    onExpand = { showFullPodcastPlayer = true },
                    onTogglePlay = { podcastRepo.togglePlay() }
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
                onCollapse = { showFullPlayer = false },
                onOpenArtist = { name ->
                    showFullPlayer = false
                    openArtistByName(name)
                }
            )
        }
        if (showFullPodcastPlayer) {
            PodcastFullPlayerSheet(
                repo = podcastRepo,
                state = podcastPlayerState,
                onCollapse = { showFullPodcastPlayer = false }
            )
        }
    }
}
