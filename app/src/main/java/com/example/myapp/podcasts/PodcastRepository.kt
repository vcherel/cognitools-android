package com.example.myapp.podcasts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.myapp.USER_AGENT
import com.example.myapp.deezerRepository
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.httpGet
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "PodcastRepository"

private val RADIO_FRANCE_STATIONS = listOf("franceinter", "franceculture", "franceinfo", "francemusique", "mouv", "fip")

// Secured past the timer's end, so nodding off with a couple of minutes left over still plays out.
private const val SLEEP_PRELOAD_MARGIN_MS = 120_000L

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
        // A heard episode has nothing left to resume: the saved position would only show a stale bar
        // and restart it mid-way the next time it is played.
        dao().deleteProgress(episodeId)
        _episodes.value = _episodes.value.map { if (it.id == episodeId) it.copy(seen = true) else it }
    }

    suspend fun markUnseen(episodeId: String) {
        dao().unmarkSeen(episodeId)
        _episodes.value = _episodes.value.map { if (it.id == episodeId) it.copy(seen = false) else it }
    }

    // ---- Downloads ----
    // Episodes are downloaded straight to a file, no separate DB table: "downloaded" is derived from
    // the file's presence, so it can never drift out of sync with what's actually on disk. The file is
    // named after a hash of the episode id rather than the id itself: an episode id *is* its audio URL,
    // and its slashes (plus its length) make it an unusable file name, which is why writing the
    // download used to fail outright for every episode.

    private val downloadsDir: File by lazy { File(appContext.filesDir, "podcast_downloads").apply { mkdirs() } }

    /** File-system safe, stable name for [episodeId]. Public: screens match it against [downloadedKeys]. */
    fun downloadKey(episodeId: String): String =
        MessageDigest.getInstance("SHA-256").digest(episodeId.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun downloadFile(episodeId: String): File = File(downloadsDir, "${downloadKey(episodeId)}.audio")

    private val _downloadedKeys = MutableStateFlow(
        downloadsDir.listFiles()?.filter { it.extension == "audio" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
    )
    val downloadedKeys: StateFlow<Set<String>> = _downloadedKeys

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds

    /** How much of each running download is on disk, 0..1, keyed by episode id. */
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    /** [keys] is passed in so a composable reading [downloadedKeys] recomposes when it changes. */
    fun isDownloaded(episodeId: String, keys: Set<String> = _downloadedKeys.value): Boolean =
        downloadKey(episodeId) in keys

    /** Downloads [episode]'s audio to disk so it can play with no connection. No-op if already downloaded/downloading. */
    suspend fun downloadEpisode(episode: PodcastEpisode) {
        if (isDownloaded(episode.id) || episode.id in _downloadingIds.value) return
        if (episode.audioUrl.isBlank()) throw IOException("Pas de flux audio pour cet épisode")
        val tmp = File(downloadsDir, "${downloadKey(episode.id)}.tmp")
        _downloadingIds.value = _downloadingIds.value + episode.id
        try {
            withContext(Dispatchers.IO) {
                val conn = openAudio(episode.audioUrl)
                try {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total <= 0) continue
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _downloadProgress.value =
                                        _downloadProgress.value + (episode.id to (percent / 100f).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (!tmp.renameTo(downloadFile(episode.id))) throw IOException("Impossible d'enregistrer le téléchargement")
            }
            _downloadedKeys.value = _downloadedKeys.value + downloadKey(episode.id)
            playFromFileIfCurrent(episode)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        } finally {
            _downloadingIds.value = _downloadingIds.value - episode.id
            _downloadProgress.value = _downloadProgress.value - episode.id
        }
    }

    /**
     * Opens [url] for reading, following redirects by hand: podcast enclosures usually point at a
     * tracking prefix (Podtrac, Chartable, Megaphone…) that bounces to the real CDN, and
     * HttpURLConnection silently refuses to follow a redirect that switches between http and https.
     */
    private fun openAudio(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirection sans destination")
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return conn
        }
        throw IOException("Trop de redirections")
    }

    /**
     * Swaps the streaming source for the file just downloaded, at the same position. Without it the
     * episode would sit on disk while the player keeps pulling the network stream it started with,
     * so playback would still die the moment the phone goes offline.
     */
    private suspend fun playFromFileIfCurrent(episode: PodcastEpisode) = withContext(Dispatchers.Main) {
        val c = controller ?: return@withContext
        if (c.currentMediaItem?.mediaId != episode.id) return@withContext
        val position = c.currentPosition
        val wasPlaying = c.playWhenReady
        c.replaceMediaItem(c.currentMediaItemIndex, buildMediaItem(episode))
        c.seekTo(position)
        c.prepare()
        if (wasPlaying) c.play()
    }

    /** Deletes [episodeId]'s downloaded file, if any. */
    fun removeDownload(episodeId: String) {
        downloadFile(episodeId).delete()
        _downloadedKeys.value = _downloadedKeys.value - downloadKey(episodeId)
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
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshPlayerState()
            // A pause is the most likely last thing before the process dies, so don't wait for the ticker.
            if (!isPlaying) saveCurrentProgress()
            trackProgressWhilePlaying(isPlaying)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refreshPlayerState()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player has already moved on, so the episode that just ended is only known from what
            // was tracked; an automatic transition means it played all the way through.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) trackedEpisodeId?.let { finishEpisode(it) }
            trackedEpisodeId = mediaItem?.mediaId
            refreshPlayerState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) trackedEpisodeId?.let { finishEpisode(it) }
            refreshPlayerState()
        }
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
                // The service can already be playing something started in an earlier app session.
                trackedEpisodeId = c.currentMediaItem?.mediaId
                trackProgressWhilePlaying(c.isPlaying)
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

    /** Played to the end: heard, and back to the beginning if it is ever played again. */
    private fun finishEpisode(episodeId: String) {
        progressScope.launch { markSeen(episodeId) }
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
        dao().upsertProgress(PodcastEpisodeProgress(episodeId, positionMs, durationMs, System.currentTimeMillis()))
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
        // One player at a time, the mirror of what DeezerRepository does when music starts: two of
        // this app's playback services running at once means two foreground services fighting over
        // audio focus.
        withContext(Dispatchers.Main) { appContext.deezerRepository.stopAll() }
        val controller = ensureController()
        val items = queue.map { buildMediaItem(it) }
        // Picked up where it was left off, silently. An episode never started, or already finished,
        // has no saved row and starts at zero.
        val startPositionMs = dao().getProgress(episode.id)?.positionMs ?: 0L
        withContext(Dispatchers.Main) {
            trackedEpisodeId = episode.id
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
        cancelSleepTimer()
        saveCurrentProgress()
        progressJob?.cancel()
        trackedEpisodeId = null
        val c = controller
        if (c != null) {
            c.sendCustomCommand(SessionCommand(PodcastPlaybackService.CMD_STOP_ALL, Bundle.EMPTY), Bundle.EMPTY)
            c.release()
            controller = null
            controllerDeferred = null
        } else {
            // The service outlives the process's controller when playback was started in an earlier
            // app session and kept going in the background. Nothing is bound to it then, so stopping
            // the service outright is what actually ends it.
            appContext.stopService(Intent(appContext, PodcastPlaybackService::class.java))
        }
        _playerState.value = PodcastPlayerUiState()
    }

    // ---- Sleep timer ----

    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sleepTimerJob: Job? = null
    private var sleepPreloadJob: Job? = null

    private val _sleepTimerEndAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndAt: StateFlow<Long?> = _sleepTimerEndAt

    /**
     * How much of the audio needed to reach the sleep timer's end is on the phone, 0..1, or null
     * when no timer is running. At 1 the rest plays with no connection, so airplane mode is safe.
     */
    private val _sleepCacheProgress = MutableStateFlow<Float?>(null)
    val sleepCacheProgress: StateFlow<Float?> = _sleepCacheProgress

    /** Pauses playback in [minutes]. Replaces any timer already running. */
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerEndAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = timerScope.launch {
            delay(minutes * 60_000L)
            withContext(Dispatchers.Main) { controller?.pause() }
            _sleepTimerEndAt.value = null
            _sleepCacheProgress.value = null
        }
        preloadForSleepTimer(minutes)
    }

    /**
     * Falling asleep usually means the phone is about to go offline (airplane mode), so the audio
     * needed to reach the timer's end is pulled in right away. Only that stretch, and only into the
     * stream cache: it is not a download, the episode isn't listed as downloaded, and playback keeps
     * streaming past it normally while there is still a connection.
     */
    private fun preloadForSleepTimer(minutes: Int) {
        sleepPreloadJob?.cancel()
        val episode = currentEpisode()
        if (episode == null || episode.audioUrl.isBlank()) {
            _sleepCacheProgress.value = null
            return
        }
        // A fully downloaded episode plays from its own file, with nothing left to secure.
        if (isDownloaded(episode.id)) {
            _sleepCacheProgress.value = 1f
            return
        }
        _sleepCacheProgress.value = 0f
        sleepPreloadJob = timerScope.launch {
            val (positionMs, durationMs) = withContext(Dispatchers.Main) {
                val c = controller
                (c?.currentPosition ?: 0L) to (c?.duration ?: C.TIME_UNSET)
            }
            runCatching { cacheForSleepTimer(episode, positionMs, durationMs, minutes) }
                .onSuccess { _sleepCacheProgress.value = 1f }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Sleep pre-fetch failed for ${episode.title}, downloading it whole", e)
                    downloadWholeForSleepTimer(episode, positionMs, durationMs, minutes)
                }
        }
    }

    /**
     * Fallback when the partial pre-fetch can't be done, typically a host that doesn't serve byte
     * ranges: the whole episode is downloaded instead. That costs more data and does show it as a
     * downloaded episode, which is the price of being sure it plays through the night.
     */
    private suspend fun downloadWholeForSleepTimer(
        episode: PodcastEpisode,
        positionMs: Long,
        durationMs: Long,
        minutes: Int
    ) = coroutineScope {
        val knownDurationMs = durationMs.takeIf { it > 0 } ?: ((episode.durationSec ?: 0) * 1000L)
        val neededMs = (positionMs + minutes * 60_000L + SLEEP_PRELOAD_MARGIN_MS)
            .let { if (knownDurationMs > 0) it.coerceAtMost(knownDurationMs) else it }
        // The download runs from the start of the file, so what matters is when it gets past the
        // timer's end, not when the whole episode is there: the badge is safe from that point on.
        val neededFraction = if (knownDurationMs > 0) neededMs.toFloat() / knownDurationMs else 1f
        val mirror = launch {
            downloadProgress.collect { progress ->
                progress[episode.id]?.let { done ->
                    _sleepCacheProgress.value = (done / neededFraction).coerceIn(0f, 1f)
                }
            }
        }
        try {
            // A manual download of the same episode already running would make downloadEpisode a
            // no-op, so wait it out instead of calling the night secured the moment it returns.
            if (episode.id in _downloadingIds.value) {
                _downloadedKeys.first { downloadKey(episode.id) in it }
            } else {
                downloadEpisode(episode)
            }
            _sleepCacheProgress.value = 1f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Progress stays where it stopped: below 100% is exactly the warning that airplane mode
            // would cut the episode off.
            Log.w(TAG, "Sleep download failed for ${episode.title}", e)
        } finally {
            mirror.cancel()
        }
    }

    /** Caches the byte range covering [positionMs] to the timer's end (plus a margin), and nothing else. */
    private suspend fun cacheForSleepTimer(
        episode: PodcastEpisode,
        positionMs: Long,
        durationMs: Long,
        minutes: Int
    ) = withContext(Dispatchers.IO) {
        val totalBytes = PodcastStreamCache.knownContentLength(appContext, episode.audioUrl)
            .takeIf { it > 0 }
            ?: runCatching {
                val conn = openAudio(episode.audioUrl)
                conn.contentLengthLong.also { conn.disconnect() }
            }.getOrDefault(C.LENGTH_UNSET.toLong())
        val knownDurationMs = durationMs.takeIf { it > 0 } ?: ((episode.durationSec ?: 0) * 1000L)
        // The file's own average bitrate when both ends are known; without them a 128 kbps guess,
        // high enough for spoken word that it over-fetches rather than leaving the timer uncovered.
        val bytesPerMs = if (totalBytes > 0 && knownDurationMs > 0) totalBytes.toDouble() / knownDurationMs else 16.0

        val start = (positionMs * bytesPerMs).toLong().coerceAtLeast(0L)
        var length = ((minutes * 60_000L + SLEEP_PRELOAD_MARGIN_MS) * bytesPerMs).toLong()
        if (totalBytes > 0) length = length.coerceAtMost(totalBytes - start)
        if (length <= 0) return@withContext

        val spec = DataSpec.Builder()
            .setUri(Uri.parse(episode.audioUrl))
            .setPosition(start)
            .setLength(length)
            .build()
        val writer = CacheWriter(
            PodcastStreamCache.cacheDataSourceFactory(appContext).createDataSource(),
            spec,
            null,
            CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
                if (requestLength > 0) {
                    _sleepCacheProgress.value = (bytesCached.toFloat() / requestLength).coerceIn(0f, 1f)
                }
            }
        )
        // runInterruptible so cancelling the timer actually aborts the fetch in flight.
        runInterruptible { writer.cache() }
    }

    private fun currentEpisode(): PodcastEpisode? =
        _playerState.value.episodeId?.let { id -> _episodes.value.firstOrNull { it.id == id } }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepPreloadJob?.cancel()
        sleepPreloadJob = null
        _sleepTimerEndAt.value = null
        _sleepCacheProgress.value = null
    }

    private fun buildMediaItem(episode: PodcastEpisode): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .setArtworkUri(episode.podcastArtworkUrl?.let { Uri.parse(it) })
            .build()
        val localFile = downloadFile(episode.id)
        val uri = if (localFile.exists()) Uri.fromFile(localFile) else Uri.parse(episode.audioUrl)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(episode.id)
            .setMediaMetadata(metadata)
            .build()
    }
}
