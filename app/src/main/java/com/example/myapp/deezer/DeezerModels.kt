package com.example.myapp.deezer

import com.example.myapp.matchNormalized

/**
 * Plain data models for the Deezer tool. gw-light responses are parsed with JsonElement navigation
 * in DeezerApi (Deezer's private JSON has inconsistent field types, so hand parsing is more robust
 * than @Serializable DTOs here). The clean public api.deezer.com DTOs come in Phase 2.
 */

/** Live session derived from the ARL. Held in memory only; refreshed when a call reports a token error. */
data class DeezerSession(
    val apiToken: String,       // checkForm, the CSRF token for gw calls
    val licenseToken: String,   // for media.deezer.com/get_url
    val userId: String,
    val canHq: Boolean,         // entitled to MP3_320
    val canLossless: Boolean,   // entitled to FLAC
    val obtainedAtMs: Long = System.currentTimeMillis()
) {
    /** Proactively refresh sessions older than this even without an explicit token error. */
    fun isStale(now: Long = System.currentTimeMillis()) = now - obtainedAtMs > 30 * 60 * 1000L
}

/** Audio format requested from get_url. Order matters: highest first, MP3_128 is the universal fallback. */
enum class DeezerQuality(val apiFormat: String, val label: String) {
    FLAC("FLAC", "FLAC"),
    MP3_320("MP3_320", "MP3 320"),
    MP3_128("MP3_128", "MP3 128");

    companion object {
        fun fromName(name: String?): DeezerQuality =
            entries.firstOrNull { it.name == name } ?: MP3_320
    }
}

/** Metadata for one track, enough to play it and show a now-playing card. */
data class DeezerTrack(
    val sngId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverMd5: String?
) {
    fun coverUrl(size: Int = 500): String? =
        coverMd5?.let { "https://e-cdns-images.dzcdn.net/images/cover/$it/${size}x$size-000000-80-0-0.jpg" }
}

/**
 * Identity of a song across release ids: the same track exists under many sngIds, and a favorite
 * added years ago pins a different one than the search hands back today, so anything asking "do I
 * already have this?" compares this instead.
 */
val DeezerTrack.matchKey: String
    get() = "${artist.matchNormalized()}|${title.matchNormalized()}"

/** One artist from the public catalog search, enough to show a card and shuffle their top tracks. */
data class DeezerArtist(val id: String, val name: String, val pictureUrl: String?)

/**
 * One release (album, EP or single) from an artist's public discography. [releaseDate] is Deezer's
 * "yyyy-MM-dd" string, kept verbatim so it can be compared and stored as is.
 */
data class DeezerRelease(
    val albumId: String,
    val title: String,
    val releaseDate: String,
    val recordType: String,
    val coverMd5: String?,
    val artistId: String,
    val artistName: String
)

/** One podcast show from Deezer's public catalog (search, chart), not yet followed. */
data class DeezerPodcastShow(val id: String, val title: String, val author: String, val artworkUrl: String?)

/** One episode of a Deezer podcast show. Streaming needs a further authenticated resolve (see DeezerRepository). */
data class DeezerPodcastEpisode(
    val id: String,
    val title: String,
    val releaseDateMs: Long,
    val durationSec: Int?,
    val artworkUrl: String?
)


/** A resolved, playable stream: fresh CDN URL plus the SNG_ID needed to derive the decrypt key. */
data class DeezerStream(
    val track: DeezerTrack,
    val cdnUrl: String,
    val format: DeezerQuality
)

/** One of the owner's playlists. */
data class DeezerPlaylist(
    val id: String,
    val title: String,
    val trackCount: Int,
    val pictureMd5: String?,
    val pictureType: String // "playlist" or "cover", drives the cover art path segment
) {
    fun coverUrl(size: Int = 500): String? =
        pictureMd5?.let { "https://e-cdns-images.dzcdn.net/images/$pictureType/$it/${size}x$size-000000-80-0-0.jpg" }
}

/**
 * Where a queued track came from. Tagged onto each MediaItem so that if its stream turns out to be
 * broken (a common case for artists who re-released the same song under a different sngId), the
 * fix found at playback time can be applied back at its actual source.
 */
sealed class TrackSource {
    object Favorites : TrackSource()
    data class Playlist(val id: String) : TrackSource()
}

/** Snapshot of the player for the mini bar and full sheet. Position/duration are read live from the controller. */
data class PlayerUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val sngId: String? = null,
    val source: TrackSource? = null
)

/** One row of the playback queue sheet. Built from the queued MediaItems, not from the track cache,
 *  so a queue that outlived the process still lists what it is actually going to play. */
data class QueueEntry(
    val sngId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?
)

/** The whole queue and where playback sits in it. */
data class QueueUiState(
    val entries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0
)
