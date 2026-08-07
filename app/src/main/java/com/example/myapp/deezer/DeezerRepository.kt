package com.example.myapp.deezer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.myapp.podcastRepository
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Outcome of adding a track to a playlist: it was added, it was already there, or the playlist doesn't exist. */
enum class PlaylistAddResult { ADDED, DUPLICATE, NO_PLAYLIST }

/**
 * Singleton (held by MyApplication, like FlashcardRepository). Owns:
 *  - the Deezer session lifecycle (token refresh on error),
 *  - the shared MediaController the whole tool drives, plus a StateFlow of player state for the UI,
 *  - the decrypted-stream disk cache and the permanent offline mirror,
 *  - library data access (favorites, playlists, search).
 *
 * Playback resolves lazily: a MediaItem carries only `dzr://<sngId>?q=<quality>`, and DeezerDataSource
 * fetches a fresh CDN URL and decrypts on the fly. That is why whole playlists can be queued cheaply.
 */
class DeezerRepository(private val appContext: Context) : CdnResolver {

    companion object {
        // Fixed low quality everywhere, streaming and offline downloads alike: on a Bluetooth speaker
        // or earbuds the codec on the wire recompresses the audio anyway, so 320 bought nothing audible
        // while costing 2.5x the cache space and struggling harder on a weak connection. One quality
        // also means the offline mirror and live playback always share the same cache key, so a
        // downloaded track is always a guaranteed cache hit, never a silent miss.
        val DEFAULT_QUALITY = DeezerQuality.MP3_128
        const val CACHE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024 // 5 GB
        const val CACHE_LIMIT_LABEL = "5 Go"
        private const val RESOLVE_ATTEMPTS = 3
        private const val QUEUED_NEXT_KEY = "deezer_queued_next"
        private const val SOURCE_TYPE_KEY = "deezer_source_type"
        private const val SOURCE_ID_KEY = "deezer_source_id"
        private const val TAG = "DeezerRepository"
    }

    private val api = DeezerApi()
    val settings = DeezerSettings(appContext)

    private val sessionMutex = Mutex()
    private var session: DeezerSession? = null

    private val cacheDir: File by lazy { File(appContext.cacheDir, "deezer").apply { mkdirs() } }

    /**
     * One SimpleCache per process for the decrypted audio, keyed by the stable dzr:// URI (SNG_ID +
     * quality). Only ever holds liked tracks: DeezerPlaybackService's write sink checks [isFavorite]
     * before persisting anything, and [setFavorites] purges whatever the cache holds that isn't liked
     * whenever the favorites list changes. The 5 GB LRU cap is therefore just a safety net.
     */
    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    /** The permanent Best pépites mirror: its own uncapped cache, read before [streamCache] on playback. */
    val offline: DeezerOfflineLibrary by lazy { DeezerOfflineLibrary(appContext, this) }

    // Fire and forget IO work that must survive the screen that started it (played-track metadata writes).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val playedTracksLoaded: Job

    /** Drops stream cache entries left over from a previous default quality: they can never be served
     *  again since playback always requests the current quality, so they are just dead weight. */
    private fun purgeStaleQualityCacheAsync() {
        ioScope.launch {
            runCatching {
                val suffix = "?q=${DEFAULT_QUALITY.name}"
                streamCache.keys.filterNot { it.endsWith(suffix) }.forEach { streamCache.removeResource(it) }
            }
        }
    }

    // ---- Session ----

    suspend fun hasArl(): Boolean = settings.arl.first().isNotBlank()

    /**
     * Whether the device currently has validated internet access. Checked before a network call
     * that would otherwise hang for its whole timeout while offline, so the Best pépites mirror
     * (fully downloaded) opens and plays instantly with no connection instead of waiting it out.
     */
    private fun hasNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun ensureSession(forceRefresh: Boolean = false): DeezerSession = sessionMutex.withLock {
        val current = session
        if (!forceRefresh && current != null && !current.isStale()) return current
        val arl = settings.arl.first()
        if (arl.isBlank()) throw DeezerApiException("No ARL configured", tokenError = true)
        api.bootstrapSession(arl).also { session = it }
    }

