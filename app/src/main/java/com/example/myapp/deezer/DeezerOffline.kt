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
import kotlinx.coroutines.CancellationException
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
 *
 * Every run also appends to a rolling log next to the app's external files, so a failure that
 * happened days ago can still be read back with adb on a release build.
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

    /** Every track currently mirrored offline, regardless of playlist id. Empty until the first sync. */
    fun allTracks(): List<DeezerTrack> = readSnapshot()?.tracks.orEmpty()

    /** The id of the playlist backing the offline mirror (Best pépites), or null before the first sync. */
    fun playlistId(): String? = readSnapshot()?.playlistId

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
                    log("sync aborted: no track list (playlist fetch failed and no snapshot on disk)")
                    return@withLock
                }

                prune(tracks)
                publishCounts(tracks)

                val cachedBefore = tracks.count { isDownloaded(cacheKey(it)) }
                log("sync start: ${tracks.size} tracks, $cachedBefore already downloaded")

                val failed = downloadMissing(tracks)

                val cachedAfter = tracks.count { isDownloaded(cacheKey(it)) }
                log("sync end: ${cachedAfter - cachedBefore} newly downloaded, $cachedAfter/${tracks.size} on disk, ${failed.size} failed")
                _state.update {
                    it.copy(error = if (failed.isEmpty()) null else "${failed.size} titre(s) non téléchargé(s)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline sync failed", e)
                log("sync crashed: ${describe(e)}")
                _state.update { it.copy(error = e.message ?: "Échec de la synchro") }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    /**
     * Downloads everything not already on disk, in up to [PASSES] passes. Returns the tracks still
     * missing at the end, mapped to their last error.
     *
     * Each pass recomputes what is missing, so a track already fully cached is never fetched again,
     * and a track that died on a transient failure (CDN read timeout, network drop between two
     * chunks) gets a fresh go later in the same run instead of waiting for the next sync.
     */
    private suspend fun downloadMissing(tracks: List<DeezerTrack>): Map<DeezerTrack, String> {
        var failed = emptyMap<DeezerTrack, String>()
        for (pass in 1..PASSES) {
            val pending = tracks.filterNot { isDownloaded(cacheKey(it)) }
            if (pending.isEmpty()) return emptyMap()
            if (pass > 1) {
                log("retry pass $pass on ${pending.size} track(s)")
                delay(PASS_PAUSE_MS)
            }
            val round = LinkedHashMap<DeezerTrack, String>()
            for ((index, track) in pending.withIndex()) {
                if (!currentCoroutineContext().isActive) {
                    log("sync interrupted with ${pending.size - index} track(s) still to do")
                    return round
                }
                attemptDownload(track)?.let { error ->
                    round[track] = error
                    log("failed: \"${track.title}\" by ${track.artist} [${track.sngId}] $error")
                }
                publishCounts(tracks, round.size)
                delay(THROTTLE_MS) // stay closer to a listening pattern than to a scraper
            }
            failed = round
        }
        return failed
    }

    /**
     * Downloads one track, retrying a few times with a growing pause. Returns null on success, or a
     * description of the last error. Retries are cheap: a failed attempt leaves its spans in the
     * cache, so the next one resumes instead of restarting from zero.
     */
    private suspend fun attemptDownload(track: DeezerTrack): String? {
        var last: Throwable? = null
        for (attempt in 0 until ATTEMPTS) {
            try {
                download(track)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "Attempt ${attempt + 1}/$ATTEMPTS failed for ${track.title}", e)
                if (attempt < ATTEMPTS - 1) delay(ATTEMPT_PAUSES_MS[attempt])
            }
        }
        return describe(last)
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

    // ---- Sync log ----
    // In getExternalFilesDir so a release build's log can be read with plain adb (run-as only works
    // on debug builds):
    //   adb shell cat /sdcard/Android/data/com.example.myapp/files/deezer_sync_log.txt

    private val logFile: File by lazy { File(appContext.getExternalFilesDir(null) ?: dir, LOG_NAME) }

    /** Appends one line to the rolling log. Called from the sync coroutine, which is already on IO. */
    private fun log(line: String) {
        Log.i(TAG, line)
        runCatching {
            logFile.appendText("${LocalDateTime.now().format(STAMP)} $line\n")
            if (logFile.length() > LOG_MAX_BYTES) {
                val kept = logFile.readLines().takeLast(LOG_KEEP_LINES)
                logFile.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Flattens a throwable and its causes into one loggable line: the message is what identifies it. */
    private fun describe(t: Throwable?): String =
        generateSequence(t) { it.cause }
            .take(3)
            .joinToString(", caused by ") { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }
            .ifBlank { "unknown error" }

    private companion object {
        const val TAG = "DeezerOffline"
        const val THROTTLE_MS = 400L

        /** Attempts per track inside one pass, then how long to wait before each next attempt. */
        const val ATTEMPTS = 3
        val ATTEMPT_PAUSES_MS = longArrayOf(1_500L, 5_000L)

        /** Full passes over what is still missing, and the breather taken before a retry pass. */
        const val PASSES = 2
        const val PASS_PAUSE_MS = 10_000L

        const val LOG_NAME = "deezer_sync_log.txt"
        const val LOG_MAX_BYTES = 96 * 1024L
        const val LOG_KEEP_LINES = 400
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
