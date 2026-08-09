package com.example.myapp.deezer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One proposed track, plus why it is being proposed: a fresh release from an artist Valentin follows, or a discovery. */
data class DiscoveryTrack(
    val track: DeezerTrack,
    val isNewRelease: Boolean,
    val releaseDate: String? = null
) {
    /** "Nouveauté · 12/07" for a release, null for a plain discovery. */
    fun note(): String? {
        if (!isNewRelease) return null
        val d = releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "Nouveauté"
        return "Nouveauté · %02d/%02d".format(d.dayOfMonth, d.monthValue)
    }
}

/**
 * What the UI renders: today's batch, whether a batch is being built right now (with how far along it
 * is, 0 to 100), and the last failure. [tracks] is updated optimistically: a row leaves the list the
 * instant it is tapped, while the like and the disk write finish in the background.
 */
data class DiscoveryState(
    val tracks: List<DiscoveryTrack> = emptyList(),
    val generating: Boolean = false,
    val progress: Int = 0,
    val error: String? = null
) {
    val newReleaseCount: Int get() = tracks.count { it.isNewRelease }
}

/**
 * The daily "Découvertes du jour" batch: at most [BATCH_SIZE] tracks Valentin has never been offered
 * before, each of which he can push into his favorites one by one or all at once. A handled track
 * leaves the batch, and once the batch is empty nothing shows until the next day brings a new one.
 *
 * Two sources feed it:
 *  - new releases from the artists on his Deezer profile, found by diffing each artist's public
 *    discography against the album ids we have already seen. They have priority and fill the batch as
 *    far as they go, newest first; what doesn't fit queues up in a backlog for the following days.
 *  - discoveries from Deezer's own personalized recommendations: Flow ([DeezerApi.flowTracks]), topped
 *    up with the track mix of a few random favorites when Flow runs thin after filtering. These only
 *    fill what the releases left free, so a heavy release week can leave none at all.
 *
 * Nothing already in his favorites, in Best pépites, or ever proposed before can appear. There is
 * deliberately no play tracking: Deezer's own listening history is a rolling window of 100 plays,
 * which is not worth the bookkeeping, so a track played once but never liked may be offered once.
 */
class DeezerDiscoveries(private val appContext: Context, private val repo: DeezerRepository) {

    companion object {
        const val BATCH_SIZE = 20
        private const val RELEASE_WINDOW_DAYS = 60L
        private const val FLOW_CALLS = 8
        private const val MIX_SEEDS = 4
        /** Album track fetches per scan. The rest stay unmarked, so the next scan picks them up. */
        private const val ALBUMS_PER_SCAN = 40
        private const val BACKLOG_CAP = 120
        private const val PROPOSED_CAP = 4000
        private const val SEEN_ALBUMS_CAP = 4000
        private const val ARTIST_PARALLELISM = 4
        /** Share of the progress bar owned by the daily release scan, and by its artist pass within that. */
        private const val SCAN_WEIGHT = 85
        private const val ARTIST_WEIGHT = 65
        private const val TAG = "DeezerDiscoveries"
    }

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state

    private val file: File by lazy { File(appContext.filesDir, "deezer_discoveries.json") }
    private val backupFile: File by lazy { File(appContext.filesDir, "deezer_discoveries.bak.json") }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Mirrors the file, loaded once. Only ever touched under [mutex].
    private var loaded = false
    private var batchDate: String = ""
    private var batch: MutableList<DiscoveryTrack> = mutableListOf()
    private var backlog: MutableList<DiscoveryTrack> = mutableListOf()
    private var proposed: LinkedHashSet<String> = LinkedHashSet()
    private var seenAlbums: LinkedHashSet<String> = LinkedHashSet()
    private var lastScanDate: String = ""

    /** Rows already gone from the screen whose real removal has not been written yet. */
    private val pendingRemoval: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ---- Public API ----
    // Everything here is fire and forget on [scope] rather than suspending on the caller: a scan walks
    // a few hundred artists and a "tout ajouter" sends twenty likes, and neither should die because
    // the screen that asked for it went away. [mutex] keeps the queued work in order.

    /** Reads the saved batch off disk and publishes it. No network. Called once at app start. */
    fun prime() {
        scope.launch { mutex.withLock { load() } }
    }

    /**
     * Builds today's batch if there isn't one yet, otherwise just republishes it. Safe to call on every
     * entry into the music screen; only the first call of a new day does any network work.
     */
    fun ensureToday() {
        launchExclusive {
            load()
            if (batchDate == today()) publish() else generate(keepNewReleases = false)
        }
    }

