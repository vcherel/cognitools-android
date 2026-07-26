package com.example.myapp.deezer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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

/** What the library screen shows about the offline mirror. */
data class OfflineState(
    val total: Int = 0,
    val downloaded: Int = 0,
    val bytes: Long = 0,
    val syncing: Boolean = false,
    val failed: Int = 0,
    val error: String? = null
)

/** The Best pépites track list as last seen online, so the playlist can be queued with no network. */
private data class OfflineSnapshot(val playlistId: String, val tracks: List<DeezerTrack>)

/**
 * Keeps every "Best pépites" track permanently on the phone.
 *
 * The audio lives in a second SimpleCache with no evictor (the playback cache is LRU capped and sits
 * in cacheDir, which Android may wipe): same cache keys as playback, so a downloaded track is served
 * straight from disk and never resolves a CDN URL. The playlist's track list is mirrored to JSON
 * next to it, which is what makes the list browsable and playable while fully offline.
 *
 * Sync is incremental: only tracks missing from the cache are fetched, one at a time, and tracks
 * dropped from the playlist are deleted. Start it with [syncInBackground] on entering the tool: the
 * check costs one playlist fetch and downloads nothing when the playlist has not moved.
 */
class DeezerOfflineLibrary(private val appContext: Context, private val repo: DeezerRepository) {

    // noBackupFilesDir: internal storage that Android never clears on its own, and never uploads.
    private val dir: File by lazy { File(appContext.noBackupFilesDir, "deezer_offline").apply { mkdirs() } }
    private val snapshotFile: File by lazy { File(dir, "best_pepites.json") }

    /** The permanent audio store. Read-only during playback (see DeezerPlaybackService). */
    val cache: SimpleCache by lazy {
        SimpleCache(File(dir, "media"), NoOpCacheEvictor(), StandaloneDatabaseProvider(appContext))
    }

    private val downloadFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DeezerDataSource.Factory(repo))
    }

    private val _state = MutableStateFlow(OfflineState())
    val state: StateFlow<OfflineState> = _state

    private val syncMutex = Mutex()

    // Process lifetime, not screen lifetime: moving between the Deezer screens must not abort a download.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var snapshot: OfflineSnapshot? = null

    /** Fire and forget incremental sync. A no-op once the mirror matches the playlist. */
    fun syncInBackground() {
        scope.launch { sync() }
    }

    /** The mirrored track list for [playlistId], or null if that is not the offline playlist. */
    fun tracksFor(playlistId: String): List<DeezerTrack>? =
        readSnapshot()?.takeIf { it.playlistId == playlistId }?.tracks

    /**
     * Mirrors Best pépites: refreshes the track list, drops what left the playlist, downloads what is
     * missing. A network failure falls back to the last known list, so a sync with no connection just
     * finishes what was already queued.
     */
    suspend fun sync() {
        if (syncMutex.isLocked) return
        syncMutex.withLock {
            _state.update { it.copy(syncing = true, error = null, failed = 0) }
            try {
                val known = readSnapshot()
                known?.let { publishCounts(it.tracks) }

                val fresh = runCatching {
                    val pid = repo.bestPepitesPlaylistId() ?: error("Playlist Best pépites introuvable")
                    OfflineSnapshot(pid, repo.playlistTracks(pid))
                }.onFailure { Log.w(TAG, "Playlist refresh failed, keeping the last known list", it) }.getOrNull()

                if (fresh != null && fresh.tracks.isNotEmpty()) writeSnapshot(fresh)
                val tracks = (fresh ?: known)?.tracks
                if (tracks.isNullOrEmpty()) {
                    _state.update { it.copy(error = "Liste Best pépites indisponible") }
                    return@withLock
                }

                prune(tracks)
                publishCounts(tracks)

                var failed = 0
                for (track in tracks) {
                    if (!currentCoroutineContext().isActive) break
                    if (isDownloaded(cacheKey(track))) continue
                    val ok = runCatching { download(track) }
                        .onFailure { Log.w(TAG, "Download failed for ${track.title}", it) }
                        .isSuccess
                    if (!ok) failed++
                    publishCounts(tracks, failed)
                    delay(THROTTLE_MS) // stay closer to a listening pattern than to a scraper
                }
                if (failed > 0) _state.update { it.copy(error = "$failed titre(s) non téléchargé(s)") }
            } catch (e: Exception) {
                Log.w(TAG, "Offline sync failed", e)
                _state.update { it.copy(error = e.message ?: "Échec de la synchro") }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    /** Downloads one full track into the offline cache, resuming whatever spans are already there. */
    private suspend fun download(track: DeezerTrack) = withContext(Dispatchers.IO) {
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(cacheKey(track)))
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        val writer = CacheWriter(downloadFactory.createDataSource(), spec, null, null)
        // runInterruptible so stopping the service actually aborts the in-flight track.
        runInterruptible { writer.cache() }
    }

    /** Deletes cached audio for tracks that are no longer in the playlist. */
    private suspend fun prune(tracks: List<DeezerTrack>) = withContext(Dispatchers.IO) {
        val wanted = tracks.mapTo(HashSet()) { cacheKey(it) }
        cache.keys.filterNot { it in wanted }.forEach { runCatching { cache.removeResource(it) } }
    }

    private fun publishCounts(tracks: List<DeezerTrack>, failed: Int = _state.value.failed) {
        _state.update {
            it.copy(
                total = tracks.size,
                downloaded = tracks.count { t -> isDownloaded(cacheKey(t)) },
                bytes = cache.cacheSpace,
                failed = failed
            )
        }
    }

    /** True only when the whole resource is on disk: a half downloaded track must be retried. */
    private fun isDownloaded(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    /** Same key playback uses, so a downloaded track is a cache hit for the player. */
    private fun cacheKey(track: DeezerTrack): String =
        "dzr://${track.sngId}?q=${DeezerRepository.DEFAULT_QUALITY.name}"

    // ---- Track list mirror ----

    private fun readSnapshot(): OfflineSnapshot? {
        snapshot?.let { return it }
        if (!snapshotFile.exists()) return null
        return runCatching {
            val root = DeezerLibraryCache.json.parseToJsonElement(snapshotFile.readText()).jsonObject
            OfflineSnapshot(
                playlistId = root["playlistId"]?.jsonPrimitive?.content.orEmpty(),
                tracks = root["tracks"]?.jsonArray.orEmpty().map { DeezerLibraryCache.trackFromJson(it.jsonObject) }
            )
        }.getOrNull()?.also { snapshot = it }
    }

    private suspend fun writeSnapshot(snap: OfflineSnapshot) = withContext(Dispatchers.IO) {
        snapshot = snap
        val root = buildJsonObject {
            put("playlistId", snap.playlistId)
            put("tracks", buildJsonArray { snap.tracks.forEach { add(DeezerLibraryCache.trackToJson(it)) } })
        }
        runCatching { snapshotFile.writeText(root.toString()) }
    }

    private companion object {
        const val TAG = "DeezerOffline"
        const val THROTTLE_MS = 400L
    }
}