    private suspend fun <T> withTokenRetry(block: suspend (DeezerSession) -> T): T {
        val s = ensureSession()
        return try {
            block(s)
        } catch (e: DeezerApiException) {
            if (e.tokenError) block(ensureSession(forceRefresh = true)) else throw e
        }
    }

    // ---- Library data ----

    /** Fetches the owner's playlists from the network. Prefer the [playlists] flow for the landing screen. */
    suspend fun fetchPlaylists(): List<DeezerPlaylist> = withTokenRetry { api.getPlaylists(it) }

    /**
     * The playlist's tracks, from the network. Falls back to the offline mirror when the network is
     * gone, which is what lets Best pépites be opened, shuffled and played with no connection.
     */
    suspend fun playlistTracks(playlistId: String): List<DeezerTrack> {
        if (!hasNetwork()) offline.tracksFor(playlistId)?.let { return it }
        return try {
            withTokenRetry { api.getPlaylistTracks(it, playlistId) }.also { rememberMembership(playlistId, it) }
        } catch (e: Exception) {
            offline.tracksFor(playlistId) ?: throw e
        }
    }
    suspend fun search(query: String): List<DeezerTrack> = api.searchTracks(query)

    /** The catalog's best matching artist for [query], or null. Powers the search screen's artist shortcut. */
    suspend fun searchArtist(query: String): DeezerArtist? = api.searchArtists(query, limit = 1).firstOrNull()

    /** Shuffles [artist]'s most popular tracks. Untagged: not a favorites/playlist source. */
    suspend fun shuffleArtist(artist: DeezerArtist) = shuffleTracks(api.artistTopTracks(artist.id))

    // ---- Podcast catalog (public, no auth) ----
    // Browsing Deezer's podcast catalog needs no session at all; only actually streaming an
    // episode (below, in the player section) requires the authenticated get_url pipeline.

    suspend fun searchPodcastShows(query: String): List<DeezerPodcastShow> = api.searchPodcastShows(query)
    suspend fun podcastChart(): List<DeezerPodcastShow> = api.podcastChart()
    suspend fun podcastEpisodes(showId: String): List<DeezerPodcastEpisode> = api.podcastEpisodes(showId)

    // ---- Library snapshot (stale-while-revalidate) ----
    // The landing screen reads favorites + playlists from these flows. On launch we seed them from the
    // last on-disk snapshot (instant), then revalidate over the network and persist the fresh result.

    private val _playlists = MutableStateFlow<List<DeezerPlaylist>?>(null)
    val playlists: StateFlow<List<DeezerPlaylist>?> = _playlists

    private val libraryCacheFile: File by lazy { File(appContext.filesDir, "deezer_library.json") }
    private val libraryMutex = Mutex()
    private val snapshotWriteMutex = Mutex()
    @Volatile private var diskSeedTried = false

    /** Seeds the flows from disk (once), then revalidates over the network. Safe to call on every screen entry. */
    suspend fun ensureLibrary() {
        if (!diskSeedTried && (_favorites.value == null || _playlists.value == null)) {
            diskSeedTried = true
            withContext(Dispatchers.IO) { DeezerLibraryCache.read(libraryCacheFile) }?.let { snap ->
                if (_favorites.value == null) setFavorites(snap.favorites)
                if (_playlists.value == null) _playlists.value = snap.playlists
            }
        }
        refreshLibrary()
    }

    /**
     * Fetches favorites and playlists concurrently, updates the flows, and persists the fresh snapshot.
     * A no-op while offline: the screen keeps showing whatever was last seeded from disk instead of
     * hanging on a doomed network call and surfacing an error for a state that is expected.
     */
    suspend fun refreshLibrary(): Unit = libraryMutex.withLock {
        if (!hasNetwork()) return@withLock
        flushPendingFavorites()
        withTokenRetry { session ->
            coroutineScope {
                val favsDeferred = async { api.getFavorites(session) }
                val plsDeferred = async { api.getPlaylists(session) }
                val favs = favsDeferred.await()
                val pls = plsDeferred.await()
                setFavorites(favs)
                _playlists.value = pls
                writeSnapshot(favs, pls)
            }
        }
    }

