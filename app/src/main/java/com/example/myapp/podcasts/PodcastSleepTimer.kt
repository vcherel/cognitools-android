package com.example.myapp.podcasts

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

private const val TAG = "PodcastSleepTimer"

// Secured past the timer's end, so nodding off with a couple of minutes left over still plays out.
private const val PRELOAD_MARGIN_MS = 120_000L

// And secured a little behind the player too: a playback position maps to a byte offset only
// approximately (see cacheRange), and the fetched range must not start after what is playing.
private const val PRELOAD_BACK_MARGIN_MS = 60_000L

/** How often the secured stretch is checked again while the timer runs. */
private const val COVERAGE_CHECK_INTERVAL_MS = 30_000L

/** What the pre-fetch secured: the byte range it holds in the cache, and how it mapped time to it. */
private data class Coverage(val url: String, val startByte: Long, val endByte: Long, val bytesPerMs: Double)

/**
 * Pauses playback after a set delay, and makes sure the audio needed to reach that point is on the
 * phone before it does. Owned by [PodcastRepository], which is where the rest of the app reaches it.
 */
class PodcastSleepTimer(private val appContext: Context, private val repo: PodcastRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private var preloadJob: Job? = null
    private var watchdogJob: Job? = null

    /** The range the pre-fetch secured, so the watchdog knows what it has to still find in the cache. */
    private var coverage: Coverage? = null

    /** The episode whose bytes are held against eviction for as long as the timer runs. */
    private var protectedEpisode: PodcastEpisode? = null

    private val _endAt = MutableStateFlow<Long?>(null)
    val endAt: StateFlow<Long?> = _endAt

    /**
     * How much of the audio needed to reach the timer's end is on the phone, 0..1, or null when no
     * timer is running. At 1 the rest plays with no connection, so airplane mode is safe.
     */
    private val _cacheProgress = MutableStateFlow<Float?>(null)
    val cacheProgress: StateFlow<Float?> = _cacheProgress

    /** Keeps what the night needs out of the evictor's reach, instead of only noticing it went. */
    private fun protect(episode: PodcastEpisode) {
        releaseProtection()
        if (episode.audioUrl.isBlank()) return
        protectedEpisode = episode
        PodcastStreamCache.setProtected(appContext, episode.audioUrl, true)
    }

    /** A downloaded episode keeps its own protection: only the timer's is given back here. */
    private fun releaseProtection() {
        val episode = protectedEpisode ?: return
        protectedEpisode = null
        if (!repo.downloads.isDownloaded(episode.id)) {
            PodcastStreamCache.setProtected(appContext, episode.audioUrl, false)
        }
    }

    /**
     * Raises the badge to [value], never lowers it. Securing the night can take two passes (a partial
     * pre-fetch, then the whole episode when that one can't be trusted), and each pass counts its own
     * bytes from zero: driving the badge straight from them made it fill up twice for one timer.
     * Only starting a pass, or cancelling the timer, puts it back down.
     */
    private fun raiseProgress(value: Float) {
        val capped = value.coerceIn(0f, 1f)
        _cacheProgress.update { current -> maxOf(current ?: 0f, capped) }
    }

    /** Pauses playback in [minutes]. Replaces any timer already running. */
    fun start(minutes: Int) {
        timerJob?.cancel()
        _endAt.value = System.currentTimeMillis() + minutes * 60_000L
        timerJob = scope.launch {
            delay(minutes * 60_000L)
            withContext(Dispatchers.Main) { repo.controller?.pause() }
            _endAt.value = null
            _cacheProgress.value = null
            watchdogJob?.cancel()
            coverage = null
            releaseProtection()
        }
        preload(minutes)
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        preloadJob?.cancel()
        preloadJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        coverage = null
        releaseProtection()
        _endAt.value = null
        _cacheProgress.value = null
    }

    /**
     * Falling asleep usually means the phone is about to go offline (airplane mode), so the audio
     * needed to reach the timer's end is pulled in right away. Only that stretch, and only into the
     * stream cache: it is not a download, the episode isn't listed as downloaded, and playback keeps
     * streaming past it normally while there is still a connection.
     */
    private fun preload(minutes: Int) {
        preloadJob?.cancel()
        val episode = repo.currentEpisode()
        if (episode == null || episode.audioUrl.isBlank()) {
            _cacheProgress.value = null
            return
        }
        protect(episode)
        // A fully downloaded episode is held whole already, with nothing left to secure.
        if (repo.downloads.isDownloaded(episode.id)) {
            coverage = null
            _cacheProgress.value = 1f
            startWatchdog(episode)
            return
        }
        _cacheProgress.value = 0f
        preloadJob = scope.launch {
            val (positionMs, durationMs) = withContext(Dispatchers.Main) {
                val c = repo.controller
                (c?.currentPosition ?: 0L) to (c?.duration ?: C.TIME_UNSET)
            }
            // A download of this very episode is already pulling the whole thing into the same cache:
            // waiting on it beats fetching an overlapping range next to it.
            val alreadyDownloading = episode.id in repo.downloads.activeIds.value
            val secured = if (alreadyDownloading) null else {
                runCatching { cacheRange(episode, positionMs, durationMs, minutes) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Sleep pre-fetch failed for ${episode.title}", e)
                    }
                    .getOrNull()
            }
            // Only a verified range counts as secured. Anything else, including a pre-fetch that had
            // nothing trustworthy to fetch, falls back to the whole episode instead of claiming 100%.
            coverage = secured
            if (secured != null) {
                raiseProgress(1f)
            } else {
                if (!alreadyDownloading) {
                    Log.w(TAG, "Sleep pre-fetch did not secure ${episode.title}, downloading it whole")
                }
                downloadWhole(episode, positionMs, durationMs, minutes)
            }
            if (_cacheProgress.value == 1f) startWatchdog(episode)
        }
    }

    /**
     * Fallback when the partial pre-fetch can't be done, typically a host that doesn't serve byte
     * ranges: the whole episode is downloaded instead. That costs more data and does show it as a
     * downloaded episode, which is the price of being sure it plays through the night.
     *
     * It goes through the normal download queue rather than fetching by hand: that gives it the same
     * mutex, the same notification and the same cancel action as any download, and it can no longer
     * end up writing the same temp file as a download started by hand.
     */
    private suspend fun downloadWhole(
        episode: PodcastEpisode,
        positionMs: Long,
        durationMs: Long,
        minutes: Int
    ) = coroutineScope {
        val knownDurationMs = durationMs.takeIf { it > 0 } ?: ((episode.durationSec ?: 0) * 1000L)
        val neededMs = (positionMs + minutes * 60_000L + PRELOAD_MARGIN_MS)
            .let { if (knownDurationMs > 0) it.coerceAtMost(knownDurationMs) else it }
        // The download runs from the start of the file, so what matters is when it gets past the
        // timer's end, not when the whole episode is there: the badge is safe from that point on.
        val neededFraction = if (knownDurationMs > 0) neededMs.toFloat() / knownDurationMs else 1f
        val mirror = launch {
            repo.downloads.progress.collect { progress ->
                progress[episode.id]?.let { done -> raiseProgress(done / neededFraction) }
            }
        }
        try {
            // A manual download of the same episode already running makes this a no-op, so wait it
            // out instead of calling the night secured the moment enqueue returns.
            repo.downloads.enqueue(episode)
            // Progress stays where it stopped when a download fails: below 100% is exactly the
            // warning that airplane mode would cut the episode off.
            while (!repo.downloads.isDownloaded(episode.id) && episode.id in repo.downloads.activeIds.value) {
                delay(500)
            }
            if (repo.downloads.isDownloaded(episode.id)) raiseProgress(1f)
        } finally {
            mirror.cancel()
        }
    }

    /**
     * Caches the byte range covering [positionMs] to the timer's end (plus a margin), and nothing else.
     * Returns that range only once its bytes are verified to be in the cache: the badge it drives
     * promises airplane mode is safe for the night, so nothing less may light it up. Null otherwise.
     */
    private suspend fun cacheRange(
        episode: PodcastEpisode,
        positionMs: Long,
        durationMs: Long,
        minutes: Int
    ): Coverage? = withContext(Dispatchers.IO) {
        val totalBytes = PodcastStreamCache.knownContentLength(appContext, episode.audioUrl)
            .takeIf { it > 0 }
            ?: runCatching {
                val conn = repo.downloads.openAudio(episode.audioUrl)
                conn.contentLengthLong.also { conn.disconnect() }
            }.getOrDefault(C.LENGTH_UNSET.toLong())
        val knownDurationMs = durationMs.takeIf { it > 0 } ?: ((episode.durationSec ?: 0) * 1000L)
        // Without the file's size and its duration, where a playback position sits in it is pure
        // guesswork, and a range fetched at the wrong offset leaves the player a gap it cannot fill
        // offline. Saying so and downloading the episode whole beats a badge that lies.
        if (totalBytes <= 0 || knownDurationMs <= 0) return@withContext null

        val bytesPerMs = totalBytes.toDouble() / knownDurationMs
        // Starts a minute behind what is playing: this maps milliseconds to bytes through the file's
        // average bitrate, which ignores its header (tags, embedded artwork) and flattens a variable
        // bitrate, so the offset is approximate and must never land past what the player is reading.
        val start = ((positionMs - PRELOAD_BACK_MARGIN_MS) * bytesPerMs).toLong().coerceIn(0L, totalBytes)
        val neededMs = minutes * 60_000L + PRELOAD_MARGIN_MS + PRELOAD_BACK_MARGIN_MS
        val length = (neededMs * bytesPerMs).toLong().coerceAtMost(totalBytes - start)
        if (length <= 0) return@withContext null

        val spec = DataSpec.Builder()
            .setUri(Uri.parse(episode.audioUrl))
            .setPosition(start)
            .setLength(length)
            .build()
        val writer = CacheWriter(
            PodcastStreamCache.cacheDataSourceFactory(appContext).createDataSource(),
            spec,
            null,
            CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
                if (requestLength > 0) raiseProgress(bytesCached.toFloat() / requestLength)
            }
        )
        // runInterruptible so cancelling the timer actually aborts the fetch in flight.
        runInterruptible { writer.cache() }
        // Returning is not proof the bytes are there: the range can be short of what the player will
        // actually read, and the cache's own evictor can drop spans behind the writer. Only the cache
        // saying it holds the whole range settles it.
        if (!PodcastStreamCache.isFullyCached(appContext, episode.audioUrl, start, length)) return@withContext null
        Coverage(episode.audioUrl, start, start + length, bytesPerMs)
    }

    /**
     * Checks every [COVERAGE_CHECK_INTERVAL_MS], while the timer runs, that what was secured is still
     * on the phone: the stream cache's evictor is free to drop spans behind the pre-fetch, and a
     * download can go from under it. A hole drops the badge and secures the stretch left to play
     * again, so a night that stops being covered says so instead of quietly failing at 3 in the morning.
     */
    private fun startWatchdog(episode: PodcastEpisode) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(COVERAGE_CHECK_INTERVAL_MS)
                val end = _endAt.value ?: return@launch
                if (stillCovered(episode)) continue
                Log.w(TAG, "Sleep coverage lost for ${episode.title}, securing it again")
                _cacheProgress.value = 0f
                val remainingMin = ((end - System.currentTimeMillis()) / 60_000L + 1).toInt().coerceAtLeast(1)
                // Re-securing starts its own watchdog, so this one is done either way.
                preload(remainingMin)
                return@launch
            }
        }
    }

    /** Whether the audio still to play before the timer's end is all on the phone right now. */
    private suspend fun stillCovered(episode: PodcastEpisode): Boolean {
        // A downloaded episode is covered by its own file, and nothing but a deletion can change that.
        if (repo.downloads.isDownloaded(episode.id)) return true
        val secured = coverage ?: return false
        val positionMs = withContext(Dispatchers.Main) { repo.controller?.currentPosition ?: 0L }
        // Only what is left to play is checked: the stretch already behind the player can be evicted
        // freely, and asking for it back would send the badge red for nothing.
        val from = ((positionMs - PRELOAD_BACK_MARGIN_MS) * secured.bytesPerMs).toLong()
            .coerceIn(secured.startByte, secured.endByte)
        return withContext(Dispatchers.IO) {
            PodcastStreamCache.isFullyCached(appContext, secured.url, from, secured.endByte - from)
        }
    }
}
