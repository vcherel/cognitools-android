package com.example.myapp.deezer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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
        // Fixed quality: MP3 320, with automatic fallback to 128 inside resolveStream.
        val DEFAULT_QUALITY = DeezerQuality.MP3_320
        const val CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        const val CACHE_LIMIT_LABEL = "2 Go"
        private const val RESOLVE_ATTEMPTS = 3
        private const val QUEUED_NEXT_KEY = "deezer_queued_next"
        private const val SOURCE_TYPE_KEY = "deezer_source_type"
        private const val SOURCE_ID_KEY = "deezer_source_id"
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

    /** The permanent Best pépites mirror: its own uncapped cache, read before [streamCache] on playback. */
    val offline: DeezerOfflineLibrary by lazy { DeezerOfflineLibrary(appContext, this) }

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

    /**
     * The playlist's tracks, from the network. Falls back to the offline mirror when the network is
     * gone, which is what lets Best pépites be opened, shuffled and played with no connection.
     */
    suspend fun playlistTracks(playlistId: String): List<DeezerTrack> =
        try {
            withTokenRetry { api.getPlaylistTracks(it, playlistId) }.also { rememberMembership(playlistId, it) }
        } catch (e: Exception) {
            offline.tracksFor(playlistId) ?: throw e
        }
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
                resolve(candidate.sngId, DEFAULT_QUALITY.name)
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
     * Called on ExoPlayer's loading thread, once per track (and again on seek). Retries a few times
     * with a fresh session, because a single failure here kills the whole queue: the failure must
     * also surface as an IOException, the only kind Media3 considers retriable.
     */
    override fun resolve(sngId: String, quality: String): String = runBlocking {
        val q = DeezerQuality.fromName(quality)
        var last: Exception? = null
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            try {
                // Any retry re-bootstraps the session: a stale sid is the most common cause here.
                val s = ensureSession(forceRefresh = attempt > 0)
                return@runBlocking api.resolveStream(s, sngId, q).cdnUrl
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
            sngId = c.currentMediaItem?.mediaId
        )
    }

    /** Sets the queue to [tracks] starting at [startIndex] and plays in order. */
    suspend fun playTracks(tracks: List<DeezerTrack>, startIndex: Int, source: TrackSource? = null) {
        if (tracks.isEmpty()) return
        val controller = ensureController()
        queuedTracks.clear()
        val items = tracks.map { buildMediaItem(it, DEFAULT_QUALITY, source = source) }
        withContext(Dispatchers.Main) {
            controller.shuffleModeEnabled = false
            controller.setMediaItems(items, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    /** Queues every favorite and plays them shuffled, starting from a random one. */
    suspend fun shuffleFavorites() = shuffleTracks(ensureFavorites(), TrackSource.Favorites)

    /** Loads [playlistId]'s tracks and plays them shuffled, starting from a random one. */
    suspend fun shufflePlaylist(playlistId: String) = shuffleTracks(playlistTracks(playlistId), TrackSource.Playlist(playlistId))

    /** Queues [list] with shuffle on and plays from a random position. */
    suspend fun shuffleTracks(list: List<DeezerTrack>, source: TrackSource? = null) {
        if (list.isEmpty()) return
        val controller = ensureController()
        queuedTracks.clear()
        val items = list.map { buildMediaItem(it, DEFAULT_QUALITY, source = source) }
        withContext(Dispatchers.Main) {
            controller.shuffleModeEnabled = true
            controller.setMediaItems(items, Random.nextInt(items.size), 0L)
            controller.prepare()
            controller.play()
        }
    }

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
                controller.setMediaItem(buildMediaItem(track, DEFAULT_QUALITY))
                controller.prepare()
                controller.play()
                return@withContext
            }
            var insertIndex = controller.currentMediaItemIndex + 1
            while (insertIndex < controller.mediaItemCount && controller.getMediaItemAt(insertIndex).isQueuedNext) {
                insertIndex++
            }
            controller.addMediaItem(insertIndex, buildMediaItem(track, DEFAULT_QUALITY, queuedNext = true))
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
        val c = controller ?: return
        c.sendCustomCommand(SessionCommand(DeezerPlaybackService.CMD_STOP_ALL, Bundle.EMPTY), Bundle.EMPTY)
        c.release()
        controller = null
        controllerDeferred = null
        _playerState.value = PlayerUiState()
    }

    /**
     * The tracks of the current queue, by SNG_ID. A MediaItem only carries title/artist/cover, so this
     * is how the notification actions and the now playing sheet recover the full track (album, cover
     * md5) they need to like it or push it into a playlist.
     */
    private val queuedTracks = ConcurrentHashMap<String, DeezerTrack>()

    fun trackById(sngId: String): DeezerTrack? = queuedTracks[sngId]

    /** Builds a queued MediaItem for [track], tagged with [source] so a stream failure can be corrected at its origin. */
    fun mediaItemFor(track: DeezerTrack, source: TrackSource?): MediaItem = buildMediaItem(track, DEFAULT_QUALITY, source = source)

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
        quality: DeezerQuality,
        queuedNext: Boolean = false,
        source: TrackSource? = null
    ): MediaItem {
        queuedTracks[track.sngId] = track
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
            .setUri(Uri.parse("dzr://${track.sngId}?q=${quality.name}"))
            .setMediaId(track.sngId)
            .setMediaMetadata(metadata.build())
            .build()
    }
}