    /** Serializes and writes the snapshot on IO, guarded so concurrent writers can't corrupt the file. */
    private suspend fun writeSnapshot(favs: List<DeezerTrack>, pls: List<DeezerPlaylist>) =
        withContext(Dispatchers.IO) {
            snapshotWriteMutex.withLock {
                DeezerLibraryCache.write(libraryCacheFile, DeezerLibrarySnapshot(favs, pls))
            }
        }

    /**
     * Rewrites the snapshot from the current in-memory favorites so a like/unlike survives a kill even
     * when offline. Falls back to the on-disk playlists when they aren't loaded yet, so we never wipe them.
     */
    private suspend fun persistSnapshot() {
        val favs = _favorites.value ?: return
        val pls = _playlists.value
            ?: withContext(Dispatchers.IO) { DeezerLibraryCache.read(libraryCacheFile) }?.playlists
            ?: emptyList()
        writeSnapshot(favs, pls)
    }

    // ---- Favorites cache ----
    // Loaded once (all of them, paged past the old 200 cap) and kept in memory. This single list
    // powers the total count, shuffle-all, and the filled/empty heart state on every row.

    private val _favorites = MutableStateFlow<List<DeezerTrack>?>(null)
    val favorites: StateFlow<List<DeezerTrack>?> = _favorites

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    private val favoritesMutex = Mutex()

    /** Ensures the favorites cache is populated. Re-fetches only when [force] or not yet loaded. */
    suspend fun ensureFavorites(force: Boolean = false): List<DeezerTrack> = favoritesMutex.withLock {
        flushPendingFavorites()
        if (!force) _favorites.value?.let { return it }
        val list = withTokenRetry { api.getFavorites(it) }
        setFavorites(list)
        list
    }

    private fun setFavorites(list: List<DeezerTrack>) {
        _favorites.value = list
        _favoriteIds.value = list.mapTo(HashSet()) { it.sngId }
        purgeCacheOfNonFavoritesAsync()
    }

    /** The stream cache only holds liked tracks: whenever the favorites list changes (a toggle, or a
     *  fresh fetch), anything cached under a sngId that isn't liked anymore gets dropped right away. */
    private fun purgeCacheOfNonFavoritesAsync() {
        val liked = _favoriteIds.value
        ioScope.launch {
            runCatching {
                streamCache.keys.forEach { key ->
                    val sngId = sngIdFromCacheKey(key) ?: return@forEach
                    if (sngId !in liked) streamCache.removeResource(key)
                }
            }
        }
    }

    fun isFavorite(sngId: String): Boolean = _favoriteIds.value.contains(sngId)

    /**
     * Marks a stream-failure replacement (see [DeezerPlaybackService.findAndApplyReplacement]) as
     * liked without touching the real favorites list, so the Now Playing heart and the media
     * notification still read "liked" for what is, in spirit, still the user's favorite song until
     * they either unlike it or make the swap permanent via "Corriger". Any subsequent real favorites
     * write (a toggle, a fresh fetch) recomputes [favoriteIds] from the authoritative list and drops
     * this override on its own.
     */
    fun markTemporaryFavorite(sngId: String) {
        _favoriteIds.value = _favoriteIds.value + sngId
    }

    /**
     * Likes or unlikes [track]. The local cache always updates right away, online or not, so the heart
     * responds instantly. When there is no connection (or the call drops mid flight), the change is
     * queued to disk instead of sent, and [flushPendingFavorites] retries it the next time a Deezer
     * screen is opened with a connection.
     */
    suspend fun toggleFavorite(track: DeezerTrack) {
        val liked = isFavorite(track.sngId)
        val cur = _favorites.value ?: emptyList()
        setFavorites(if (liked) cur.filterNot { it.sngId == track.sngId } else listOf(track) + cur)
        persistSnapshot()

        val add = !liked
        if (!hasNetwork()) {
            queuePendingFavorite(track.sngId, add)
            return
        }
        try {
            withTokenRetry { if (add) api.addFavorite(it, track.sngId) else api.removeFavorite(it, track.sngId) }
            clearPendingFavorite(track.sngId)
        } catch (e: IOException) {
            queuePendingFavorite(track.sngId, add)
        }
    }

