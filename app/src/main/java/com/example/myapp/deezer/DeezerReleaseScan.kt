package com.example.myapp.deezer

import android.util.Log
import com.example.myapp.matchNormalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val RELEASE_WINDOW_DAYS = 60L
/** An artist's releases are re-read at most this often: well inside [RELEASE_WINDOW_DAYS]. */
private const val ARTIST_RESCAN_DAYS = 4L
/** Artists whose releases are read on one scan, the best known first, the rest rotating in. */
private const val ARTISTS_PER_SCAN = 150
/** Album track fetches per scan. The rest stay unmarked, so the next scan picks them up. */
private const val ALBUMS_PER_SCAN = 40
private const val ARTIST_PARALLELISM = 6
private const val TAG = "DeezerReleaseScan"

/** What one scan read, applied to the state in one go by [DeezerDiscoveries] once the network part is over. */
internal class ScanResult(
    val artists: List<DeezerArtist>,
    val fetched: List<Pair<DeezerRelease, List<DeezerTrack>>>
)

/**
 * Diffs the known artists' discographies against the albums already seen and returns what came out
 * of it. Bounded twice over, because this is what makes the scan slow: only [ARTISTS_PER_SCAN]
 * artists are read per run, the best known ([known]) first and the rest rotating in oldest-read
 * first, and an artist read less than [ARTIST_RESCAN_DAYS] ago is skipped outright (the release
 * window is fifteen times that, so nothing is missed). Of what that turns up, the newest
 * [ALBUMS_PER_SCAN] candidates get their lead track fetched, the rest stay unseen for the next scan.
 *
 * Works on copies of the artist read dates ([scans]) and the seen albums ([seen]) and touches no
 * state, so it can run without the discoveries lock. Returns null when not one artist could be
 * read, i.e. the pass never happened.
 */
internal suspend fun scanNewReleases(
    repo: DeezerRepository,
    log: RollingLog,
    known: Map<String, Int>,
    scans: Map<String, String>,
    seen: Set<String>
): ScanResult? = withContext(Dispatchers.IO) {
    // Two sources, because neither covers the other: the profile tab is Deezer's own view of who
    // Valentin listens to and skips artists he only has a track or two from, while the library
    // artists are exactly the ones he liked, whatever Deezer thinks of his habits.
    val profile = runCatching { repo.profileArtists() }.getOrElse {
        Log.w(TAG, "Profile artists failed", it)
        emptyList()
    }
    val library = runCatching { repo.libraryArtists() }.getOrElse {
        Log.w(TAG, "Library artists failed", it)
        emptyList()
    }
    val all = (profile + library).distinctBy { it.id }
    if (all.isEmpty()) {
        log.log("scan: no artist reachable")
        return@withContext null
    }
    val cutoff = LocalDate.now().minusDays(RELEASE_WINDOW_DAYS).toString()
    val today = LocalDate.now().toString()
    val staleBefore = LocalDate.now().minusDays(ARTIST_RESCAN_DAYS).toString()

    val artists = all
        .filter { (scans[it.id] ?: "") < staleBefore }
        .sortedWith(
            compareByDescending<DeezerArtist> { known[it.name.matchNormalized()] ?: 0 }
                .thenBy { scans[it.id] ?: "" }
        )
        .take(ARTISTS_PER_SCAN)
    // Nothing stale left to read is a scan that is already done, not one that failed.
    if (artists.isEmpty()) return@withContext ScanResult(emptyList(), emptyList())

    val gate = Semaphore(ARTIST_PARALLELISM)
    val candidates = coroutineScope {
        artists.map { artist ->
            async {
                gate.withPermit {
                    runCatching { repo.artistReleases(artist.id, artist.name) }.getOrElse {
                        Log.w(TAG, "Releases failed for ${artist.name}", it)
                        emptyList()
                    }
                }
            }
        }.awaitAll()
    }.flatten().filter {
        it.releaseDate >= cutoff && it.releaseDate <= today && it.albumId !in seen
    }.distinctBy { it.albumId }
        .sortedByDescending { it.releaseDate }
        .take(ALBUMS_PER_SCAN)

    val fetched = coroutineScope {
        candidates.map { release ->
            async {
                gate.withPermit {
                    release to runCatching { repo.albumTracks(release) }.getOrElse {
                        Log.w(TAG, "Album tracks failed for ${release.title}", it)
                        emptyList()
                    }
                }
            }
        }.awaitAll()
    }
    log.log("scan: ${artists.size} artist(s), ${candidates.size} release(s) fetched")
    ScanResult(artists, fetched)
}
