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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    suspend fun favorites(): List<DeezerTrack> = withTokenRetry { api.getFavorites(it) }
    suspend fun playlists(): List<DeezerPlaylist> = withTokenRetry { api.getPlaylists(it) }
    suspend fun playlistTracks(playlistId: String): List<DeezerTrack> =
        withTokenRetry { api.getPlaylistTracks(it, playlistId) }
    suspend fun search(query: String): List<DeezerTrack> = api.searchTracks(query)

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

    /** Sets the queue to [tracks] starting at [startIndex] and plays. */
    suspend fun playTracks(tracks: List<DeezerTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val controller = ensureController()
        val items = tracks.map { buildMediaItem(it, DEFAULT_QUALITY) }
        withContext(Dispatchers.Main) {
            controller.setMediaItems(items, startIndex, 0L)
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
