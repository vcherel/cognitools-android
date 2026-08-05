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
import com.example.myapp.deezerRepository
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.httpGet
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val RADIO_FRANCE_STATIONS = listOf("franceinter", "franceculture", "franceinfo", "francemusique", "mouv", "fip")

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

    /** Fetches one favorite's episodes from its actual source: its RSS feed, or Deezer's API. */
    private suspend fun fetchEpisodesFor(fav: PodcastFavorite): List<PodcastEpisode> = when (fav.source) {
        PodcastSource.RSS -> fetchEpisodes(fav)
        PodcastSource.DEEZER -> appContext.deezerRepository.podcastEpisodes(fav.id).map { ep ->
            PodcastEpisode(
                id = ep.id,
                podcastId = fav.id,
                podcastTitle = fav.title,
                podcastArtworkUrl = fav.artworkUrl,
                title = ep.title,
                pubDate = ep.releaseDateMs,
                audioUrl = "", // Deezer episodes stream through DeezerRepository's resolve pipeline, not a plain URL.
                durationSec = ep.durationSec,
                seen = false,
                source = PodcastSource.DEEZER
            )
        }
    }

    /** Re-fetches every favorite's feed/API and rebuilds the merged, seen-tagged episode list. */
    suspend fun refreshEpisodes(): Unit = refreshMutex.withLock {
        _loading.value = true
        try {
            val favs = dao().getFavorites()
            val seen = dao().getSeenIds().toSet()
            val raw = withContext(Dispatchers.IO) {
                favs.map { fav -> async { runCatching { fetchEpisodesFor(fav) }.getOrDefault(emptyList()) } }
                    .awaitAll()
                    .flatten()
            }
            _episodes.value = raw.map { it.copy(seen = it.id in seen) }.sortedByDescending { it.pubDate }
        } finally {
            _loading.value = false
        }
    }

    /** Re-fetches just [favoriteId]'s episodes and patches them into the merged list. Used by the
     *  per-podcast screen, which doesn't need every other followed show re-fetched to open fast. */
    suspend fun refreshFavorite(favoriteId: String) {
        val fav = dao().getFavorites().firstOrNull { it.id == favoriteId } ?: return
        val seen = dao().getSeenIds().toSet()
        val fresh = withContext(Dispatchers.IO) {
            runCatching { fetchEpisodesFor(fav) }.getOrDefault(emptyList())
        }.map { it.copy(seen = it.id in seen) }
        _episodes.value = (_episodes.value.filterNot { it.podcastId == favoriteId } + fresh)
            .sortedByDescending { it.pubDate }
    }

    /** Merges search results from the RSS/iTunes directory and Deezer's own podcast catalog. */
    suspend fun searchCatalog(query: String): List<PodcastCatalogItem> = withContext(Dispatchers.IO) {
        val rssDeferred = async { runCatching { searchPodcasts(query) }.getOrDefault(emptyList()) }
        val deezerDeferred = async {
            runCatching { appContext.deezerRepository.searchPodcastShows(query) }.getOrDefault(emptyList())
        }
        rssDeferred.await() + deezerDeferred.await().map {
            PodcastCatalogItem(id = it.id, source = PodcastSource.DEEZER, title = it.title, author = it.author, artworkUrl = it.artworkUrl)
        }
    }

    /** Deezer's trending podcasts, for the "recommendations" surface on an empty podcast search. */
    suspend fun recommendations(): List<PodcastCatalogItem> = withContext(Dispatchers.IO) {
        runCatching { appContext.deezerRepository.podcastChart() }.getOrDefault(emptyList()).map {
            PodcastCatalogItem(id = it.id, source = PodcastSource.DEEZER, title = it.title, author = it.author, artworkUrl = it.artworkUrl)
        }
    }

    /** Lowercases, strips accents and punctuation, for exact-ish title matching. */
    private fun normalizeTitle(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    /**
     * Deezer aggregates podcasts from their original external hosts (Acast, Audiomeans, Radio
     * France…) and, contrary to what its API's track_token field suggests, doesn't actually expose
     * a way to stream episode audio itself (confirmed: get_url returns empty media for every
     * format/cipher tried). So a Deezer-catalog show can only be followed by finding its real RSS
     * feed: first via an exact (accent/case/punctuation insensitive) title match in the iTunes
     * directory, then, for a big French public broadcaster show iTunes doesn't expose a feedUrl for
     * at all (confirmed for France Inter's own flagship shows), via [resolveRadioFranceFeed].
     */
    private suspend fun resolveDeezerShowToRss(item: PodcastCatalogItem): PodcastCatalogItem? {
        val target = normalizeTitle(item.title)
        val candidates = runCatching { searchPodcasts(item.title) }.getOrDefault(emptyList())
        candidates.firstOrNull { normalizeTitle(it.title) == target }?.let { return it }
        return resolveRadioFranceFeed(item)
    }

    /**
     * Radio France no longer links its shows' RSS feeds from its own site, but the feeds still
     * exist and are still embedded (as a radiofrance-podcast.net URL) in each show's own page HTML.
     * The page URL itself is a predictable slug of the title under one of Radio France's stations,
     * so this guesses it rather than needing a show-specific lookup.
     */
    private suspend fun resolveRadioFranceFeed(item: PodcastCatalogItem): PodcastCatalogItem? =
        withContext(Dispatchers.IO) {
            val slug = slugify(item.title)
            val feedPattern = Regex("""https://radiofrance-podcast\.net/podcast09/[^"'\s]+\.xml""")
            for (station in RADIO_FRANCE_STATIONS) {
                val html = runCatching {
                    httpGet("https://www.radiofrance.fr/$station/podcasts/$slug", accept = "text/html")
                }.getOrNull() ?: continue
                val feedUrl = feedPattern.find(html)?.value ?: continue
                return@withContext PodcastCatalogItem(
                    id = feedUrl, source = PodcastSource.RSS, title = item.title, author = item.author, artworkUrl = item.artworkUrl
                )
            }
            null
        }

    private fun slugify(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    /**
     * Follows [item] and merges its episodes into the list right away, without a full refresh.
     * Returns false, with nothing followed, if [item] is a Deezer-catalog show with no matching RSS
     * feed we could resolve (see [resolveDeezerShowToRss]): better to say no than to add a show that
     * can never actually play.
     */
    suspend fun addFavorite(item: PodcastCatalogItem): Boolean {
        val resolved = if (item.source == PodcastSource.DEEZER) {
            resolveDeezerShowToRss(item) ?: return false
        } else item
        val fav = PodcastFavorite(
            id = resolved.id,
            title = resolved.title,
            author = resolved.author,
            artworkUrl = resolved.artworkUrl,
            addedAt = System.currentTimeMillis(),
            source = resolved.source
        )
        dao().upsertFavorite(fav)
        val seen = dao().getSeenIds().toSet()
        val newEpisodes = withContext(Dispatchers.IO) {
            runCatching { fetchEpisodesFor(fav) }.getOrDefault(emptyList())
        }.map { it.copy(seen = it.id in seen) }
        _episodes.value = (_episodes.value.filterNot { it.podcastId == fav.id } + newEpisodes)
            .sortedByDescending { it.pubDate }
        return true
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

    /**
     * Plays [episode], queuing the rest of [queue] (defaults to just this episode) right after it.
     * A DEEZER-sourced episode can't actually play (Deezer doesn't expose episode audio itself; see
     * [resolveDeezerShowToRss]): addFavorite no longer creates these, but a favorite followed before
     * that fix still has this source on disk until re-followed, so this fails clearly instead of crashing.
     */
    suspend fun playEpisode(episode: PodcastEpisode, queue: List<PodcastEpisode> = listOf(episode)) {
        if (episode.source == PodcastSource.DEEZER) {
            throw IllegalStateException("Ce podcast doit être réajouté depuis la recherche pour pouvoir être lu")
        }
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
        cancelSleepTimer()
        val c = controller ?: return
        c.sendCustomCommand(SessionCommand(PodcastPlaybackService.CMD_STOP_ALL, Bundle.EMPTY), Bundle.EMPTY)
        c.release()
        controller = null
        controllerDeferred = null
        _playerState.value = PodcastPlayerUiState()
    }

    // ---- Sleep timer ----

    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sleepTimerJob: Job? = null

    private val _sleepTimerEndAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndAt: StateFlow<Long?> = _sleepTimerEndAt

    /** Pauses playback in [minutes]. Replaces any timer already running. */
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerEndAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = timerScope.launch {
            delay(minutes * 60_000L)
            withContext(Dispatchers.Main) { controller?.pause() }
            _sleepTimerEndAt.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerEndAt.value = null
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
