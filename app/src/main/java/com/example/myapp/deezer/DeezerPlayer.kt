package com.example.myapp.deezer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import com.example.myapp.MediaControllerHolder
import com.example.myapp.podcastRepository
import com.example.myapp.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val QUEUED_NEXT_KEY = "deezer_queued_next"
private const val SOURCE_TYPE_KEY = "deezer_source_type"
private const val SOURCE_ID_KEY = "deezer_source_id"
private const val TAG = "DeezerPlayer"

/**
 * The playback side of the music tool, reached as `deezerRepository.player`: the shared
 * MediaController the whole tool drives, the StateFlows of player and queue state the UI reads,
 * the queue edits, shuffle, and the metadata of every track ever queued (see [queuedTracks]).
 *
 * Playback resolves lazily: a MediaItem carries only `dzr://<sngId>?q=<quality>`, and DeezerDataSource
 * fetches a fresh CDN URL and decrypts on the fly. That is why whole playlists can be queued cheaply.
 */
class DeezerPlayer(private val appContext: Context, private val repo: DeezerRepository) {

    // Fire and forget IO work that must survive the screen that started it (played-track metadata writes).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val playedTracksLoaded: Job

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState

    private val _queueState = MutableStateFlow(QueueUiState())

    /** What is queued right now, for the queue sheet. */
    val queueState: StateFlow<QueueUiState> = _queueState

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayerState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refreshPlayerState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshPlayerState()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshPlayerState()
        // A queue edited from anywhere (the sheet, "lire ensuite", the service's error recovery)
        // reaches the sheet through this one.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refreshPlayerState()
    }

    private val controllerHolder = MediaControllerHolder(
        appContext,
        DeezerPlaybackService::class.java,
        DeezerPlaybackService.CMD_STOP_ALL
    ) { c ->
        c.addListener(playerListener)
        refreshPlayerState()
    }

    val controller: MediaController? get() = controllerHolder.controller

    suspend fun ensureController(): MediaController = controllerHolder.ensure()

    private fun refreshPlayerState() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            _playerState.value = PlayerUiState()
            _queueState.value = QueueUiState()
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
        _queueState.value = QueueUiState(
            entries = (0 until c.mediaItemCount).map { index ->
                val item = c.getMediaItemAt(index)
                val meta = item.mediaMetadata
                QueueEntry(
                    sngId = item.mediaId,
                    title = meta.title?.toString().orEmpty(),
                    artist = meta.artist?.toString().orEmpty(),
                    coverUrl = meta.artworkUri?.toString()
                )
            },
            currentIndex = c.currentMediaItemIndex
        )
    }

    // The sheet acts on the controller directly, then re-reads it: the timeline is the queue, there is
    // no second copy to keep in step. Each edit also re-captures [orderedQueue] from what is left, so
    // turning shuffle back off restores the hand-made order instead of the list the queue started as.

    /** Moves the queued item at [from] to [to]. */
    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
        captureQueueOrder()
        refreshPlayerState()
    }

    /** Drops the queued item at [index]. Removing the playing one moves playback on to the next. */
    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        captureQueueOrder()
        refreshPlayerState()
    }

    /** Jumps straight to the queued item at [index]. */
    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
    }

    private fun captureQueueOrder() {
        val c = controller ?: return
        orderedQueue = (0 until c.mediaItemCount).mapNotNull { queuedTracks[c.getMediaItemAt(it).mediaId] }
    }

    /**
     * Replaces the queue with [tracks] and starts playing at [startIndex]. Never touches
     * shuffleModeEnabled: ExoPlayer's own shuffle order assigns newly inserted/replaced items a random
     * slot (see [addToQueue] and the playback service's error recovery), which breaks "play next" and
     * makes "previous" lose tracks whenever the queue changes after the fact. A shuffled play is instead
     * a plain queue whose order was already randomized in Kotlin before it gets here.
     *
     * When [shuffle] is on (the saved default), the tapped track still starts right away and only the
     * rest of the list is randomized behind it. [tracks] is kept in its original order in
     * [orderedQueue], which is what turning shuffle back off restores.
     */
    suspend fun playTracks(
        tracks: List<DeezerTrack>,
        startIndex: Int,
        source: TrackSource? = null,
        shuffle: Boolean = _shuffleEnabled.value
    ) {
        if (tracks.isEmpty()) return
        stopPodcastPlayback()
        val controller = ensureController()
        // Never cleared: the map doubles as the metadata behind [downloadedTracks] and is pruned to
        // what the caches hold at write time, so wiping it here shrank the file to the current queue.
        orderedQueue = tracks
        queueSource = source
        val order = if (shuffle) {
            listOf(tracks[startIndex]) + tracks.filterIndexed { i, _ -> i != startIndex }.shuffled()
        } else {
            tracks
        }
        // Thousands of favorites handed over in one call kept the service busy unpacking them for
        // half a second before the first note. The track that plays goes in alone, and the rest in a
        // second main thread turn: the service shares this process's main thread, so adding them in
        // the same turn would still hold the play command back until they were all through.
        val first = if (shuffle) 0 else startIndex
        withContext(Dispatchers.Main) {
            controller.setMediaItems(listOf(buildMediaItem(order[first], source = source)), 0, 0L)
            controller.prepare()
            controller.play()
        }
        // Built off the main thread even when the caller is on it, which is also what gives the
        // service its turn in between.
        val (before, after) = withContext(Dispatchers.Default) {
            order.subList(0, first).map { buildMediaItem(it, source = source) } to
                order.subList(first + 1, order.size).map { buildMediaItem(it, source = source) }
        }
        withContext(Dispatchers.Main) {
            controller.addMediaItems(0, before)
            controller.addMediaItems(after)
        }
    }

    /**
     * Queues every favorite and plays them shuffled. The first one is picked among the favorites
     * already whole on disk when there are any: any other track first waits on the session login,
     * the stream URL and the CDN, a good second of silence on a cold process.
     */
    suspend fun shuffleFavorites() = coroutineScope {
        // Binding the playback service takes a moment of its own on a cold process, so it runs
        // alongside the favorites read instead of after it.
        val connecting = async { ensureController() }
        val favorites = repo.ensureFavorites()
        if (favorites.isEmpty()) return@coroutineScope
        val start = withContext(Dispatchers.IO) {
            favorites.indices.shuffled().firstOrNull { isOnDisk(favorites[it].sngId) }
        } ?: favorites.indices.random()
        connecting.await()
        setShuffleSetting(true)
        playTracks(favorites, start, TrackSource.Favorites, shuffle = true)
    }

    /**
     * What the menu does while it shows the shuffle button: reads the favorites snapshot and opens
     * both audio caches off the main thread, so the tap itself only has to bind the service.
     */
    fun warmUp() {
        ioScope.launch {
            runCatching {
                repo.seedFavoritesFromDisk()
                repo.offline.cache
                repo.streamCache
            }
        }
    }

    private fun isOnDisk(sngId: String): Boolean {
        val key = deezerCacheKey(sngId)
        return isWhole(repo.offline.cache, key) || isWhole(repo.streamCache, key)
    }

    private fun isWhole(cache: SimpleCache, key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    /**
     * [shuffleFavorites] on the player's own scope: the menu button launches it, and opening
     * another tool right after kills the menu's scope, which used to cancel the start half way.
     */
    fun shuffleFavoritesDetached(): Deferred<Unit> = ioScope.async { shuffleFavorites() }

    /** Loads [playlistId]'s tracks and plays them shuffled, starting from a random one. */
    suspend fun shufflePlaylist(playlistId: String) =
        shuffleTracks(repo.playlistTracks(playlistId), TrackSource.Playlist(playlistId))

    /**
     * Plays [list] at random, starting from a random track. An explicit shuffle also turns the saved
     * setting on, so the player's shuffle button never claims the queue is in order when it isn't.
     */
    suspend fun shuffleTracks(list: List<DeezerTrack>, source: TrackSource? = null) {
        if (list.isEmpty()) return
        setShuffleSetting(true)
        playTracks(list, list.indices.random(), source, shuffle = true)
    }

    private val _shuffleEnabled = MutableStateFlow(true)

    /** Whether playback shuffles. Saved, and applied to every list started from anywhere in the tool. */
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    /** The current queue as its source gave it, before any shuffling: what [setShuffle] restores. */
    @Volatile private var orderedQueue: List<DeezerTrack>? = null
    @Volatile private var queueSource: TrackSource? = null

    private fun setShuffleSetting(enabled: Boolean) {
        if (_shuffleEnabled.value == enabled) return
        _shuffleEnabled.value = enabled
        ioScope.launch { runCatching { repo.settings.setShuffle(enabled) } }
    }

    /**
     * Flips the setting and reorders what is left of the current queue to match. The playing track is
     * never touched, only the items around it are removed and re-added, so nothing re-buffers.
     */
    suspend fun setShuffle(enabled: Boolean) {
        setShuffleSetting(enabled)
        val controller = controller ?: return
        val ordered = orderedQueue ?: return
        withContext(Dispatchers.Main) {
            val currentId = controller.currentMediaItem?.mediaId ?: return@withContext
            val index = ordered.indexOfFirst { it.sngId == currentId }
            if (index < 0) return@withContext
            val before: List<DeezerTrack>
            val after: List<DeezerTrack>
            if (enabled) {
                before = emptyList()
                after = ordered.filterIndexed { i, _ -> i != index }.shuffled()
            } else {
                before = ordered.take(index)
                after = ordered.drop(index + 1)
            }
            val current = controller.currentMediaItemIndex
            controller.removeMediaItems(current + 1, controller.mediaItemCount)
            controller.removeMediaItems(0, current)
            controller.addMediaItems(after.map { buildMediaItem(it, source = queueSource) })
            controller.addMediaItems(0, before.map { buildMediaItem(it, source = queueSource) })
        }
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
            fun tryAdd(sngId: String, key: String, track: DeezerTrack?, cache: SimpleCache) {
                if (track != null && seen.add(sngId) && isWhole(cache, key)) result += track
            }
            repo.offline.allTracks().forEach { tryAdd(it.sngId, deezerCacheKey(it.sngId), it, repo.offline.cache) }
            repo.streamCache.keys.forEach { key -> sngIdFromCacheKey(key)?.let { tryAdd(it, key, queuedTracks[it], repo.streamCache) } }
            result
        }
    }

    /** Shuffles everything currently downloaded (see [downloadedTracks]), read straight off disk with no network call. */
    suspend fun shuffleDownloaded() = shuffleTracks(downloadedTracks())

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

    /** Pauses playback, removes the notification, and stops the playback service entirely. */
    fun stopAll() {
        controllerHolder.stop()
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

    // Bounded by the cache itself at write time (only sngIds still present in a cache are kept), so
    // this file tracks the 5 GB LRU cache's contents without growing forever.

    private val playedTracksFile: File by lazy { File(appContext.filesDir, "deezer_played_tracks.json") }
    private val playedTracksWriteMutex = Mutex()
    @Volatile private var playedTracksWritePending = false
    // Cleared when the file failed to load: whatever it holds is worth more than what this run knows.
    @Volatile private var playedTracksWritable = true

    // Runs last in the constructor: loadPlayedTracksAsync is launched onto the IO dispatcher here and
    // can start running on another thread before this constructor returns, so every property it
    // touches (playedTracksFile, queuedTracks, ...) must already be assigned by this point, not just
    // declared further down the file.
    init {
        playedTracksLoaded = loadPlayedTracksAsync()
        ioScope.launch { runCatching { _shuffleEnabled.value = repo.settings.shuffle.first() } }
    }

    private fun loadPlayedTracksAsync(): Job = ioScope.launch {
        runCatching {
            if (!playedTracksFile.exists()) return@launch
            val arr = DeezerLibraryCache.json.parseToJsonElement(playedTracksFile.readText()).jsonArray
            arr.forEach {
                val t = DeezerLibraryCache.trackFromJson(it.jsonObject)
                if (t.sngId.isNotBlank()) queuedTracks.putIfAbsent(t.sngId, t)
            }
        }.onFailure {
            // A file that can't be read is a file that must not be rewritten from an empty map: the
            // audio is still cached, and pruning the metadata here would hide it for good.
            playedTracksWritable = false
            Log.w(TAG, "Failed to load played-track metadata", it)
        }
    }

    /** Debounced so queuing a whole playlist doesn't trigger one disk write per track. */
    private fun persistPlayedTracksAsync() {
        if (playedTracksWritePending) return
        playedTracksWritePending = true
        ioScope.launch {
            delay(2_000L)
            playedTracksWritePending = false
            playedTracksWriteMutex.withLock {
                if (!playedTracksWritable) return@withLock
                val cachedIds = HashSet<String>()
                repo.streamCache.keys.forEach { sngIdFromCacheKey(it)?.let(cachedIds::add) }
                repo.offline.cache.keys.forEach { sngIdFromCacheKey(it)?.let(cachedIds::add) }
                val arr = buildJsonArray {
                    queuedTracks.values.filter { it.sngId in cachedIds }.forEach { add(DeezerLibraryCache.trackToJson(it)) }
                }
                runCatching { playedTracksFile.writeAtomically(arr.toString()) }
            }
        }
    }

    /** The [TrackSource] a queued MediaItem was tagged with, if any. */
    fun sourceOf(mediaItem: MediaItem): TrackSource? {
        val extras = mediaItem.mediaMetadata.extras ?: return null
        return when (extras.getString(SOURCE_TYPE_KEY)) {
            "favorites" -> TrackSource.Favorites
            "playlist" -> extras.getString(SOURCE_ID_KEY)?.let { TrackSource.Playlist(it) }
            else -> null
        }
    }

    /** Builds a queued MediaItem for [track], tagged with [source] so a stream failure can be corrected at its origin. */
    internal fun buildMediaItem(
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
            .setUri(Uri.parse(deezerCacheKey(track.sngId)))
            .setMediaId(track.sngId)
            .setMediaMetadata(metadata.build())
            .build()
    }
}
