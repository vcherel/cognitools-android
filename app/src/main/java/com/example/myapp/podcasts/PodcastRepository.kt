package com.example.myapp.podcasts

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.myapp.flashcards.AppDatabase
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PodcastPlayerUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val episodeId: String? = null
)

/**
 * Singleton (held by MyApplication, like DeezerRepository). Owns favorites (Room), the merged
 * episode list read live from each favorite's RSS feed, the heard/seen state, and the shared
 * MediaController driving PodcastPlaybackService.
 */
class PodcastRepository(private val appContext: Context) {

    private fun dao() = AppDatabase.get(appContext).podcastDao()

    val favorites: kotlinx.coroutines.flow.Flow<List<PodcastFavorite>> get() = dao().observeFavorites()

    // ---- Episodes ----
    // Episodes aren't persisted: every favorite's feed is re-fetched on refresh and merged here,
    // then joined against the seen table. Local edits (mark seen, add/remove a favorite) patch
    // this in place so the UI doesn't wait on a full re-fetch for something it already knows.

    private val _episodes = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    val episodes: StateFlow<List<PodcastEpisode>> = _episodes

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val refreshMutex = Mutex()

    /** Re-fetches every favorite's RSS feed and rebuilds the merged, seen-tagged episode list. */
    suspend fun refreshEpisodes(): Unit = refreshMutex.withLock {
        _loading.value = true
        try {
            val favs = dao().getFavorites()
            val seen = dao().getSeenIds().toSet()
            val raw = withContext(Dispatchers.IO) {
                favs.map { fav -> async { runCatching { fetchEpisodes(fav) }.getOrDefault(emptyList()) } }
                    .awaitAll()
                    .flatten()
            }
            _episodes.value = raw.map { it.copy(seen = it.id in seen) }.sortedByDescending { it.pubDate }
        } finally {
            _loading.value = false
        }
    }

    suspend fun search(query: String): List<PodcastSearchResult> =
        withContext(Dispatchers.IO) { searchPodcasts(query) }

    /** Follows [result] and merges its episodes into the list right away, without a full refresh. */
    suspend fun addFavorite(result: PodcastSearchResult) {
        val fav = PodcastFavorite(
            id = result.feedUrl,
            title = result.title,
            author = result.author,
            artworkUrl = result.artworkUrl,
            addedAt = System.currentTimeMillis()
        )
        dao().upsertFavorite(fav)
        val seen = dao().getSeenIds().toSet()
        val newEpisodes = withContext(Dispatchers.IO) {
            runCatching { fetchEpisodes(fav) }.getOrDefault(emptyList())
        }.map { it.copy(seen = it.id in seen) }
        _episodes.value = (_episodes.value.filterNot { it.podcastId == fav.id } + newEpisodes)
            .sortedByDescending { it.pubDate }
    }

    /** Unfollows the podcast [id] and drops its episodes from the merged list. */
    suspend fun removeFavorite(id: String) {
        dao().deleteFavorite(id)
        _episodes.value = _episodes.value.filterNot { it.podcastId == id }
    }

    suspend fun markSeen(episodeId: String) {
        dao().markSeen(PodcastSeenEpisode(episodeId, System.currentTimeMillis()))
        _episodes.value = _episodes.value.map { if (it.id == episodeId) it.copy(seen = true) else it }
    }

    suspend fun markUnseen(episodeId: String) {
        dao().unmarkSeen(episodeId)
        _episodes.value = _episodes.value.map { if (it.id == episodeId) it.copy(seen = false) else it }
    }

    // ---- Player ----

    private val _playerState = MutableStateFlow(PodcastPlayerUiState())
    val playerState: StateFlow<PodcastPlayerUiState> = _playerState

    @Volatile
    var controller: MediaController? = null
        private set

    private val controllerMutex = Mutex()
    private var controllerDeferred: CompletableDeferred<MediaController>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayerState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refreshPlayerState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshPlayerState()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshPlayerState()
    }

    suspend fun ensureController(): MediaController = controllerMutex.withLock {
        controllerDeferred?.let { return it.await() }
        val deferred = CompletableDeferred<MediaController>()
        controllerDeferred = deferred
        withContext(Dispatchers.Main) {
            val token = SessionToken(appContext, ComponentName(appContext, PodcastPlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            future.addListener({
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                refreshPlayerState()
                deferred.complete(c)
            }, MoreExecutors.directExecutor())
        }
        deferred.await()
    }

    private fun refreshPlayerState() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            _playerState.value = PodcastPlayerUiState()
            return
        }
        val m = c.mediaMetadata
        _playerState.value = PodcastPlayerUiState(
            hasItem = true,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            title = m.title?.toString().orEmpty(),
            podcastTitle = m.artist?.toString().orEmpty(),
            artworkUrl = m.artworkUri?.toString(),
            episodeId = c.currentMediaItem?.mediaId
        )
    }

    /** Plays [episode], queuing the rest of [queue] (defaults to just this episode) right after it. */
    suspend fun playEpisode(episode: PodcastEpisode, queue: List<PodcastEpisode> = listOf(episode)) {
        val startIndex = queue.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val controller = ensureController()
        val items = queue.map { buildMediaItem(it) }
        withContext(Dispatchers.Main) {
            controller.setMediaItems(items, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0L)) }
    fun seekBy(deltaMs: Long) { controller?.let { seekTo(it.currentPosition + deltaMs) } }
    fun positionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    /** Pauses, drops the notification, and stops the playback service entirely. */
    fun stopAll() {
        val c = controller ?: return
        c.sendCustomCommand(SessionCommand(PodcastPlaybackService.CMD_STOP_ALL, Bundle.EMPTY), Bundle.EMPTY)
        c.release()
        controller = null
        controllerDeferred = null
        _playerState.value = PodcastPlayerUiState()
    }

    private fun buildMediaItem(episode: PodcastEpisode): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .setArtworkUri(episode.podcastArtworkUrl?.let { Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setUri(Uri.parse(episode.audioUrl))
            .setMediaId(episode.id)
            .setMediaMetadata(metadata)
            .build()
    }
}
