package com.example.myapp.podcasts

import com.example.myapp.writeAtomically
import com.example.myapp.userMessage
import com.example.myapp.deezerRepository
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.example.myapp.AppSnackbar
import com.example.myapp.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

    /** Outlives every screen: a download keeps going with the app closed and the phone locked. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Touched from the main thread (a tap), this scope (a job ending) and the sleep timer's scope. */
    private val jobs = ConcurrentHashMap<String, Job>()
    /** One download at a time: several large episodes over mobile data help nobody. */
    private val mutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The episodes the user asked to keep offline, persisted to disk. A download cut short by a dead
     * connection or by the app being killed used to be lost silently, forcing a trip back online for
     * an episode that was meant to be on the phone; now it stays on this list and [retryWanted]
     * resumes it on the next launch or the next time the connection comes back. Keyed by episode id.
     */
    private val wantedFile: File by lazy { File(appContext.filesDir, "podcast_wanted_downloads.json") }
    private val wantedMutex = Mutex()
    private val wanted = LinkedHashMap<String, PodcastDownload>()

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
            loadWanted()
            refresh()
            retryWanted()
        }
        watchNetworkForRetry()
    }

    private suspend fun loadWanted() = wantedMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!wantedFile.exists()) return@runCatching
                json.parseToJsonElement(wantedFile.readText()).jsonArray.forEach {
                    val d = downloadFromJson(it.jsonObject)
                    if (d.episodeId.isNotBlank()) wanted[d.episodeId] = d
                }
            }.onFailure { Log.w(TAG, "Could not read the wanted-downloads list", it) }
        }
    }

    /** Caller holds [wantedMutex]. Written aside then renamed: the process dies screen-off often
     *  enough that a direct write leaves a truncated file. */
    private suspend fun writeWanted() = withContext(Dispatchers.IO) {
        runCatching {
            if (wanted.isEmpty()) {
                wantedFile.delete()
                return@runCatching
            }
            wantedFile.writeAtomically(buildJsonArray { wanted.values.forEach { add(downloadToJson(it)) } }.toString())
        }.onFailure { Log.w(TAG, "Could not write the wanted-downloads list", it) }
    }

    private suspend fun addWanted(episode: PodcastEpisode) = wantedMutex.withLock {
        wanted[episode.id] = episode.toDownload()
        writeWanted()
    }

    private suspend fun removeWanted(episodeId: String) = wantedMutex.withLock {
        if (wanted.remove(episodeId) != null) writeWanted()
    }

    /**
     * Re-queues every wanted episode whose audio isn't fully on the phone. Safe to call repeatedly:
     * [enqueue] ignores an episode already downloaded or already in flight. Run on init and whenever
     * the device gets validated internet back.
     */
    suspend fun retryWanted() {
        val pending = wantedMutex.withLock { wanted.values.toList() }
        pending.forEach { d ->
            if (d.episodeId in _ids.value || jobs.containsKey(d.episodeId)) return@forEach
            if (PodcastStreamCache.holdsWholeResource(appContext, d.audioUrl)) return@forEach
            enqueue(d.toEpisode(seen = false))
        }
    }

    private fun watchNetworkForRetry() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var validated = false

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (now && !validated) scope.launch { retryWanted() }
                    validated = now
                }

                override fun onLost(network: Network) {
                    validated = false
                }
            })
        }.onFailure { Log.w(TAG, "Could not watch the network to resume downloads", it) }
    }

    private fun downloadToJson(d: PodcastDownload): JsonObject = buildJsonObject {
        put("id", d.episodeId)
        put("pid", d.podcastId)
        put("pt", d.podcastTitle)
        d.podcastArtworkUrl?.let { put("pa", it) }
        put("t", d.title)
        put("pub", d.pubDate)
        put("u", d.audioUrl)
        d.durationSec?.let { put("dur", it) }
    }

    private fun downloadFromJson(o: JsonObject): PodcastDownload = PodcastDownload(
        episodeId = o["id"]?.jsonPrimitive?.content.orEmpty(),
        podcastId = o["pid"]?.jsonPrimitive?.content.orEmpty(),
        podcastTitle = o["pt"]?.jsonPrimitive?.content.orEmpty(),
        podcastArtworkUrl = o["pa"]?.jsonPrimitive?.content?.ifBlank { null },
        title = o["t"]?.jsonPrimitive?.content.orEmpty(),
        pubDate = o["pub"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        audioUrl = o["u"]?.jsonPrimitive?.content.orEmpty(),
        durationSec = o["dur"]?.jsonPrimitive?.content?.toIntOrNull(),
        downloadedAt = 0L
    )

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
        val job = scope.launch(start = CoroutineStart.LAZY) {
            addWanted(episode)
            try {
                mutex.withLock { download(episode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${episode.title}", e)
                // Logged where the music tool's errors go: the snackbar alone said "Problème de
                // réseau" for any IOException, which hid whether the CDN refused or the fetch was cut.
                appContext.deezerRepository.logError("Téléchargement podcast ${episode.title} (${episode.audioUrl})", e)
                AppSnackbar.show(userMessage(e, "Échec du téléchargement"))
            } finally {
                // Only this job's own entry: a cancel followed by a fresh enqueue of the same episode
                // has already replaced it, and that newer download must stay tracked.
                if (jobs.remove(episode.id, coroutineContext[Job])) {
                    _active.update { list -> list.filterNot { it.id == episode.id } }
                    _progress.update { it - episode.id }
                }
            }
        }
        jobs[episode.id] = job
        job.start()
    }

    /**
     * Stops [episodeId]'s download, running or still queued. What it had already fetched stays in the
     * cache as ordinary streaming bytes: it still plays offline, and the evictor may reclaim it.
     */
    fun cancel(episodeId: String) {
        scope.launch { removeWanted(episodeId) }
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
        if (episode.audioUrl.isBlank()) throw IllegalStateException("Pas de flux audio pour cet épisode")
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
                // Some feeds serve the audio chunked, with no Content-Length: the fetch then runs to
                // the end cleanly but the cache never learns the size, so the whole-resource check
                // below would call a complete download incomplete forever. A clean read to
                // end-of-stream is trusted here (a real cut throws), and the bytes held are recorded
                // as the length.
                PodcastStreamCache.commitFetchedLengthIfUnknown(appContext, episode.audioUrl)
                // A connection cut mid-transfer can end the fetch on a plain EOF instead of throwing,
                // and a truncated episode kept as a download is worse than none: it plays up to where
                // it stops, the player calls that the end, and the episode gets marked heard.
                if (!PodcastStreamCache.holdsWholeResource(appContext, episode.audioUrl)) {
                    throw IllegalStateException("Téléchargement incomplet")
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
        removeWanted(episodeId)
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
