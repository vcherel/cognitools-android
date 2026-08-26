package com.example.myapp.podcasts

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.myapp.AppSnackbar
import com.example.myapp.MediaControllerHolder
import com.example.myapp.deezerRepository
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.httpGet
import com.example.myapp.matchNormalized
import com.example.myapp.slugified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PodcastRepository"

private val RADIO_FRANCE_STATIONS = listOf("franceinter", "franceculture", "franceinfo", "francemusique", "mouv", "fip")

/** Under this much playback, an "end of episode" is a source that stopped short, not a real end. */
private const val INSTANT_END_MS = 5_000L

/** How far before the saved position an episode restarts when resuming there ended it on the spot. */
private const val SHORT_SOURCE_BACK_MS = 60_000L

private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
/** Under this, an episode counts as not really started and resumes from zero. */
private const val PROGRESS_MIN_MS = 15_000L
/** Within this of the end, an episode counts as finished. */
private const val PROGRESS_FINISHED_MARGIN_MS = 30_000L

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
            _episodes.value = withDownloadedFallback(raw.map { it.copy(seen = it.id in seen) }, seen)
                .sortedByDescending { it.pubDate }
            backfillDownloadMetadata()
        } finally {
            _loading.value = false
        }
    }

    /** Re-fetches just [favoriteId]'s episodes and patches them into the merged list. Used by the
     *  per-podcast screen, which doesn't need every other followed show re-fetched to open fast.
     *  Shares [refreshMutex] with the full refresh, which would otherwise overwrite this patch. */
    suspend fun refreshFavorite(favoriteId: String) {
        val fav = dao().getFavorites().firstOrNull { it.id == favoriteId } ?: return
        val seen = dao().getSeenIds().toSet()
        val fetched = withContext(Dispatchers.IO) {
            runCatching { fetchEpisodesFor(fav) }.getOrDefault(emptyList())
        }.map { it.copy(seen = it.id in seen) }
        val fresh = withDownloadedFallback(fetched, seen).filter { it.podcastId == favoriteId }
        refreshMutex.withLock { replaceEpisodesOf(favoriteId, fresh) }
    }

    /**
     * Files metadata for downloads made before that table existed: a hash-named file can't be traced
     * back to its episode, but a fresh feed read can, so the first refresh that sees them fills them in.
     */
    private suspend fun backfillDownloadMetadata() {
        val known = dao().getDownloads().map { it.episodeId }.toSet()
        _episodes.value
            .filter { it.id !in known && downloads.isDownloaded(it.id) }
            .forEach { dao().upsertDownload(it.toDownload()) }
    }

    /**
     * Adds back the downloaded episodes [fetched] does not already contain. With no connection every
     * feed read fails and comes back empty, and the screens would show "Aucun épisode" while the
     * episodes are sitting on the phone, playable. Also covers a single feed being down.
     */
    private suspend fun withDownloadedFallback(
        fetched: List<PodcastEpisode>,
        seen: Set<String>
    ): List<PodcastEpisode> {
        val known = fetched.map { it.id }.toSet()
        val missing = dao().getDownloads()
            .filter { it.episodeId !in known && downloads.isDownloaded(it.episodeId) }
            .map { it.toEpisode(seen = it.episodeId in seen) }
        return if (missing.isEmpty()) fetched else fetched + missing
    }

    /** Swaps in one podcast's episodes, keeping the merged list ordered newest first. */
    private fun replaceEpisodesOf(podcastId: String, fresh: List<PodcastEpisode>) {
        _episodes.value = (_episodes.value.filterNot { it.podcastId == podcastId } + fresh)
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
        val target = item.title.matchNormalized()
        val candidates = runCatching { searchPodcasts(item.title) }.getOrDefault(emptyList())
        candidates.firstOrNull { it.title.matchNormalized() == target }?.let { return it }
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
            val slug = item.title.slugified()
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
        refreshMutex.withLock { replaceEpisodesOf(fav.id, newEpisodes) }
        return true
    }

    /** Unfollows the podcast [id] and drops its episodes from the merged list. */
    suspend fun removeFavorite(id: String) {
        dao().deleteFavorite(id)
        _episodes.value = _episodes.value.filterNot { it.podcastId == id }
    }

    suspend fun markSeen(episodeId: String) {
        dao().markSeen(PodcastSeenEpisode(episodeId, System.currentTimeMillis()))
        // A heard episode has nothing left to resume: the saved position would only show a stale bar
        // and restart it mid-way the next time it is played.
        dao().deleteProgress(episodeId)
        _episodes.value = _episodes.value.map { if (it.id == episodeId) it.copy(seen = true) else it }
        freeEpisodeStorage(episodeId)
    }

    /**
     * Gives back the disk a heard episode was holding: its cached stream bytes and its download, if
     * it had one. An episode id is its audio URL for an RSS episode, which is exactly the key the
     * stream cache uses.
     */
    private suspend fun freeEpisodeStorage(episodeId: String) {
        // Never pull the ground from under the player: an episode is marked heard once its last
        // [PROGRESS_FINISHED_MARGIN_MS] start playing, and those bytes are still being read. The
        // cleanup is then queued and run by [flushPendingStorageCleanup] the moment playback stops
        // or moves on, so a finished episode never waits for the next launch to leave the downloads.
        val stillPlaying = withContext(Dispatchers.Main) {
            val c = controller ?: return@withContext false
            c.currentMediaItem?.mediaId == episodeId && c.isPlaying
        }
        if (stillPlaying) {
            pendingStorageCleanup += episodeId
            return
        }
        pendingStorageCleanup -= episodeId
        withContext(Dispatchers.IO) {
            if (downloads.isDownloaded(episodeId)) downloads.remove(episodeId)
            // An RSS episode id is its audio URL, which is the key the cache files it under. A
            // Deezer-sourced one is a numeric id with nothing cached against it, and an episode gone
            // from the merged list can't be traced back to its URL: only the cache is skipped then,
            // the download above is keyed by the id alone and goes either way.
            val url = _episodes.value.firstOrNull { it.id == episodeId }?.audioUrl
                ?.takeIf { it.isNotBlank() } ?: return@withContext
            PodcastStreamCache.remove(appContext, url)
        }
    }

    /** Episodes marked heard while the player was still on them, waiting for it to move off.
     *  Concurrent: markSeen reaches this from the player's Main scope and from the download scope alike. */
    private val pendingStorageCleanup: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Runs the cleanups that were queued while their episode was playing. */
    private suspend fun flushPendingStorageCleanup() {
        if (pendingStorageCleanup.isEmpty()) return
        pendingStorageCleanup.toList().forEach { freeEpisodeStorage(it) }
    }

    /**
     * Sweeps the stream cache of every episode already heard. Run at app start: it catches the ones
     * finished before this cleanup existed, and any whose own cleanup was skipped because the player
     * was still on them at the time.
     */
    suspend fun purgeHeardFromCache() = withContext(Dispatchers.IO) {
        val seen = dao().getSeenIds().toSet()
        if (seen.isEmpty()) return@withContext
        // An RSS episode id is its audio URL, which is the key the cache files itself under.
        PodcastStreamCache.cache(appContext).keys.filter { it in seen }
            .forEach { PodcastStreamCache.remove(appContext, it) }
        seen.filter { downloads.isDownloaded(it) }.forEach { downloads.remove(it) }
    }

    suspend fun markUnseen(episodeId: String) {
        dao().unmarkSeen(episodeId)
        _episodes.value = _episodes.value.map { if (it.id == episodeId) it.copy(seen = false) else it }
    }

    // ---- Downloads ----
    // Kept in [PodcastDownloads]; the rest of the app reaches it through this property.

    val downloads = PodcastDownloads(appContext) { dao() }

    // ---- Sleep timer ----
    // Kept in [PodcastSleepTimer], same as above.

    val sleepTimer = PodcastSleepTimer(appContext, this)

    // ---- Player ----

    private val _playerState = MutableStateFlow(PodcastPlayerUiState())
    val playerState: StateFlow<PodcastPlayerUiState> = _playerState

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshPlayerState()
            // A pause is the most likely last thing before the process dies, so don't wait for the ticker.
            if (!isPlaying) {
                saveCurrentProgress()
                progressScope.launch { flushPendingStorageCleanup() }
            }
            trackProgressWhilePlaying(isPlaying)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refreshPlayerState()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player has already moved on, so the episode that just ended is only known from what
            // was tracked; an automatic transition means it played all the way through.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) trackedEpisodeId?.let { finishEpisode(it) }
            trackEpisode(mediaItem?.mediaId)
            refreshPlayerState()
            // The player has moved off whatever was waiting on it to let go of its file.
            progressScope.launch { flushPendingStorageCleanup() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) trackedEpisodeId?.let { finishEpisode(it) }
            refreshPlayerState()
        }
    }

    private val controllerHolder = MediaControllerHolder(
        appContext,
        PodcastPlaybackService::class.java,
        PodcastPlaybackService.CMD_STOP_ALL
    ) { c ->
        c.addListener(playerListener)
        // The service can already be playing something started in an earlier app session.
        trackEpisode(c.currentMediaItem?.mediaId)
        trackProgressWhilePlaying(c.isPlaying)
        refreshPlayerState()
    }

    val controller: MediaController? get() = controllerHolder.controller

    suspend fun ensureController(): MediaController = controllerHolder.ensure()

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

    // ---- Listening progress ----
    // Where each started episode was left off, so playing it again picks up there. Written every
    // [PROGRESS_SAVE_INTERVAL_MS] while playing and on every pause, since the playback service can be
    // killed with the app without any chance to save on the way out.

    /** Saved positions keyed by episode id. Episodes never started, or finished, are absent. */
    val progress: kotlinx.coroutines.flow.Flow<Map<String, PodcastEpisodeProgress>>
        get() = dao().observeProgress().map { rows -> rows.associateBy { it.episodeId } }

    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    /** The episode the player is on, kept here because a transition reports it only after the fact. */
    private var trackedEpisodeId: String? = null

    /** When the player moved onto [trackedEpisodeId], to tell a real end of episode from a short source. */
    private var trackedSinceMs: Long = 0L

    /** How many times each episode was replayed after ending on the spot, so a bad source can't loop. */
    private val shortSourceRetries = mutableMapOf<String, Int>()

    private fun trackEpisode(episodeId: String?) {
        trackedEpisodeId = episodeId
        trackedSinceMs = SystemClock.elapsedRealtime()
    }

    /**
     * Gives an episode its retry budget back once it has played long enough to prove the source is
     * fine. Without this a single recovery spent the budget for the whole process, and a genuinely
     * bad source hit weeks later got fewer attempts than the first one did.
     */
    private fun clearShortSourceRetries(episodeId: String) {
        shortSourceRetries.remove(episodeId)
    }

    private fun trackProgressWhilePlaying(isPlaying: Boolean) {
        progressJob?.cancel()
        if (!isPlaying) return
        progressJob = progressScope.launch {
            while (true) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                saveCurrentProgress()
            }
        }
    }

    /** Writes where the player currently is. Runs on the main thread: the controller demands it. */
    private fun saveCurrentProgress() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val positionMs = c.currentPosition
        val durationMs = c.duration.takeIf { it > 0 } ?: 0L
        progressScope.launch { persistProgress(id, positionMs, durationMs) }
    }

    /**
     * Played to the end: heard, and back to the beginning if it is ever played again.
     *
     * An end reported within [INSTANT_END_MS] of the episode starting is not a real one: the source
     * stopped short (a truncated download, a resume position past the end of the media). Taking those
     * at face value is what marked an episode heard and skipped to the next one on resume, so they go
     * through [recoverFromShortSource] instead.
     */
    private fun finishEpisode(episodeId: String) {
        if (SystemClock.elapsedRealtime() - trackedSinceMs < INSTANT_END_MS) {
            recoverFromShortSource(episodeId)
            return
        }
        progressScope.launch { markSeen(episodeId) }
    }

    /**
     * Plays [episodeId] again after removing what made it end on the spot: a download that can only be
     * truncated, or a saved position the stream doesn't reach.
     *
     * Where it was left off is worth more than one failed attempt, so the saved position survives the
     * first try: the episode restarts a minute before it, which is enough when the position is only
     * slightly past what the media really holds. Only a second failure gives up on resuming and starts
     * over from the beginning, and nothing is tried a third time, so a source that keeps ending short
     * stops rather than looping.
     */
    private fun recoverFromShortSource(episodeId: String) {
        Log.w(TAG, "Episode $episodeId ended right after it started, not marking it heard")
        val attempt = (shortSourceRetries[episodeId] ?: 0) + 1
        if (attempt > 2) return
        val episode = _episodes.value.firstOrNull { it.id == episodeId } ?: return
        shortSourceRetries[episodeId] = attempt
        progressScope.launch {
            // A downloaded file that ends on the spot is truncated, whatever the position: it goes,
            // and the same position is tried again on the stream.
            if (downloads.isDownloaded(episodeId)) {
                downloads.remove(episodeId)
                AppSnackbar.show("Téléchargement incomplet, lecture en ligne")
                runCatching { playEpisode(episode) }
                return@launch
            }
            val savedMs = dao().getProgress(episodeId)?.positionMs ?: 0L
            when {
                // Nothing was resumed, so the position isn't what ended it: the source itself is bad.
                savedMs <= 0L -> AppSnackbar.show("Lecture impossible pour cet épisode")
                attempt == 1 -> {
                    val retryFromMs = (savedMs - SHORT_SOURCE_BACK_MS).coerceAtLeast(0L)
                    runCatching { playEpisode(episode, startPositionMsOverride = retryFromMs) }
                }
                else -> {
                    dao().deleteProgress(episodeId)
                    AppSnackbar.show("Reprise impossible, l'épisode redémarre au début")
                    runCatching { playEpisode(episode, startPositionMsOverride = 0L) }
                }
            }
        }
    }

    private suspend fun persistProgress(episodeId: String, positionMs: Long, durationMs: Long) {
        // The last stretch is credits and outro, so stopping there counts as having heard the episode.
        if (durationMs > 0 && positionMs >= durationMs - PROGRESS_FINISHED_MARGIN_MS) {
            markSeen(episodeId)
            return
        }
        // Below the threshold there is nothing worth resuming, and a stale row from a previous listen
        // would otherwise survive a restart from the beginning.
        if (positionMs < PROGRESS_MIN_MS) {
            dao().deleteProgress(episodeId)
            return
        }
        clearShortSourceRetries(episodeId)
        dao().upsertProgress(PodcastEpisodeProgress(episodeId, positionMs, durationMs, System.currentTimeMillis()))
    }

    /**
     * Plays [episode], queuing the rest of [queue] (defaults to just this episode) right after it.
     * A DEEZER-sourced episode can't actually play (Deezer doesn't expose episode audio itself; see
     * [resolveDeezerShowToRss]): addFavorite no longer creates these, but a favorite followed before
     * that fix still has this source on disk until re-followed, so this fails clearly instead of crashing.
     */
    suspend fun playEpisode(
        episode: PodcastEpisode,
        queue: List<PodcastEpisode> = listOf(episode),
        startPositionMsOverride: Long? = null
    ) {
        if (episode.source == PodcastSource.DEEZER) {
            throw IllegalStateException("Ce podcast doit être réajouté depuis la recherche pour pouvoir être lu")
        }
        val startIndex = queue.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        // One player at a time, the mirror of what DeezerRepository does when music starts: two of
        // this app's playback services running at once means two foreground services fighting over
        // audio focus.
        withContext(Dispatchers.Main) { appContext.deezerRepository.stopAll() }
        val controller = ensureController()
        val items = queue.map { buildMediaItem(it) }
        // Picked up where it was left off, silently. An episode never started, or already finished,
        // has no saved row and starts at zero. Kept inside what the feed says the episode lasts: a
        // position at or past the end makes the player end the episode on the spot, which reads as
        // "finished" and skips to the next one.
        // [startPositionMsOverride] is what a retry after an instant end plays from, saved row or not.
        val savedMs = (startPositionMsOverride ?: dao().getProgress(episode.id)?.positionMs ?: 0L)
            .coerceAtLeast(0L)
        val feedDurationMs = (episode.durationSec ?: 0) * 1000L
        val startPositionMs = if (feedDurationMs > 0) {
            savedMs.coerceAtMost((feedDurationMs - PROGRESS_FINISHED_MARGIN_MS).coerceAtLeast(0L))
        } else savedMs
        withContext(Dispatchers.Main) {
            trackEpisode(episode.id)
            controller.setMediaItems(items, startIndex, startPositionMs)
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
        sleepTimer.cancel()
        saveCurrentProgress()
        progressJob?.cancel()
        trackEpisode(null)
        controllerHolder.stop()
        _playerState.value = PodcastPlayerUiState()
    }

    /** The episode the player is on, as a full episode. Read by [sleepTimer]. */
    internal fun currentEpisode(): PodcastEpisode? =
        _playerState.value.episodeId?.let { id -> _episodes.value.firstOrNull { it.id == id } }

    private fun buildMediaItem(episode: PodcastEpisode): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .setArtworkUri(episode.podcastArtworkUrl?.let { Uri.parse(it) })
            .build()
        // Always the audio URL: downloaded or not, the player reads it through the cache, which
        // serves whatever is held and streams the rest.
        return MediaItem.Builder()
            .setUri(Uri.parse(episode.audioUrl))
            .setMediaId(episode.id)
            .setMediaMetadata(metadata)
            .build()
    }
}
