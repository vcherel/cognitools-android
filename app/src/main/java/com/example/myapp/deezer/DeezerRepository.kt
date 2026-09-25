package com.example.myapp.deezer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.myapp.deaccented
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** A library refresh landing this soon after the last one is the same one asked twice. */
private const val LIBRARY_REFRESH_DEDUPE_MS = 10_000L

/** Outcome of adding a track to a playlist: it was added, it was already there, or the playlist doesn't exist. */
enum class PlaylistAddResult { ADDED, DUPLICATE, NO_PLAYLIST }

/** The stream cache key of [sngId]: the `dzr://` URI a queued MediaItem carries. */
internal fun deezerCacheKey(sngId: String): String = "dzr://$sngId?q=${DeezerRepository.DEFAULT_QUALITY.name}"

internal fun sngIdFromCacheKey(key: String): String? =
    key.takeIf { it.startsWith("dzr://") }?.removePrefix("dzr://")?.substringBefore("?")?.ifBlank { null }

/**
 * Singleton (held by MyApplication, like FlashcardRepository). Owns:
 *  - the Deezer session lifecycle (token refresh on error),
 *  - the decrypted-stream disk cache and the permanent offline mirror,
 *  - library data access (favorites, playlists, search).
 * Playback itself is [player].
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
        private const val RESOLVE_ATTEMPTS = 3
        private const val REPLACEMENT_CANDIDATES = 3
        private const val TAG = "DeezerRepository"
    }

    private val api = DeezerApi()
    val settings = DeezerSettings(appContext)

    private val pendingFavorites = DeezerPendingFavorites(appContext, hasNetwork = ::hasNetwork) { sngId, add ->
        withTokenRetry { if (add) api.addFavorite(it, sngId) else api.removeFavorite(it, sngId) }
    }

    private val sessionMutex = Mutex()
    private var session: DeezerSession? = null

    /**
     * Every Deezer failure with its full cause chain, what the "Journal d'erreurs" screen shows and
     * what gets copied from it: a release build's on-screen message is often an obfuscated class
     * name, useless for finding out what the API changed.
     */
    val errorLog = RollingLog(appContext, TAG, "deezer_errors.txt", maxBytes = 200_000, keepLines = 300)

    /** Records [e] under [what] and hands back the line written, for a snackbar's copy action. */
    fun logError(what: String, e: Throwable): String {
        val line = "$what: ${errorLog.describe(e)}"
        errorLog.log(line)
        return line
    }

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

    /** The daily "Découvertes du jour" batch of tracks to like or ignore. */
    val discoveries: DeezerDiscoveries by lazy { DeezerDiscoveries(appContext, this) }

    /** The favorites count day by day, behind the curve on the library screen. */
    val favoritesHistory: DeezerFavoritesHistory by lazy { DeezerFavoritesHistory(appContext) }

    /** The MediaController, the queue and everything the now playing surfaces drive. */
    val player = DeezerPlayer(appContext, this)

    // Fire and forget IO work that must survive the screen that started it.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        pendingFavorites.flushOnNetwork(ioScope)
    }

    suspend fun hasArl(): Boolean = settings.arl.first().isNotBlank()

    /**
     * Whether the device currently has validated internet access. Checked before a network call
     * that would otherwise hang for its whole timeout while offline, so the Best pépites mirror
     * (fully downloaded) opens and plays instantly with no connection instead of waiting it out.
     */
    fun hasNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun ensureSession(forceRefresh: Boolean = false): DeezerSession = sessionMutex.withLock {
        val current = session
        if (!forceRefresh && current != null && !current.isStale()) return current
        val arl = settings.arl.first()
        if (arl.isBlank()) throw DeezerApiException("No ARL configured", tokenError = true)
        try {
            api.bootstrapSession(arl).also { session = it }
        } catch (e: Exception) {
            if (e !is CancellationException) logError("session", e)
            throw e
        }
    }

    private suspend fun <T> withTokenRetry(block: suspend (DeezerSession) -> T): T {
        val s = ensureSession()
        return try {
            try {
                block(s)
            } catch (e: DeezerApiException) {
                if (e.tokenError) block(ensureSession(forceRefresh = true)) else throw e
            }
        } catch (e: Exception) {
            if (e !is CancellationException) logError("api", e)
            throw e
        }
    }

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

    /** One pull of Deezer's Flow. Each call advances the radio, so calling it again gives different tracks. */
    suspend fun flowTracks(): List<DeezerTrack> = withTokenRetry { api.flowTracks(it) }

    /** Tracks Deezer considers close to [sngId]. */
    suspend fun trackMix(sngId: String): List<DeezerTrack> = withTokenRetry { api.trackMix(it, sngId) }

    /** The artists on the owner's Deezer profile, i.e. who Deezer thinks they listen to. */
    suspend fun profileArtists(): List<DeezerArtist> = withTokenRetry { api.profileArtists(it) }

    /** Every artist behind the owner's favorites and behind Best pépites, ids included. */
    suspend fun libraryArtists(): List<DeezerArtist> {
        val pepites = listOfNotNull(runCatching { bestPepitesPlaylistId() }.getOrNull())
        return withTokenRetry { api.libraryArtists(it, pepites) }
    }

    /** [artistId]'s whole discography, newest first. Public catalog, no session needed. */
    suspend fun artistReleases(artistId: String, artistName: String): List<DeezerRelease> =
        api.artistReleases(artistId, artistName)

    /** [release]'s tracks. Public catalog, no session needed. */
    suspend fun albumTracks(release: DeezerRelease): List<DeezerTrack> = api.albumTracks(release)

    /** The current contents of "Best pépites", or empty when there is no such playlist. */
    suspend fun bestPepitesTracks(): List<DeezerTrack> =
        bestPepitesPlaylistId()?.let { playlistTracks(it) } ?: emptyList()

    /** The catalog's best matching artist for [query], or null. Powers the search screen's artist shortcut. */
    suspend fun searchArtist(query: String): DeezerArtist? = api.searchArtists(query, limit = 1).firstOrNull()

    /** Shuffles [artist]'s most popular tracks. Untagged: not a favorites/playlist source. */
    suspend fun shuffleArtist(artist: DeezerArtist) = player.shuffleTracks(api.artistTopTracks(artist.id))

    /** [artistId]'s most popular tracks, for the artist screen's "Titres populaires" section. */
    suspend fun artistTopTracks(artistId: String): List<DeezerTrack> = api.artistTopTracks(artistId)

    // Browsing Deezer's podcast catalog needs no session at all.

    suspend fun searchPodcastShows(query: String): List<DeezerPodcastShow> = api.searchPodcastShows(query)
    suspend fun podcastChart(): List<DeezerPodcastShow> = api.podcastChart()

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
        seedLibraryFromDisk()
        refreshLibrary()
    }

    /** Fills whatever flow is still empty from the last snapshot, once per process. */
    private suspend fun seedLibraryFromDisk() {
        if (diskSeedTried || (_favorites.value != null && _playlists.value != null)) return
        diskSeedTried = true
        withContext(Dispatchers.IO) { DeezerLibraryCache.read(libraryCacheFile) }?.let { snap ->
            // complete = false: the disk snapshot seeds the UI instantly, but only the network
            // fetch that follows is allowed to purge the stream cache. A stale or truncated
            // snapshot driving the purge is how downloaded liked tracks used to vanish.
            if (_favorites.value == null) setFavorites(snap.favorites, complete = false)
            if (_playlists.value == null) _playlists.value = snap.playlists
        }
    }

    /**
     * Fetches favorites and playlists concurrently, updates the flows, and persists the fresh snapshot.
     * A no-op while offline: the screen keeps showing whatever was last seeded from disk instead of
     * hanging on a doomed network call and surfacing an error for a state that is expected.
     */
    suspend fun refreshLibrary(force: Boolean = false): Unit = libraryMutex.withLock {
        if (!hasNetwork()) return@withLock
        // Two callers a moment apart (a cold shuffle's background refresh, then the music screen's
        // own) would page every favorite twice. [force] is for a changed ARL, a new account entirely.
        if (!force && SystemClock.elapsedRealtime() - lastLibraryRefreshMs < LIBRARY_REFRESH_DEDUPE_MS) return@withLock
        pendingFavorites.flush()
        withTokenRetry { session ->
            coroutineScope {
                val favsDeferred = async { api.getFavorites(session) }
                val plsDeferred = async { api.getPlaylists(session) }
                val favs = favsDeferred.await()
                val pls = plsDeferred.await()
                setFavorites(favs)
                _playlists.value = pls
                // A "Best pépites" created since the last lookup is found again on the next one.
                bestPepitesLookedUp = false
                writeSnapshot(favs, pls)
                lastLibraryRefreshMs = SystemClock.elapsedRealtime()
            }
        }
    }

    @Volatile private var lastLibraryRefreshMs = Long.MIN_VALUE / 2

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

    // Loaded once (all of them, paged past the old 200 cap) and kept in memory. This single list
    // powers the total count, shuffle-all, and the filled/empty heart state on every row.

    private val _favorites = MutableStateFlow<List<DeezerTrack>?>(null)
    val favorites: StateFlow<List<DeezerTrack>?> = _favorites

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    private val favoritesMutex = Mutex()

    /**
     * Ensures the favorites cache is populated. Re-fetches only when [force] or not yet loaded, and a
     * cold process takes the disk snapshot first: paging every favorite over the network took long
     * enough that the menu's shuffle button sat spinning, and the process was killed in the
     * background before it got anywhere. The network fetch then runs behind, refreshing the list.
     */
    suspend fun ensureFavorites(force: Boolean = false): List<DeezerTrack> = favoritesMutex.withLock {
        pendingFavorites.flush()
        if (!force) {
            _favorites.value?.let { return it }
            seedLibraryFromDisk()
            _favorites.value?.let { seeded ->
                ioScope.launch { runCatching { refreshLibrary() } }
                return seeded
            }
        }
        val list = withTokenRetry { api.getFavorites(it) }
        setFavorites(list)
        list
    }

    /** The disk half of [ensureFavorites] alone: no network, nothing to wait on after the first call. */
    suspend fun seedFavoritesFromDisk() = favoritesMutex.withLock { seedLibraryFromDisk() }

    // The largest favorites list seen this run (seeded from the disk snapshot, then every fetch).
    // A "complete" list that is a big drop from this is treated as a bad fetch: the UI takes it, but
    // the cache purge is skipped so a glitchy response can't wipe the downloaded tracks.
    @Volatile private var maxFavoritesSeen = 0

    /**
     * [complete] says whether [list] is the whole favorites list. It isn't when a like lands before the
     * library has ever loaded (the notification heart on a cold process): the local list is then that
     * one track, and purging the cache against it would wipe every downloaded song. The purge waits for
     * the next real fetch instead.
     */
    private fun setFavorites(list: List<DeezerTrack>, complete: Boolean = true) {
        _favorites.value = list
        _favoriteIds.value = list.mapTo(HashSet()) { it.sngId }
        val suspiciousDrop = maxFavoritesSeen > 20 && list.size < maxFavoritesSeen / 2
        maxFavoritesSeen = maxOf(maxFavoritesSeen, list.size)
        when {
            !complete -> ioScope.launch { favoritesHistory.record(list, complete = false) }
            suspiciousDrop -> Log.w(TAG, "Skipping stream-cache purge: favorites came back as ${list.size}, down from $maxFavoritesSeen")
            else -> {
                purgeCacheOfNonFavoritesAsync()
                ioScope.launch { favoritesHistory.record(list) }
            }
        }
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

    /**
     * Rewrites the local favorites list through [transform] and persists it. A list that was never
     * loaded is transformed from empty and flagged incomplete, see [setFavorites].
     */
    private suspend fun updateFavorites(transform: (List<DeezerTrack>) -> List<DeezerTrack>) {
        val loaded = _favorites.value
        setFavorites(transform(loaded ?: emptyList()), complete = loaded != null)
        persistSnapshot()
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
        _favoriteIds.update { it + sngId }
    }

    /**
     * Likes or unlikes [track]. The local cache always updates right away, online or not, so the heart
     * responds instantly. When there is no connection (or the call drops mid flight), the change is
     * queued to disk instead of sent, and [DeezerPendingFavorites.flush] retries it the next time a Deezer
     * screen is opened with a connection. Unliking the track being played skips to the next one.
     */
    suspend fun toggleFavorite(track: DeezerTrack) {
        val liked = isFavorite(track.sngId)
        if (liked) player.controller?.let { if (it.currentMediaItem?.mediaId == track.sngId && it.hasNextMediaItem()) it.seekToNextMediaItem() }
        updateFavorites { if (liked) it.filterNot { t -> t.sngId == track.sngId } else listOf(track) + it }

        val add = !liked
        // Queued first, cleared on success, rather than queued only once the call has failed: the
        // process is killed the moment the app goes away, and a call left hanging on a connection
        // that looks alive but isn't would otherwise take the like with it.
        pendingFavorites.queue(listOf(track.sngId), add)
        if (!hasNetwork()) return
        try {
            withTokenRetry { if (add) api.addFavorite(it, track.sngId) else api.removeFavorite(it, track.sngId) }
            pendingFavorites.clear(track.sngId)
        } catch (e: Exception) {
            Log.w(TAG, "Favorite ${if (add) "add" else "remove"} for ${track.sngId} left queued", e)
        }
    }

    /**
     * Likes every track in [tracks] at once. Every like is written to the local list and to the
     * durable queue in one pass up front, before a single request goes out, so "tout ajouter" holds
     * even offline or with the app left straight away; the queue is then drained here, and whatever
     * it doesn't manage waits for [DeezerPendingFavorites.flush] on the next connection.
     */
    suspend fun addFavorites(tracks: List<DeezerTrack>) {
        val fresh = tracks.filterNot { isFavorite(it.sngId) }.distinctBy { it.sngId }
        if (fresh.isEmpty()) return
        updateFavorites { fresh + it }
        pendingFavorites.queue(fresh.map { it.sngId }, add = true)
        pendingFavorites.flush()
    }

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

    @Volatile private var bestPepitesId: String? = null
    // Separate from the id being null: without it, an owner with no such playlist would re-fetch the
    // whole playlist list on every lookup, and the playback service does two of these per track.
    @Volatile private var bestPepitesLookedUp = false

    /**
     * Resolves (and caches) the id of the owner's "Best pépites" playlist, or null if none exists.
     * Reads the already loaded playlists first, so it also answers offline once the library is seeded.
     */
    suspend fun bestPepitesPlaylistId(): String? {
        bestPepitesId?.let { return it }
        if (bestPepitesLookedUp) return null
        val playlists = _playlists.value ?: fetchPlaylists()
        bestPepitesLookedUp = true
        return playlists.firstOrNull { it.title.deaccented().contains("pepite") }?.id
            ?.also { bestPepitesId = it }
    }

    /** Adds [track] to the owner's "Best pépites" playlist, unless it is already in it. */
    suspend fun addToBestPepites(track: DeezerTrack): PlaylistAddResult {
        val pid = bestPepitesPlaylistId() ?: return PlaylistAddResult.NO_PLAYLIST
        return addToPlaylist(pid, track)
    }

    /** Removes [sngId] from "Best pépites". False if that playlist doesn't exist. */
    suspend fun removeFromBestPepites(sngId: String): Boolean {
        val pid = bestPepitesPlaylistId() ?: return false
        removeFromPlaylist(pid, sngId)
        return true
    }

    /** Loads the "Best pépites" contents so [bestPepitesContains] can answer without a network call. */
    suspend fun ensureBestPepitesLoaded() {
        membership(bestPepitesPlaylistId() ?: return)
    }

    /** Whether [sngId] is in "Best pépites", or null while the playlist has never been read. */
    fun bestPepitesContains(sngId: String): Boolean? =
        bestPepitesId?.let { playlistTrackIds[it] }?.contains(sngId)

    /** True when [source] is the Best pépites playlist, the only place the diamond takes a track back out. */
    fun isBestPepites(source: TrackSource?): Boolean =
        source is TrackSource.Playlist && source.id == bestPepitesId

    /** Adds [track] to any of the owner's playlists, unless it is already in it. */
    suspend fun addToPlaylist(playlistId: String, track: DeezerTrack): PlaylistAddResult {
        val ids = membership(playlistId)
        if (track.sngId in ids) return PlaylistAddResult.DUPLICATE
        withTokenRetry { api.addSongToPlaylist(it, playlistId, track.sngId) }
        ids += track.sngId
        return PlaylistAddResult.ADDED
    }

    /** Removes [sngId] from [playlistId]. */
    suspend fun removeFromPlaylist(playlistId: String, sngId: String) {
        withTokenRetry { api.removeSongFromPlaylist(it, playlistId, sngId) }
        playlistTrackIds[playlistId]?.remove(sngId)
    }

    // Favorites and playlists pin a specific sngId at the time a track was liked/added. An artist who
    // re-released the same song under a different sngId can leave that pinned release unstreamable
    // (get_url refuses it) while a fresh catalog search turns up a working one. This is what lets
    // playback swap in a working release live, and what the "Corriger" snackbar action applies for real.

    /**
     * Searches the catalog for a different release of [track] (same normalized title and artist, a
     * different sngId) and returns the first one that actually resolves a stream, or null if none does.
     */
    suspend fun findReplacement(track: DeezerTrack): DeezerTrack? {
        val key = track.matchKey
        val candidates = try {
            search("${track.title} ${track.artist}")
        } catch (e: Exception) {
            return null
        }
        val matches = candidates.filter { it.sngId != track.sngId && it.matchKey == key }
            .take(REPLACEMENT_CANDIDATES)
        // Probed together, not one after the other: this runs with the music stopped, and a candidate
        // that fails still costs a round trip. The search order is kept, the first one that resolves wins.
        return coroutineScope {
            val probes = matches.map { candidate ->
                candidate to async {
                    try {
                        resolveStream(candidate.sngId, DEFAULT_QUALITY)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            probes.firstOrNull { it.second.await() }?.first
        }
    }

    /** Removes [old] from favorites/[source]'s playlist and adds [new] in its place. */
    suspend fun applyReplacement(old: DeezerTrack, new: DeezerTrack, source: TrackSource) {
        when (source) {
            is TrackSource.Favorites -> {
                withTokenRetry { api.removeFavorite(it, old.sngId); api.addFavorite(it, new.sngId) }
                updateFavorites { listOf(new) + it.filterNot { t -> t.sngId == old.sngId } }
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
     *
     * A track Deezer flatly refuses to serve is the exception: no session refresh will ever change
     * that answer, so it gives up on the first attempt and throws [TrackUnavailableException], which
     * the player uses to jump straight to the replacement search instead of burning its own retries.
     */
    private suspend fun resolveStream(sngId: String, quality: DeezerQuality): String {
        var last: Exception? = null
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            try {
                // Any retry re-bootstraps the session: a stale sid is the most common cause here.
                val session = ensureSession(forceRefresh = attempt > 0)
                return api.resolveStream(session, sngId, quality).cdnUrl
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (e is DeezerApiException && e.unavailable) {
                    logError("stream $sngId", e)
                    throw TrackUnavailableException("Deezer refuses to stream $sngId: ${e.message}", e)
                }
                if (attempt < RESOLVE_ATTEMPTS - 1) delay(500L * (attempt + 1))
            }
        }
        logError("stream $sngId", last!!)
        throw IOException("Deezer stream resolve failed for $sngId: ${last?.message}", last)
    }
}