    /**
     * Throws away the discovery half and rolls a fresh one, as often as asked. New releases already on
     * screen stay put, since a refresh asked for out of boredom with the Flow picks should not burn
     * them, but a batch whose releases were all handled refills its release slots from the backlog.
     */
    fun regenerate() {
        launchExclusive {
            load()
            generate(keepNewReleases = true)
        }
    }

    /** Likes [item] and drops it from the batch. */
    fun add(item: DiscoveryTrack) {
        dropFromView(listOf(item))
        scope.launch { addOne(item) }
    }

    /** Drops [item] from the batch without liking it. It will never be proposed again. */
    fun dismiss(item: DiscoveryTrack) {
        dropFromView(listOf(item))
        scope.launch { handle(item) }
    }

    /**
     * Likes every remaining track. Returns how many are on their way in. The list empties on screen at
     * once and the likes are sent behind it, one at a time and never twenty in parallel:
     * [DeezerRepository.toggleFavorite] reads the favorites list and writes it back, so concurrent
     * likes would drop each other's entries.
     */
    fun addAll(): Int {
        val items = _state.value.tracks
        dropFromView(items)
        scope.launch { items.forEach { addOne(it) } }
        return items.size
    }

    /** Empties the batch without liking anything. */
    fun dismissAll() {
        val items = _state.value.tracks
        dropFromView(items)
        scope.launch { items.forEach { handle(it) } }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Runs [block] under [mutex], skipping it entirely if a generation is already in flight. */
    private fun launchExclusive(block: suspend () -> Unit) {
        if (_state.value.generating) return
        scope.launch { mutex.withLock { block() } }
    }

    // ---- Batch bookkeeping ----

    private suspend fun addOne(item: DiscoveryTrack) {
        if (!repo.isFavorite(item.track.sngId)) {
            runCatching { repo.toggleFavorite(item.track) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
        handle(item)
    }

    private suspend fun handle(item: DiscoveryTrack) = mutex.withLock {
        load()
        batch.removeAll { it.track.sngId == item.track.sngId }
        backlog.removeAll { it.track.sngId == item.track.sngId }
        remember(item)
        save()
        pendingRemoval.remove(item.track.sngId)
        publish()
    }

    /**
     * Takes [items] off the screen immediately, before anything is liked or written. [publish] keeps
     * hiding them until their real removal lands, so a bulk add empties the list in one go instead of
     * dropping a row at a time as each like comes back.
     */
    private fun dropFromView(items: List<DiscoveryTrack>) {
        items.forEach { pendingRemoval += it.track.sngId }
        _state.value = _state.value.copy(tracks = _state.value.tracks.filterNot { it.track.sngId in pendingRemoval })
    }

    private fun remember(item: DiscoveryTrack) {
        proposed += key(item.track)
        while (proposed.size > PROPOSED_CAP) proposed.remove(proposed.first())
    }

    private fun publish() {
        _state.value = DiscoveryState(
            tracks = batch.filterNot { it.track.sngId in pendingRemoval },
            generating = false,
            error = _state.value.error
        )
    }

    private fun setProgress(pct: Int) {
        _state.value = _state.value.copy(progress = pct.coerceIn(0, 100))
    }

    // ---- Generation ----

    private suspend fun generate(keepNewReleases: Boolean) {
        val kept = if (keepNewReleases) batch.filter { it.isNewRelease } else emptyList()
        pendingRemoval.clear()
        _state.value = DiscoveryState(tracks = kept, generating = true)
        try {
            // Favorites drive both the exclusion set and the track mix seeds, so they must be loaded.
            runCatching { repo.ensureFavorites() }
            val pepites = runCatching { repo.bestPepitesTracks() }.getOrDefault(emptyList())
            // The release scan runs once a day and is the slow part, so it owns most of the progress
            // bar. A regenerate later the same day skips it and the bar is all Flow.
            val needsScan = lastScanDate != today()
            if (needsScan) {
                runCatching { scanNewReleases() }
                    .onSuccess { lastScanDate = today() }
                    .onFailure { Log.w(TAG, "New release scan failed", it) }
            }

            // New releases first, newest release date first, then the personalized discoveries.
            val excluded = excludedKeys(pepites)
            // Your artists put out roughly nine releases a day, far more than the daily slots, so the
            // backlog has to stay honest: anything liked or handled in the meantime is dead weight.
            backlog.removeAll { key(it.track) in excluded }
            val collector = BatchCollector(excluded, kept)
            // New releases come first and take as much of the batch as the backlog can fill, so a heavy
            // release week can be all releases and Flow only gets what is left over.
            backlog.sortByDescending { it.releaseDate }
            for (item in backlog) {
                if (collector.full) break
                collector.offer(item)
            }
            fillWithDiscoveries(collector, base = if (needsScan) SCAN_WEIGHT else 0)

            // A release stays in the backlog until it is actually handled, so one that sat in a batch
            // nothing was done with isn't lost, it just comes back. Discoveries are one shot: recording
            // them as proposed right here is what stops a regenerate from handing back the same picks.
            collector.items.filterNot { it.isNewRelease }.forEach { remember(it) }

            batch = collector.items
            batchDate = today()
            save()
            publish()
        } catch (e: Exception) {
            Log.w(TAG, "Discovery batch generation failed", e)
            _state.value = DiscoveryState(tracks = kept, generating = false, error = e.message)
        }
    }

    /** Pulls Flow repeatedly, then the track mix of a few random favorites, until [collector] is full. */
    private suspend fun fillWithDiscoveries(collector: BatchCollector, base: Int) {
        repeat(FLOW_CALLS) { call ->
            if (collector.full) return
            val tracks = runCatching { repo.flowTracks() }.getOrElse {
                Log.w(TAG, "Flow call failed", it)
                emptyList()
            }
            tracks.forEach { collector.offer(DiscoveryTrack(it, isNewRelease = false)) }
            setProgress(base + (call + 1) * (100 - base) / FLOW_CALLS)
        }
        val seeds = (repo.favorites.value ?: emptyList()).shuffled().take(MIX_SEEDS)
        for (seed in seeds) {
            if (collector.full) return
            val mix = runCatching { repo.trackMix(seed.sngId) }.getOrElse {
                Log.w(TAG, "Track mix failed for ${seed.sngId}", it)
                emptyList()
            }
            mix.forEach { collector.offer(DiscoveryTrack(it, isNewRelease = false)) }
        }
    }

    /**
     * Accumulates one batch, refusing anything excluded, a duplicate, a second track by an artist
     * already in (twenty rows should read as twenty finds, not five artists), or anything past the cap.
     */
    private inner class BatchCollector(private val excluded: Set<String>, seed: List<DiscoveryTrack>) {
        val items = ArrayList<DiscoveryTrack>(seed)
        private val keys = seed.mapTo(HashSet()) { key(it.track) }
        private val artists = seed.mapTo(HashSet()) { normalize(it.track.artist) }

        val full: Boolean get() = items.size >= BATCH_SIZE

        fun offer(item: DiscoveryTrack): Boolean {
            if (full) return false
            val k = key(item.track)
            if (k in excluded || k in keys) return false
            if (!artists.add(normalize(item.track.artist))) return false
            keys += k
            items += item
            return true
        }
    }

    /**
     * Diffs every profile artist's discography against [seenAlbums] and turns anything released inside
     * the window into backlog entries. Bounded per run: the newest [ALBUMS_PER_SCAN] candidates get
     * their lead track fetched and marked seen, the rest stay unseen for the next scan.
     */
    private suspend fun scanNewReleases() = withContext(Dispatchers.IO) {
        val artists = repo.profileArtists()
        if (artists.isEmpty()) return@withContext
        val cutoff = LocalDate.now().minusDays(RELEASE_WINDOW_DAYS).toString()
        val today = today()

        val gate = Semaphore(ARTIST_PARALLELISM)
        val scanned = AtomicInteger()
        val candidates = coroutineScope {
            artists.map { artist ->
                async {
                    gate.withPermit {
                        runCatching { repo.artistReleases(artist.id, artist.name) }.getOrElse {
                            Log.w(TAG, "Releases failed for ${artist.name}", it)
                            emptyList()
                        }
                    }.also { setProgress(scanned.incrementAndGet() * ARTIST_WEIGHT / artists.size) }
                }
            }.awaitAll()
        }.flatten().filter {
            it.releaseDate >= cutoff && it.releaseDate <= today && it.albumId !in seenAlbums
        }.distinctBy { it.albumId }
            .sortedByDescending { it.releaseDate }
            .take(ALBUMS_PER_SCAN)

        val fetchedCount = AtomicInteger()
        val fetched = coroutineScope {
            candidates.map { release ->
                async {
                    gate.withPermit {
                        release to runCatching { repo.albumTracks(release) }.getOrElse {
                            Log.w(TAG, "Album tracks failed for ${release.title}", it)
                            emptyList()
                        }
                    }.also {
                        val done = fetchedCount.incrementAndGet()
                        setProgress(ARTIST_WEIGHT + done * (SCAN_WEIGHT - ARTIST_WEIGHT) / candidates.size)
                    }
                }
            }.awaitAll()
        }

        fetched.forEach { (release, tracks) ->
            seenAlbums += release.albumId
            // The lead track represents the release: one row per release, not a whole tracklist.
            val lead = tracks.firstOrNull() ?: return@forEach
            if (backlog.none { it.track.sngId == lead.sngId }) {
                backlog += DiscoveryTrack(lead, isNewRelease = true, releaseDate = release.releaseDate)
            }
        }
        while (seenAlbums.size > SEEN_ALBUMS_CAP) seenAlbums.remove(seenAlbums.first())
        backlog.sortByDescending { it.releaseDate }
        while (backlog.size > BACKLOG_CAP) backlog.removeAt(backlog.lastIndex)
    }

    /** Everything that must never be proposed: already liked, already in Best pépites, already offered. */
    private fun excludedKeys(pepites: List<DeezerTrack>): Set<String> {
        val out = HashSet<String>(proposed)
        (repo.favorites.value ?: emptyList()).forEach { out += key(it) }
        pepites.forEach { out += key(it) }
        return out
    }

    // ---- Identity ----
    // Matching on artist + title, not on sngId: the same song exists under many release ids, and a
    // favorite added years ago pins a different id than the one a recommendation hands back today.

    private fun key(track: DeezerTrack): String = track.matchKey

    private fun normalize(s: String): String = s.matchNormalized()

    private fun today(): String = LocalDate.now().toString()

    // ---- Persistence ----

    private suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            // The backup is the previous good state: it only loses the last batch's bookkeeping,
            // where a lost file means a whole new batch the same day and every track ever proposed
            // becoming fair game again.
            if (!readState(file)) readState(backupFile)
        }
        if (batchDate == today()) publish()
    }

    private fun readState(from: File): Boolean = runCatching {
        if (!from.exists()) return false
        val root = DeezerLibraryCache.json.parseToJsonElement(from.readText()).jsonObject
        batchDate = root["date"]?.jsonPrimitive?.content.orEmpty()
        lastScanDate = root["scan"]?.jsonPrimitive?.content.orEmpty()
        batch = root["tracks"]?.jsonArray.orEmpty().mapTo(mutableListOf()) { itemFromJson(it.jsonObject) }
        backlog = root["backlog"]?.jsonArray.orEmpty().mapTo(mutableListOf()) { itemFromJson(it.jsonObject) }
        proposed = root["proposed"]?.jsonArray.orEmpty()
            .mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
        seenAlbums = root["albums"]?.jsonArray.orEmpty()
            .mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
        true
    }.onFailure { Log.w(TAG, "Failed to read the discoveries state from ${from.name}", it) }
        .getOrDefault(false)

    /**
     * Written through a temp file and renamed over the real one, keeping the last version as a
     * backup. This state runs to a few hundred kilobytes (the batch, the backlog, up to
     * [PROPOSED_CAP] proposed keys and [SEEN_ALBUMS_CAP] album ids) and the app is killed with the
     * screen off all the time; a plain write caught mid flight left a truncated file, which reads
     * back as "no batch today" and hands out a second selection.
     */
    private suspend fun save() = withContext(Dispatchers.IO) {
        val root = buildJsonObject {
            put("date", batchDate)
            put("scan", lastScanDate)
            put("tracks", buildJsonArray { batch.forEach { add(itemToJson(it)) } })
            put("backlog", buildJsonArray { backlog.forEach { add(itemToJson(it)) } })
            put("proposed", buildJsonArray { proposed.forEach { add(it) } })
            put("albums", buildJsonArray { seenAlbums.forEach { add(it) } })
        }
        runCatching {
            val tmp = File(appContext.filesDir, "deezer_discoveries.json.tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.copyTo(backupFile, overwrite = true)
            check(tmp.renameTo(file)) { "rename failed" }
        }.onFailure { Log.w(TAG, "Failed to write the discoveries state", it) }
    }

    private fun itemToJson(item: DiscoveryTrack) = buildJsonObject {
        DeezerLibraryCache.trackToJson(item.track).forEach { (k, v) -> put(k, v) }
        if (item.isNewRelease) put("n", true)
        item.releaseDate?.let { put("r", it) }
    }

    private fun itemFromJson(o: kotlinx.serialization.json.JsonObject) = DiscoveryTrack(
        track = DeezerLibraryCache.trackFromJson(o),
        isNewRelease = o["n"]?.jsonPrimitive?.content == "true",
        releaseDate = o["r"]?.jsonPrimitive?.content?.ifBlank { null }
    )
}