    // ---- Pending favorites (offline like/unlike) ----
    // A like/unlike made with no connection is applied locally right away and its intent (add or
    // remove) recorded here, keyed by sngId, so the app can resend it later without the user having to
    // redo anything. Persisted to disk so it survives the app being killed while still offline.

    private val pendingFavoritesFile: File by lazy { File(appContext.filesDir, "deezer_pending_favorites.json") }
    private val pendingFavoritesMutex = Mutex()
    private var pendingFavoritesCache: LinkedHashMap<String, Boolean>? = null

    private suspend fun pendingFavoritesMap(): LinkedHashMap<String, Boolean> {
        pendingFavoritesCache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val map = LinkedHashMap<String, Boolean>()
                if (pendingFavoritesFile.exists()) {
                    val root = DeezerLibraryCache.json.parseToJsonElement(pendingFavoritesFile.readText()).jsonObject
                    root.forEach { (sngId, add) -> map[sngId] = add.jsonPrimitive.content == "add" }
                }
                map
            }.getOrDefault(LinkedHashMap())
        }
        pendingFavoritesCache = loaded
        return loaded
    }

    private suspend fun writePendingFavorites(map: Map<String, Boolean>) = withContext(Dispatchers.IO) {
        runCatching {
            if (map.isEmpty()) pendingFavoritesFile.delete()
            else pendingFavoritesFile.writeText(
                buildJsonObject { map.forEach { (sngId, add) -> put(sngId, if (add) "add" else "remove") } }.toString()
            )
        }
    }

    private suspend fun queuePendingFavorite(sngId: String, add: Boolean): Unit = pendingFavoritesMutex.withLock {
        val map = pendingFavoritesMap()
        map[sngId] = add
        writePendingFavorites(map)
    }

    private suspend fun clearPendingFavorite(sngId: String): Unit = pendingFavoritesMutex.withLock {
        val map = pendingFavoritesMap()
        if (map.remove(sngId) != null) writePendingFavorites(map)
    }

    /**
     * Resends every queued like/unlike. Called opportunistically whenever a Deezer screen refreshes
     * the library or the favorites cache, so a change made offline reaches Deezer the next time the
     * tool is opened with a connection. Stops at the first network failure so the rest stays queued.
     */
    suspend fun flushPendingFavorites(): Unit = pendingFavoritesMutex.withLock {
        if (!hasNetwork()) return@withLock
        val map = pendingFavoritesMap()
        if (map.isEmpty()) return@withLock
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val (sngId, add) = iter.next()
            try {
                withTokenRetry { if (add) api.addFavorite(it, sngId) else api.removeFavorite(it, sngId) }
                iter.remove()
            } catch (e: IOException) {
                break
            } catch (e: DeezerApiException) {
                Log.w(TAG, "Dropping pending favorite $sngId after API error", e)
                iter.remove()
            }
        }
        writePendingFavorites(map)
    }

    // ---- Playlist membership ----
    // The track ids of every playlist we have looked at, so an add can refuse a track that is already
    // there instead of creating a duplicate. Seeded by playlistTracks and kept in sync with our own
    // adds and removes; a playlist edited elsewhere is only re-read on the next fetch.

    private val playlistTrackIds = ConcurrentHashMap<String, MutableSet<String>>()

    private fun rememberMembership(playlistId: String, tracks: List<DeezerTrack>) {
        playlistTrackIds[playlistId] = ConcurrentHashMap.newKeySet<String>().apply { tracks.forEach { add(it.sngId) } }
    }

    /** The cached id set for [playlistId], fetching the playlist once if we have never read it. */
    private suspend fun membership(playlistId: String): MutableSet<String> {
        playlistTrackIds[playlistId]?.let { return it }
        playlistTracks(playlistId)
        return playlistTrackIds.getOrPut(playlistId) { ConcurrentHashMap.newKeySet() }
    }

    // ---- "Best pépites" quick-add ----

    @Volatile private var bestPepitesId: String? = null

    /**
     * Resolves (and caches) the id of the owner's "Best pépites" playlist, or null if none exists.
     * Reads the already loaded playlists first, so it also answers offline once the library is seeded.
     */
    suspend fun bestPepitesPlaylistId(): String? =
        bestPepitesId ?: (_playlists.value ?: fetchPlaylists())
            .firstOrNull { normalizeName(it.title).contains("pepite") }?.id?.also { bestPepitesId = it }

    /** Adds [track] to the owner's "Best pépites" playlist, unless it is already in it. */
    suspend fun addToBestPepites(track: DeezerTrack): PlaylistAddResult {
        val pid = bestPepitesPlaylistId() ?: return PlaylistAddResult.NO_PLAYLIST
        return addToPlaylist(pid, track)
    }

    /** Loads the "Best pépites" contents so [bestPepitesContains] can answer without a network call. */
    suspend fun ensureBestPepitesLoaded() {
        membership(bestPepitesPlaylistId() ?: return)
    }

    /** Whether [sngId] is in "Best pépites", or null while the playlist has never been read. */
    fun bestPepitesContains(sngId: String): Boolean? =
        bestPepitesId?.let { playlistTrackIds[it] }?.contains(sngId)

    /** Adds [track] to any of the owner's playlists, unless it is already in it. */
    suspend fun addToPlaylist(playlistId: String, track: DeezerTrack): PlaylistAddResult {
        val ids = membership(playlistId)
        if (track.sngId in ids) return PlaylistAddResult.DUPLICATE
        withTokenRetry { api.addSongToPlaylist(it, playlistId, track.sngId) }
        ids += track.sngId
        return PlaylistAddResult.ADDED
    }

    /** Lowercases and strips accents + non-letters so "Best pépites 💎" matches on "pepite". */
    private fun normalizeName(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    /** Removes [sngId] from [playlistId]. */
    suspend fun removeFromPlaylist(playlistId: String, sngId: String) {
        withTokenRetry { api.removeSongFromPlaylist(it, playlistId, sngId) }
        playlistTrackIds[playlistId]?.remove(sngId)
    }

    // ---- Broken release recovery ----
    // Favorites and playlists pin a specific sngId at the time a track was liked/added. An artist who
    // re-released the same song under a different sngId can leave that pinned release unstreamable
    // (get_url refuses it) while a fresh catalog search turns up a working one. This is what lets
    // playback swap in a working release live, and what the "Corriger" snackbar action applies for real.

    /**
     * Searches the catalog for a different release of [track] (same normalized title and artist, a
     * different sngId) and returns the first one that actually resolves a stream, or null if none does.
     */
    suspend fun findReplacement(track: DeezerTrack): DeezerTrack? {
        val artistKey = normalizeName(track.artist)
        val titleKey = normalizeName(track.title)
        val candidates = try {
            search("${track.title} ${track.artist}")
        } catch (e: Exception) {
            return null
        }
        val matches = candidates.filter {
            it.sngId != track.sngId && normalizeName(it.artist) == artistKey && normalizeName(it.title) == titleKey
        }.take(5)
        for (candidate in matches) {
            val works = try {
                resolveStream(candidate.sngId, DEFAULT_QUALITY)
                true
            } catch (e: Exception) {
                false
            }
            if (works) return candidate
        }
        return null
    }

    /** Removes [old] from favorites/[source]'s playlist and adds [new] in its place. */
    suspend fun applyReplacement(old: DeezerTrack, new: DeezerTrack, source: TrackSource) {
        when (source) {
            is TrackSource.Favorites -> {
                withTokenRetry { api.removeFavorite(it, old.sngId); api.addFavorite(it, new.sngId) }
                val cur = _favorites.value ?: emptyList()
                setFavorites(listOf(new) + cur.filterNot { it.sngId == old.sngId })
                persistSnapshot()
            }
            is TrackSource.Playlist -> {
                withTokenRetry {
                    api.removeSongFromPlaylist(it, source.id, old.sngId)
                    api.addSongToPlaylist(it, source.id, new.sngId)
                }
                playlistTrackIds[source.id]?.let { it.remove(old.sngId); it.add(new.sngId) }
            }
        }
    }

    // ---- CDN resolution (called from DeezerDataSource on ExoPlayer's loading thread) ----

    /**
     * Called on ExoPlayer's loading thread, once per track (and again on seek), which is why this
     * one blocks. Anything already inside a coroutine calls [resolveStream] instead.
     */
    override fun resolve(sngId: String, quality: String): String =
        runBlocking { resolveStream(sngId, DeezerQuality.fromName(quality)) }

    /**
     * Retries a few times with a fresh session, because a single failure here kills the whole
     * queue: the failure must also surface as an IOException, the only kind Media3 considers
     * retriable.
     */
    private suspend fun resolveStream(sngId: String, quality: DeezerQuality): String {
        var last: Exception? = null
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            try {
                // Any retry re-bootstraps the session: a stale sid is the most common cause here.
                val session = ensureSession(forceRefresh = attempt > 0)
                return api.resolveStream(session, sngId, quality).cdnUrl
            } catch (e: Exception) {
                last = e
                if (attempt < RESOLVE_ATTEMPTS - 1) delay(500L * (attempt + 1))
            }
        }
        throw IOException("Deezer stream resolve failed for $sngId: ${last?.message}", last)
    }

    // ---- Player ----

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState

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
            val token = SessionToken(appContext, ComponentName(appContext, DeezerPlaybackService::class.java))
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
            _playerState.value = PlayerUiState()
            return
        }
        val m = c.mediaMetadata
        _playerState.value = PlayerUiState(
            hasItem = true,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            coverUrl = m.artworkUri?.toString(),
            sngId = c.currentMediaItem?.mediaId,
            source = c.currentMediaItem?.let { sourceOf(it) }
        )
    }

    /**
     * Replaces the queue with [tracks] and starts playing at [startIndex]. Never touches
     * shuffleModeEnabled: ExoPlayer's own shuffle order assigns newly inserted/replaced items a random
     * slot (see [addToQueue] and the playback service's error recovery), which breaks "play next" and
     * makes "previous" lose tracks whenever the queue changes after the fact. A shuffled play is instead
     * a plain queue whose order was already randomized in Kotlin before it gets here.
     */
    private suspend fun setQueue(tracks: List<DeezerTrack>, startIndex: Int, source: TrackSource?) {
        stopPodcastPlayback()
        val controller = ensureController()
        queuedTracks.clear()
        val items = tracks.map { buildMediaItem(it, source = source) }
        withContext(Dispatchers.Main) {
            controller.setMediaItems(items, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    /** Sets the queue to [tracks] starting at [startIndex] and plays in order. */
    suspend fun playTracks(tracks: List<DeezerTrack>, startIndex: Int, source: TrackSource? = null) {
        if (tracks.isEmpty()) return
        setQueue(tracks, startIndex, source)
    }

    /** Queues every favorite and plays them shuffled, starting from a random one. */
    suspend fun shuffleFavorites() = shuffleTracks(ensureFavorites(), TrackSource.Favorites)

    /** Loads [playlistId]'s tracks and plays them shuffled, starting from a random one. */
    suspend fun shufflePlaylist(playlistId: String) = shuffleTracks(playlistTracks(playlistId), TrackSource.Playlist(playlistId))

    /** Shuffles [list] in Kotlin and queues the result, so the whole queue's order is fixed from the start. */
    suspend fun shuffleTracks(list: List<DeezerTrack>, source: TrackSource? = null) {
        if (list.isEmpty()) return
        setQueue(list.shuffled(), 0, source)
    }

    /**
     * Every track fully present on disk right now, from anywhere: the Best pépites mirror and the
     * general stream cache, which only ever holds liked tracks that ordinary playback has fetched. A
     * track only counts once it is completely downloaded, so a few seconds of buffering from a quick
     * listen does not count, and one unliked (or aged out of the 5 GB LRU cap) silently drops off this list.
     */
    suspend fun downloadedTracks(): List<DeezerTrack> {
        // queuedTracks must hold last session's persisted plays before the stream cache scan below means anything.
        playedTracksLoaded.join()
        return withContext(Dispatchers.IO) {
            val seen = HashSet<String>()
            val result = ArrayList<DeezerTrack>()
            // Checks the key actually present in the cache, not one rebuilt from DEFAULT_QUALITY: a track
            // cached before the quality changed (or under a different quality in general) must still match.
            fun tryAdd(sngId: String, key: String, track: DeezerTrack?, cache: SimpleCache) {
                if (track == null || !seen.add(sngId)) return
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (length > 0 && cache.isCached(key, 0, length)) result += track
            }
            offline.allTracks().forEach { tryAdd(it.sngId, cacheKeyFor(it.sngId), it, offline.cache) }
            streamCache.keys.forEach { key -> sngIdFromCacheKey(key)?.let { tryAdd(it, key, queuedTracks[it], streamCache) } }
            result
        }
    }

    /** Shuffles everything currently downloaded (see [downloadedTracks]), read straight off disk with no network call. */
    suspend fun shuffleDownloaded() = shuffleTracks(downloadedTracks())

    private fun cacheKeyFor(sngId: String): String = "dzr://$sngId?q=${DEFAULT_QUALITY.name}"

    /** Not private: DeezerPlaybackService's write sink needs this to gate caching on [isFavorite]. */
    internal fun sngIdFromCacheKey(key: String): String? =
        key.takeIf { it.startsWith("dzr://") }?.removePrefix("dzr://")?.substringBefore("?")?.ifBlank { null }

    /**
     * Inserts [track] right after the currently playing item, ahead of whatever the active playlist
     * had queued there. Ignores that playlist entirely otherwise: repeated calls stack up in the order
     * they were tapped, right after the last one already inserted this way. Starts playback if nothing
     * is queued yet.
     */
    suspend fun addToQueue(track: DeezerTrack) {
        val controller = ensureController()
        withContext(Dispatchers.Main) {
            if (controller.mediaItemCount == 0) {
                stopPodcastPlayback()
                controller.setMediaItem(buildMediaItem(track))
                controller.prepare()
                controller.play()
                return@withContext
            }
            var insertIndex = controller.currentMediaItemIndex + 1
            while (insertIndex < controller.mediaItemCount && controller.getMediaItemAt(insertIndex).isQueuedNext) {
                insertIndex++
            }
            controller.addMediaItem(insertIndex, buildMediaItem(track, queuedNext = true))
        }
    }

    private val MediaItem.isQueuedNext: Boolean
        get() = mediaMetadata.extras?.getBoolean(QUEUED_NEXT_KEY) == true

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun positionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    /**
     * Pauses playback, removes the notification, and stops the playback service entirely. Also drops
     * our own MediaController: a service stays alive as long as any client is bound to it, so without
     * this the service's own stopSelf() would have no real effect until the app itself goes away.
     */
    fun stopAll() {
        val c = controller
        if (c != null) {
            c.sendCustomCommand(SessionCommand(DeezerPlaybackService.CMD_STOP_ALL, Bundle.EMPTY), Bundle.EMPTY)
            c.release()
            controller = null
            controllerDeferred = null
        } else {
            // The service outlives the process's controller when playback was started in an earlier
            // app session and kept going in the background. Nothing is bound to it then, so stopping
            // the service outright is what actually ends it.
            appContext.stopService(Intent(appContext, DeezerPlaybackService::class.java))
        }
        _playerState.value = PlayerUiState()
    }

    /**
     * Ends whatever the podcast player was doing. One player at a time: two of this app's playback
     * services running at once means two foreground services fighting over audio focus, and it is
     * the case that used to take the app down when music was started over a playing podcast.
     * Main thread only, like every other MediaController call.
     */
    private suspend fun stopPodcastPlayback() {
        withContext(Dispatchers.Main) { appContext.podcastRepository.stopAll() }
    }

    /**
     * The tracks of the current queue, by SNG_ID. A MediaItem only carries title/artist/cover, so this
     * is how the notification actions and the now playing sheet recover the full track (album, cover
     * md5) they need to like it or push it into a playlist. Also doubles as the metadata behind
     * [downloadedTracks]: every track ever queued lands here, persisted to disk (see below) so a track
     * fully cached by the general LRU stream cache is still identifiable after the app process dies.
     */
    private val queuedTracks = ConcurrentHashMap<String, DeezerTrack>()

    fun trackById(sngId: String): DeezerTrack? = queuedTracks[sngId]

    // ---- Played-track metadata persistence ----
    // Bounded by the cache itself at write time (only sngIds still present in a cache are kept), so
    // this file tracks the 5 GB LRU cache's contents without growing forever.

    private val playedTracksFile: File by lazy { File(appContext.filesDir, "deezer_played_tracks.json") }
    private val playedTracksWriteMutex = Mutex()
    @Volatile private var playedTracksWritePending = false

    // Runs last in the constructor: loadPlayedTracksAsync and purgeStaleQualityCacheAsync are launched
    // onto the IO dispatcher here and can start running on another thread before this constructor
    // returns, so every property they touch (playedTracksFile, streamCache, ...) must already be
    // assigned by this point, not just declared further down the file.
    init {
        playedTracksLoaded = loadPlayedTracksAsync()
        purgeStaleQualityCacheAsync()
    }

    private fun loadPlayedTracksAsync(): Job = ioScope.launch {
        runCatching {
            if (!playedTracksFile.exists()) return@launch
            val arr = DeezerLibraryCache.json.parseToJsonElement(playedTracksFile.readText()).jsonArray
            arr.forEach {
                val t = DeezerLibraryCache.trackFromJson(it.jsonObject)
                if (t.sngId.isNotBlank()) queuedTracks.putIfAbsent(t.sngId, t)
            }
        }.onFailure { Log.w(TAG, "Failed to load played-track metadata", it) }
    }

    /** Debounced so queuing a whole playlist doesn't trigger one disk write per track. */
    private fun persistPlayedTracksAsync() {
        if (playedTracksWritePending) return
        playedTracksWritePending = true
        ioScope.launch {
            delay(2_000L)
            playedTracksWritePending = false
            playedTracksWriteMutex.withLock {
                val cachedIds = HashSet<String>()
                streamCache.keys.forEach { sngIdFromCacheKey(it)?.let(cachedIds::add) }
                offline.cache.keys.forEach { sngIdFromCacheKey(it)?.let(cachedIds::add) }
                val arr = buildJsonArray {
                    queuedTracks.values.filter { it.sngId in cachedIds }.forEach { add(DeezerLibraryCache.trackToJson(it)) }
                }
                runCatching { playedTracksFile.writeText(arr.toString()) }
            }
        }
    }

    /** Builds a queued MediaItem for [track], tagged with [source] so a stream failure can be corrected at its origin. */
    fun mediaItemFor(track: DeezerTrack, source: TrackSource?): MediaItem = buildMediaItem(track, source = source)

    /** The [TrackSource] a queued MediaItem was tagged with, if any. */
    fun sourceOf(mediaItem: MediaItem): TrackSource? {
        val extras = mediaItem.mediaMetadata.extras ?: return null
        return when (extras.getString(SOURCE_TYPE_KEY)) {
            "favorites" -> TrackSource.Favorites
            "playlist" -> extras.getString(SOURCE_ID_KEY)?.let { TrackSource.Playlist(it) }
            else -> null
        }
    }

    private fun buildMediaItem(
        track: DeezerTrack,
        queuedNext: Boolean = false,
        source: TrackSource? = null
    ): MediaItem {
        queuedTracks[track.sngId] = track
        persistPlayedTracksAsync()
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.coverUrl()?.let { Uri.parse(it) })
        val extras = Bundle()
        if (queuedNext) extras.putBoolean(QUEUED_NEXT_KEY, true)
        when (source) {
            is TrackSource.Favorites -> extras.putString(SOURCE_TYPE_KEY, "favorites")
            is TrackSource.Playlist -> {
                extras.putString(SOURCE_TYPE_KEY, "playlist")
                extras.putString(SOURCE_ID_KEY, source.id)
            }
            null -> {}
        }
        if (!extras.isEmpty) metadata.setExtras(extras)
        return MediaItem.Builder()
            .setUri(Uri.parse(cacheKeyFor(track.sngId)))
            .setMediaId(track.sngId)
            .setMediaMetadata(metadata.build())
            .build()
    }
}
