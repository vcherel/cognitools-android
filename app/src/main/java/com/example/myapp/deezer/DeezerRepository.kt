package com.example.myapp.deezer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Normalizer
import kotlin.random.Random

/**
 * Singleton (held by MyApplication, like FlashcardRepository). Owns:
 *  - the Deezer session lifecycle (token refresh on error),
 *  - the shared MediaController the whole tool drives, plus a StateFlow of player state for the UI,
 *  - the decrypted-stream disk cache,
 *  - library data access (favorites, playlists, search).
 *
 * Playback resolves lazily: a MediaItem carries only `dzr://<sngId>?q=<quality>`, and DeezerDataSource
 * fetches a fresh CDN URL and decrypts on the fly. That is why whole playlists can be queued cheaply.
 */
class DeezerRepository(private val appContext: Context) : CdnResolver {

    companion object {
        // Fixed quality: MP3 320, with automatic fallback to 128 inside resolveStream.
        val DEFAULT_QUALITY = DeezerQuality.MP3_320
        const val CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        const val CACHE_LIMIT_LABEL = "2 Go"
    }

    private val api = DeezerApi()
    val settings = DeezerSettings(appContext)

    private val sessionMutex = Mutex()
    private var session: DeezerSession? = null

    private val cacheDir: File by lazy { File(appContext.cacheDir, "deezer").apply { mkdirs() } }

    /** One SimpleCache per process for the decrypted audio. Keyed by the stable dzr:// URI (SNG_ID + quality). */
    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    // ---- Session ----

    suspend fun hasArl(): Boolean = settings.arl.first().isNotBlank()

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
    suspend fun playlistTracks(playlistId: String): List<DeezerTrack> =
        withTokenRetry { api.getPlaylistTracks(it, playlistId) }
    suspend fun search(query: String): List<DeezerTrack> = api.searchTracks(query)

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

    /** Fetches favorites and playlists concurrently, updates the flows, and persists the fresh snapshot. */
    suspend fun refreshLibrary(): Unit = libraryMutex.withLock {
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
        if (!force) _favorites.value?.let { return it }
        val list = withTokenRetry { api.getFavorites(it) }
        setFavorites(list)
        list
    }

    private fun setFavorites(list: List<DeezerTrack>) {
        _favorites.value = list
        _favoriteIds.value = list.mapTo(HashSet()) { it.sngId }
    }

    fun isFavorite(sngId: String): Boolean = _favoriteIds.value.contains(sngId)

    /** Likes or unlikes [track], updating both Deezer and the local cache. */
    suspend fun toggleFavorite(track: DeezerTrack) {
        val liked = isFavorite(track.sngId)
        withTokenRetry { if (liked) api.removeFavorite(it, track.sngId) else api.addFavorite(it, track.sngId) }
        val cur = _favorites.value ?: emptyList()
        setFavorites(if (liked) cur.filterNot { it.sngId == track.sngId } else listOf(track) + cur)
        persistSnapshot()
    }

    // ---- "Best pépites" quick-add ----

    @Volatile private var bestPepitesId: String? = null

    /** Resolves (and caches) the id of the owner's "Best pépites" playlist, or null if none exists. */
    suspend fun bestPepitesPlaylistId(): String? =
        bestPepitesId ?: fetchPlaylists().firstOrNull { normalizeName(it.title).contains("pepite") }?.id?.also { bestPepitesId = it }

    /** Adds [track] to the owner's "Best pépites" playlist. Returns false if no such playlist exists. */
    suspend fun addToBestPepites(track: DeezerTrack): Boolean {
        val pid = bestPepitesPlaylistId() ?: return false
        withTokenRetry { api.addSongToPlaylist(it, pid, track.sngId) }
        return true
    }

    /** Adds [track] to any of the owner's playlists. */
    suspend fun addToPlaylist(playlistId: String, track: DeezerTrack) {
        withTokenRetry { api.addSongToPlaylist(it, playlistId, track.sngId) }
    }

    /** Lowercases and strips accents + non-letters so "Best pépites 💎" matches on "pepite". */
    private fun normalizeName(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    /** Removes [sngId] from [playlistId]. */
    suspend fun removeFromPlaylist(playlistId: String, sngId: String) {
        withTokenRetry { api.removeSongFromPlaylist(it, playlistId, sngId) }
    }

    // ---- CDN resolution (called from DeezerDataSource on ExoPlayer's loading thread) ----

    override fun resolve(sngId: String, quality: String): String = runBlocking {
        val q = DeezerQuality.fromName(quality)
        withTokenRetry { s -> api.resolveStream(s, sngId, q).cdnUrl }
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
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            coverUrl = m.artworkUri?.toString(),
            sngId = c.currentMediaItem?.mediaId
        )
    }

    /** Sets the queue to [tracks] starting at [startIndex] and plays in order. */
    suspend fun playTracks(tracks: List<DeezerTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val controller = ensureController()
        val items = tracks.map { buildMediaItem(it, DEFAULT_QUALITY) }
        withContext(Dispatchers.Main) {
            controller.shuffleModeEnabled = false
            controller.setMediaItems(items, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    /** Queues every favorite and plays them shuffled, starting from a random one. */
    suspend fun shuffleFavorites() = playShuffled(ensureFavorites())

    /** Loads [playlistId]'s tracks and plays them shuffled, starting from a random one. */
    suspend fun shufflePlaylist(playlistId: String) = playShuffled(playlistTracks(playlistId))

    /** Queues [list] with shuffle on and plays from a random position. */
    private suspend fun playShuffled(list: List<DeezerTrack>) {
        if (list.isEmpty()) return
        val controller = ensureController()
        val items = list.map { buildMediaItem(it, DEFAULT_QUALITY) }
        withContext(Dispatchers.Main) {
            controller.shuffleModeEnabled = true
            controller.setMediaItems(items, Random.nextInt(items.size), 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun positionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    private fun buildMediaItem(track: DeezerTrack, quality: DeezerQuality): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse("dzr://${track.sngId}?q=${quality.name}"))
            .setMediaId(track.sngId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.coverUrl()?.let { Uri.parse(it) })
                    .build()
            )
            .build()
}
