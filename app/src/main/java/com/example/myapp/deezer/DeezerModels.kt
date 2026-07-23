package com.example.myapp.deezer

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

/** Snapshot of the player for the mini bar and full sheet. Position/duration are read live from the controller. */
data class PlayerUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val sngId: String? = null
)
