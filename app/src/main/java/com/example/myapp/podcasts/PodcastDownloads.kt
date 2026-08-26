package com.example.myapp.podcasts

import com.example.myapp.userMessage
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.example.myapp.AppSnackbar
import com.example.myapp.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

private const val TAG = "PodcastDownloads"

/**
 * The episodes kept on the phone. A download is the whole episode held in [PodcastStreamCache],
 * protected from eviction, plus a row in podcast_downloads for its metadata (the cache names
 * nothing). One store for everything means the three things that fetch audio feed each other:
 * playing an episode fills the cache the download would have fetched, downloading one covers the
 * sleep timer's night, and a download started over an episode already streamed only pulls what is
 * missing.
 *
 * "Downloaded" stays derived from the bytes actually held, never from a flag: [refresh] drops any
 * row whose audio is no longer whole.
 *
 * Owned by [PodcastRepository], which is where the rest of the app reaches it.
 */
class PodcastDownloads(private val appContext: Context, private val dao: () -> PodcastDao) {

    /** Where downloads lived before the cache took them over. Emptied by [migrateLegacy]. */
    private val legacyDir: File by lazy { File(appContext.filesDir, "podcast_downloads") }

    /** What those files were named: the only thing that can still tie one back to its episode. */
    private fun legacyFileName(episodeId: String): String =
        MessageDigest.getInstance("SHA-256").digest(episodeId.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Outlives every screen: a download keeps going with the app closed and the phone locked. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    /** One download at a time: several large episodes over mobile data help nobody. */
    private val mutex = Mutex()

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    /**
     * Episodes currently downloading, in the order they were queued: the running one first. The
     * notification reads this, which is why it holds whole episodes and not just ids.
     */
    private val _active = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    val active: StateFlow<List<PodcastEpisode>> = _active

    val activeIds: StateFlow<Set<String>> = _active
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** How much of each running download is on disk, 0..1, keyed by episode id. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    /** Every downloaded episode's metadata, newest first. What the downloaded list and the offline
     *  fallback read: the audio files themselves say nothing about the episode. */
    val episodes: Flow<List<PodcastDownload>> get() = dao().observeDownloads()

    init {
        scope.launch {
            migrateLegacy()
            refresh()
        }
    }

    /** [known] is passed in so a composable reading [ids] recomposes when it changes. */
    fun isDownloaded(episodeId: String, known: Set<String> = _ids.value): Boolean = episodeId in known

    /**
     * Rebuilds what counts as downloaded from the bytes actually held, and protects them from the
     * cache's evictor. A row whose audio is gone (a manual cache wipe, a reinstall of the media3
     * index) stops being a download rather than showing an episode that would not play offline.
     */
    private suspend fun refresh() {
        val rows = dao().getDownloads()
        val whole = rows.filter { PodcastStreamCache.holdsWholeResource(appContext, it.audioUrl) }
        whole.forEach { PodcastStreamCache.setProtected(appContext, it.audioUrl, true) }
        (rows - whole.toSet()).forEach { dao().deleteDownload(it.episodeId) }
        _ids.value = whole.map { it.episodeId }.toSet()
    }

    /**
     * Moves the downloads made back when they were plain files into the cache, once. Re-downloading
     * them instead would silently cost the user every episode they had put aside for a trip.
     */
    private suspend fun migrateLegacy() = withContext(Dispatchers.IO) {
        val files = legacyDir.listFiles()?.filter { it.extension == "audio" } ?: return@withContext
        if (files.isEmpty()) return@withContext
        val byName = dao().getDownloads().associateBy { legacyFileName(it.episodeId) }
        files.forEach { file ->
            val row = byName[file.nameWithoutExtension]
            // A file with no row names no episode and no URL: there is nothing to file it under.
            if (row != null && PodcastStreamCache.importFile(appContext, row.audioUrl, file)) {
                Log.i(TAG, "Migrated download into the cache: ${row.title}")
            } else if (row != null) {
                Log.w(TAG, "Could not migrate download, it will have to be fetched again: ${row.title}")
                dao().deleteDownload(row.episodeId)
            }
            file.delete()
        }
        legacyDir.delete()
    }

    /**
     * Queues [episode] and makes sure [PodcastDownloadService] is up. The work runs in this object's
     * own scope, not the caller's: leaving the screen, locking the phone or closing the app used to
     * cancel the download halfway. Downloads run one at a time.
     */
    fun enqueue(episode: PodcastEpisode) {
        if (isDownloaded(episode.id) || jobs.containsKey(episode.id)) return
        if (episode.audioUrl.isBlank()) {
            AppSnackbar.show("Pas de flux audio pour cet épisode")
            return
        }
        _active.update { it + episode }
        PodcastDownloadService.start(appContext)
        jobs[episode.id] = scope.launch {
            try {
                mutex.withLock { download(episode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${episode.title}", e)
                AppSnackbar.show(userMessage(e, "Échec du téléchargement"))
            } finally {
                jobs.remove(episode.id)
                _active.update { list -> list.filterNot { it.id == episode.id } }
                _progress.update { it - episode.id }
            }
        }
    }

    /**
     * Stops [episodeId]'s download, running or still queued. What it had already fetched stays in the
     * cache as ordinary streaming bytes: it still plays offline, and the evictor may reclaim it.
     */
    fun cancel(episodeId: String) {
        jobs.remove(episodeId)?.cancel()
        val url = _active.value.firstOrNull { it.id == episodeId }?.audioUrl
        _active.update { list -> list.filterNot { it.id == episodeId } }
        _progress.update { it - episodeId }
        if (url != null) PodcastStreamCache.setProtected(appContext, url, false)
    }

    /** Cancels everything queued. What the notification's action does. */
    fun cancelAll() {
        _active.value.map { it.id }.forEach { cancel(it) }
    }

    /**
     * Pulls the whole episode into the shared cache and keeps it there. Bytes already held, whether
     * from playing it or from a sleep pre-fetch, are not fetched again. No-op if already downloaded.
     */
    private suspend fun download(episode: PodcastEpisode) {
        if (isDownloaded(episode.id)) return
        if (episode.audioUrl.isBlank()) throw IOException("Pas de flux audio pour cet épisode")
        // Protected up front: the fetch itself must not be evicted by what playback caches meanwhile.
        PodcastStreamCache.setProtected(appContext, episode.audioUrl, true)
        try {
            withContext(Dispatchers.IO) {
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(episode.audioUrl))
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
                val writer = CacheWriter(
                    PodcastStreamCache.cacheDataSourceFactory(appContext).createDataSource(),
                    spec,
                    null,
                    CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
                        if (requestLength > 0) {
                            _progress.update {
                                it + (episode.id to (bytesCached.toFloat() / requestLength).coerceIn(0f, 1f))
                            }
                        }
                    }
                )
                // runInterruptible so cancelling actually aborts the fetch in flight.
                runInterruptible { writer.cache() }
                // A connection cut mid-transfer can end the fetch on a plain EOF instead of throwing,
                // and a truncated episode kept as a download is worse than none: it plays up to where
                // it stops, the player calls that the end, and the episode gets marked heard.
                if (!PodcastStreamCache.holdsWholeResource(appContext, episode.audioUrl)) {
                    throw IOException("Téléchargement incomplet")
                }
            }
            dao().upsertDownload(episode.toDownload())
            _ids.update { it + episode.id }
        } catch (e: Exception) {
            // What was fetched stays in the cache, unprotected: it is still worth having for playback
            // and for the next attempt, and the evictor is free to reclaim it.
            PodcastStreamCache.setProtected(appContext, episode.audioUrl, false)
            throw e
        }
    }

    /** Drops [episodeId]'s downloaded audio and its metadata row, if any. */
    suspend fun remove(episodeId: String) {
        val url = dao().getDownloads().firstOrNull { it.episodeId == episodeId }?.audioUrl
        _ids.update { it - episodeId }
        dao().deleteDownload(episodeId)
        if (url != null) PodcastStreamCache.remove(appContext, url)
    }

    /**
     * Opens [url] for reading, following redirects by hand: podcast enclosures usually point at a
     * tracking prefix (Podtrac, Chartable, Megaphone…) that bounces to the real CDN, and
     * HttpURLConnection silently refuses to follow a redirect that switches between http and https.
     */
    internal fun openAudio(url: String): HttpURLConnection {
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
}
