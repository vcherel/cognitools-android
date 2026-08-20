package com.example.myapp.deezer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Thrown by DeezerApi. [tokenError] means the session is stale and the caller should refresh once and retry. */
class DeezerApiException(message: String, val tokenError: Boolean = false) : Exception(message)

/**
 * Low level access to Deezer's private gw-light gateway and media.deezer.com. Knows nothing about
 * UI or playback. The proven Phase 0 spike is the reference for this flow. All calls run on IO.
 *
 * Cookies (arl + the sid Deezer sets) are kept per instance, so one DeezerApi maps to one session.
 */
class DeezerApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val backgroundGate = Mutex()
    private var lastBackgroundCallMs = 0L
    private val cookies = linkedMapOf<String, String>()

    // A realistic desktop browser UA; a scraper-looking UA can get blocked on gw calls.
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private companion object {
        const val GW_URL = "https://www.deezer.com/ajax/gw-light.php"
        const val GET_URL = "https://media.deezer.com/v1/get_url"
        val TOKEN_ERRORS = listOf("VALID_TOKEN_REQUIRED", "INVALID_TOKEN", "GATEWAY_ERROR", "CSRF_TOKEN")
        const val QUOTA_RETRIES = 4
        const val QUOTA_BACKOFF_MS = 1500L
        const val FOREGROUND_BACKOFF_MS = 400L
        /** ~8 calls a second, under Deezer's ~50 per 5 s, with room to spare for the foreground. */
        const val BACKGROUND_SPACING_MS = 120L
    }

    /** Bootstraps a session from the ARL. Throws [DeezerApiException] if the ARL is expired (guest session). */
    suspend fun bootstrapSession(arl: String): DeezerSession = withContext(Dispatchers.IO) {
        cookies.clear()
        cookies["arl"] = arl.trim()
        val results = gw("deezer.getUserData", "{}", apiToken = "").jsonObject["results"]!!.jsonObject
        val apiToken = results["checkForm"]?.jsonPrimitive?.content.orEmpty()
        if (apiToken.isBlank() || apiToken == "0") {
            throw DeezerApiException("ARL expired or invalid (guest session)", tokenError = true)
        }
        val user = results["USER"]!!.jsonObject
        val userId = user["USER_ID"]?.jsonPrimitive?.content ?: "0"
        val options = user["OPTIONS"]?.jsonObject
        val licenseToken = options?.get("license_token")?.jsonPrimitive?.content.orEmpty()
        val canHq = options?.get("web_hq")?.jsonPrimitive?.booleanOrNull
            ?: options?.get("web_sound_quality")?.jsonObject?.get("high")?.jsonPrimitive?.booleanOrNull ?: false
        val canLossless = options?.get("web_lossless")?.jsonPrimitive?.booleanOrNull
            ?: options?.get("web_sound_quality")?.jsonObject?.get("lossless")?.jsonPrimitive?.booleanOrNull ?: false
        DeezerSession(apiToken, licenseToken, userId, canHq, canLossless)
    }

    /** Fetches track metadata plus a fresh, short lived TRACK_TOKEN. */
    suspend fun getTrack(session: DeezerSession, sngId: String): Pair<DeezerTrack, String> =
        withContext(Dispatchers.IO) {
            val r = gw("song.getData", """{"sng_id":"$sngId"}""", session.apiToken).jsonObject["results"]!!.jsonObject
            val trackToken = r["TRACK_TOKEN"]?.jsonPrimitive?.content
                ?: throw DeezerApiException("No TRACK_TOKEN for $sngId")
            parseGwTrack(r, sngId) to trackToken
        }

    /**
     * All of the owner's favorite (loved) tracks, most recently added first. Pages through the gw
     * gateway until it runs dry, so there is no 200 track ceiling. Metadata only, no audio.
     */
    suspend fun getFavorites(session: DeezerSession): List<DeezerTrack> = withContext(Dispatchers.IO) {
        favoriteSongObjects(session).byDateAdded()
    }

    /** The raw gw song objects of the owner's favorites, which carry more than [DeezerTrack] keeps. */
    private fun favoriteSongObjects(session: DeezerSession): List<JsonObject> =
        gwSongPages("favorite_song.getList", session.apiToken) { start, nb ->
            """{"user_id":"${session.userId}","start":$start,"nb":$nb}"""
        }

    /** Pages a gw song list until it runs dry, so there is no 200 track ceiling. */
    private fun gwSongPages(method: String, apiToken: String, body: (Int, Int) -> String): List<JsonObject> {
        val page = 2000
        val objs = ArrayList<JsonObject>()
        var start = 0
        var iter = 0
        while (iter++ < 50) {
            val data = gw(method, body(start, page), apiToken)
                .jsonObject["results"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
            if (data.isEmpty()) break
            data.forEach { objs += it.jsonObject }
            start += data.size
            if (data.size < page) break
        }
        return objs
    }

    private fun List<JsonObject>.byDateAdded(): List<DeezerTrack> =
        sortedByDescending { it["DATE_ADD"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
            .map { parseGwTrack(it, it["SNG_ID"]?.jsonPrimitive?.content.orEmpty()) }

    /** The owner's own playlists (created by them), newest first. Excludes followed playlists. */
    suspend fun getPlaylists(session: DeezerSession, count: Int = 100): List<DeezerPlaylist> =
        withContext(Dispatchers.IO) {
            val body = """{"user_id":"${session.userId}","tab":"playlists","nb":$count}"""
            val data = gw("deezer.pageProfile", body, session.apiToken)
                .jsonObject["results"]?.jsonObject?.get("TAB")?.jsonObject
                ?.get("playlists")?.jsonObject?.get("data")?.jsonArray.orEmpty()
            data.filter { it.jsonObject["PARENT_USER_ID"]?.jsonPrimitive?.content == session.userId }
                .map {
                    val o = it.jsonObject
                    DeezerPlaylist(
                        id = o["PLAYLIST_ID"]?.jsonPrimitive?.content.orEmpty(),
                        title = o["TITLE"]?.jsonPrimitive?.content.orEmpty(),
                        trackCount = o["NB_SONG"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        pictureMd5 = o["PLAYLIST_PICTURE"]?.jsonPrimitive?.content?.ifBlank { null },
                        pictureType = o["PICTURE_TYPE"]?.jsonPrimitive?.content?.ifBlank { null } ?: "playlist"
                    )
                }
        }

    /** All tracks of a playlist, most recently added first. Pages until exhausted, so 3000+ track playlists load fully. */
    suspend fun getPlaylistTracks(session: DeezerSession, playlistId: String): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            playlistSongObjects(session, playlistId).byDateAdded()
        }

    private fun playlistSongObjects(session: DeezerSession, playlistId: String): List<JsonObject> =
        gwSongPages("playlist.getSongs", session.apiToken) { start, nb ->
            """{"playlist_id":"$playlistId","start":$start,"nb":$nb}"""
        }

    /**
     * Deezer's Flow for the owner: a dozen personalized tracks, built from their listening habits and
     * favorites. Each call advances the radio, so consecutive calls hand back different tracks (four
     * calls in a row measured 47 distinct tracks with no repeat).
     */
    suspend fun flowTracks(session: DeezerSession): List<DeezerTrack> = withContext(Dispatchers.IO) {
        val body = """{"user_id":"${session.userId}"}"""
        gw("radio.getUserRadio", body, session.apiToken)
            .jsonObject["results"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
            .map { parseGwTrack(it.jsonObject, it.jsonObject["SNG_ID"]?.jsonPrimitive?.content.orEmpty()) }
    }

    /** The tracks Deezer considers close to [sngId] (the mix behind a track page). Seeds discoveries when Flow runs thin. */
    suspend fun trackMix(session: DeezerSession, sngId: String, limit: Int = 30): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val body = """{"sng_id":"$sngId","start":0,"nb":$limit}"""
            gw("song.getSearchTrackMix", body, session.apiToken)
                .jsonObject["results"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
                .map { parseGwTrack(it.jsonObject, it.jsonObject["SNG_ID"]?.jsonPrimitive?.content.orEmpty()) }
        }

    /**
     * The artists on the owner's own profile page, which is Deezer's own view of who they listen to.
     * One call returns all of them: the gw `nb` parameter is ignored on this tab.
     */
    suspend fun profileArtists(session: DeezerSession): List<DeezerArtist> = withContext(Dispatchers.IO) {
        val body = """{"user_id":"${session.userId}","tab":"artists","nb":2000}"""
        gw("deezer.pageProfile", body, session.apiToken)
            .jsonObject["results"]?.jsonObject?.get("TAB")?.jsonObject
            ?.get("artists")?.jsonObject?.get("data")?.jsonArray.orEmpty()
            .mapNotNull { el ->
                val o = el.jsonObject
                val id = o["ART_ID"]?.jsonPrimitive?.content?.ifBlank { null } ?: return@mapNotNull null
                DeezerArtist(
                    id = id,
                    name = o["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
                    pictureUrl = o["ART_PICTURE"]?.jsonPrimitive?.content?.ifBlank { null }
                        ?.let { "https://e-cdns-images.dzcdn.net/images/artist/$it/250x250-000000-80-0-0.jpg" }
                )
            }
    }

    /**
     * Every artist behind the owner's favorites and behind [playlistIds], from the gw song objects,
     * which carry the artist id the [DeezerTrack] model drops. The profile tab is Deezer's own view
     * of who they listen to and leaves out artists they only have a track or two from, whose new
     * releases would then never be scanned.
     */
    suspend fun libraryArtists(session: DeezerSession, playlistIds: List<String>): List<DeezerArtist> =
        withContext(Dispatchers.IO) {
            val objs = favoriteSongObjects(session) +
                playlistIds.flatMap { runCatching { playlistSongObjects(session, it) }.getOrDefault(emptyList()) }
            objs.mapNotNull { o ->
                val id = o["ART_ID"]?.jsonPrimitive?.content?.ifBlank { null } ?: return@mapNotNull null
                DeezerArtist(id = id, name = o["ART_NAME"]?.jsonPrimitive?.content.orEmpty(), pictureUrl = null)
            }.distinctBy { it.id }
        }

    /**
     * An artist's whole discography from the public API, newest first. Deezer's own `order` parameter
     * is silently ignored (it always groups albums, then EPs, then singles), so this sorts by release
     * date here. Singles come last in that grouping, so a new single by an artist with a long back
     * catalog sits past the first pages: this reads up to [pages] pages of 100, stopping as soon as
     * one comes back short.
     */
    suspend fun artistReleases(artistId: String, artistName: String, pages: Int = 8): List<DeezerRelease> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<DeezerRelease>()
            for (page in 0 until pages) {
                val data = fetchDataArray(
                    "https://api.deezer.com/artist/$artistId/albums?limit=100&index=${page * 100}",
                    background = true
                )
                data.forEach { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content ?: return@forEach
                    out += DeezerRelease(
                        albumId = id,
                        title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                        releaseDate = o["release_date"]?.jsonPrimitive?.content.orEmpty(),
                        recordType = o["record_type"]?.jsonPrimitive?.content.orEmpty(),
                        coverMd5 = o["md5_image"]?.jsonPrimitive?.content?.ifBlank { null },
                        artistId = artistId,
                        artistName = artistName
                    )
                }
                if (data.size < 100) break
            }
            out.sortedByDescending { it.releaseDate }
        }

    /**
     * A release's tracks from the public API. These track objects carry no album title (they are
     * already nested under one), so [release] fills that in.
     */
    suspend fun albumTracks(release: DeezerRelease, limit: Int = 50): List<DeezerTrack> = withContext(Dispatchers.IO) {
        fetchDataArray("https://api.deezer.com/album/${release.albumId}/tracks?limit=$limit", background = true).mapNotNull {
            parsePublicTrack(it.jsonObject)?.copy(
                album = release.title,
                artist = it.jsonObject["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.ifBlank { null }
                    ?: release.artistName
            )
        }
    }

    // ---- Mutations (like/unlike, add/remove from a playlist) ----

    /** Adds a track to the owner's favorites (loved tracks). */
    suspend fun addFavorite(session: DeezerSession, sngId: String): Unit = withContext(Dispatchers.IO) {
        gw("favorite_song.add", """{"SNG_ID":"$sngId"}""", session.apiToken)
    }

    /** Removes a track from the owner's favorites. */
    suspend fun removeFavorite(session: DeezerSession, sngId: String): Unit = withContext(Dispatchers.IO) {
        gw("favorite_song.remove", """{"SNG_ID":"$sngId"}""", session.apiToken)
    }

    /** Appends a track to a playlist the owner controls. */
    suspend fun addSongToPlaylist(session: DeezerSession, playlistId: String, sngId: String): Unit =
        withContext(Dispatchers.IO) {
            gw("playlist.addSongs", """{"playlist_id":"$playlistId","songs":[["$sngId",0]],"offset":-1}""", session.apiToken)
        }

    /** Removes a track from a playlist the owner controls. */
    suspend fun removeSongFromPlaylist(session: DeezerSession, playlistId: String, sngId: String): Unit =
        withContext(Dispatchers.IO) {
            gw("playlist.deleteSongs", """{"playlist_id":"$playlistId","songs":[["$sngId",0]]}""", session.apiToken)
        }

    /** Catalog search via the public API (no auth). Returns tracks. */
    suspend fun searchTracks(query: String, limit: Int = 40): List<DeezerTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        fetchDataArray("https://api.deezer.com/search?q=${enc(query)}&limit=$limit").mapNotNull { parsePublicTrack(it.jsonObject) }
    }

    /** Best matching artists for [query] via the public API (no auth), most relevant first. */
    suspend fun searchArtists(query: String, limit: Int = 10): List<DeezerArtist> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val data = fetchDataArray("https://api.deezer.com/search/artist?q=${enc(query)}&limit=$limit")
        data.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: return@mapNotNull null
            DeezerArtist(id = id, name = name, pictureUrl = o["picture_medium"]?.jsonPrimitive?.content?.ifBlank { null })
        }
    }

    /** An artist's most popular tracks (their "Top" chart), enough to shuffle a representative sample. */
    suspend fun artistTopTracks(artistId: String, limit: Int = 50): List<DeezerTrack> = withContext(Dispatchers.IO) {
        fetchDataArray("https://api.deezer.com/artist/$artistId/top?limit=$limit").mapNotNull { parsePublicTrack(it.jsonObject) }
    }

    /** Podcast shows matching [query], via the public API (no auth). */
    suspend fun searchPodcastShows(query: String, limit: Int = 25): List<DeezerPodcastShow> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        fetchDataArray("https://api.deezer.com/search/podcast?q=${enc(query)}&limit=$limit").mapNotNull { parsePodcastShow(it.jsonObject) }
    }

    /** Deezer's global trending podcast shows, via the public API (no auth). Powers podcast recommendations. */
    suspend fun podcastChart(limit: Int = 20): List<DeezerPodcastShow> = withContext(Dispatchers.IO) {
        fetchDataArray("https://api.deezer.com/chart/0/podcasts?limit=$limit").mapNotNull { parsePodcastShow(it.jsonObject) }
    }

    /** A show's episodes, most recent first, via the public API (no auth). First page only (up to [limit]). */
    suspend fun podcastEpisodes(showId: String, limit: Int = 100): List<DeezerPodcastEpisode> = withContext(Dispatchers.IO) {
        val data = fetchDataArray("https://api.deezer.com/podcast/$showId/episodes?limit=$limit")
        data.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            DeezerPodcastEpisode(
                id = id,
                title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                releaseDateMs = parseDeezerDate(o["release_date"]?.jsonPrimitive?.content),
                durationSec = o["duration"]?.jsonPrimitive?.content?.toIntOrNull(),
                artworkUrl = o["picture"]?.jsonPrimitive?.content?.ifBlank { null }
            )
        }
    }

    /** Deezer's podcast objects (search/chart) carry no distinct author field, only title + description. */
    private fun parsePodcastShow(o: JsonObject): DeezerPodcastShow? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        return DeezerPodcastShow(
            id = id,
            title = o["title"]?.jsonPrimitive?.content.orEmpty(),
            author = "",
            artworkUrl = o["picture_medium"]?.jsonPrimitive?.content?.ifBlank { null }
                ?: o["picture"]?.jsonPrimitive?.content?.ifBlank { null }
        )
    }

    /** Parses a Deezer "yyyy-MM-dd HH:mm:ss" date into epoch millis. Falls back to 0 (unknown). */
    private fun parseDeezerDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(raw.trim())?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** Builds a DeezerTrack from a public api.deezer.com track object (search and artist/top share these keys). */
    private fun parsePublicTrack(o: JsonObject): DeezerTrack? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        return DeezerTrack(
            sngId = id,
            title = o["title"]?.jsonPrimitive?.content.orEmpty(),
            artist = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty(),
            album = o["album"]?.jsonObject?.get("title")?.jsonPrimitive?.content.orEmpty(),
            durationSec = o["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            coverMd5 = o["md5_image"]?.jsonPrimitive?.content?.ifBlank { null }
        )
    }

    /** Builds a DeezerTrack from a gw song object (favorites / playlist / song.getData all share these keys). */
    private fun parseGwTrack(o: JsonObject, fallbackId: String) = DeezerTrack(
        sngId = o["SNG_ID"]?.jsonPrimitive?.content ?: fallbackId,
        title = o["SNG_TITLE"]?.jsonPrimitive?.content.orEmpty(),
        artist = o["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
        album = o["ALB_TITLE"]?.jsonPrimitive?.content.orEmpty(),
        durationSec = o["DURATION"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        coverMd5 = o["ALB_PICTURE"]?.jsonPrimitive?.content?.ifBlank { null }
    )

    /**
     * Resolves a playable encrypted CDN URL for the highest quality available at or below [preferred],
     * always allowing MP3_128 as the final fallback.
     */
    suspend fun resolveStream(session: DeezerSession, sngId: String, preferred: DeezerQuality): DeezerStream =
        withContext(Dispatchers.IO) {
            val (track, trackToken) = getTrack(session, sngId)
            val formats = qualityChain(preferred)
            val formatsJson = formats.joinToString(",") {
                """{"cipher":"BF_CBC_STRIPE","format":"${it.apiFormat}"}"""
            }
            val body = """{"license_token":"${session.licenseToken}","media":[{"type":"FULL","formats":[$formatsJson]}],"track_tokens":["$trackToken"]}"""
            val root = json.parseToJsonElement(postJson(GET_URL, body)).jsonObject
            val data0 = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw DeezerApiException("get_url returned no data for $sngId")
            val errors = data0["errors"]?.jsonArray
            if (!errors.isNullOrEmpty()) {
                val msg = errors.joinToString { it.jsonObject["message"]?.jsonPrimitive?.content.orEmpty() }
                throw DeezerApiException("get_url error for $sngId: $msg", tokenError = errors.toString().contains("token", true))
            }
            val media0 = data0["media"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw DeezerApiException("Track $sngId not available in any requested format")
            val url = media0["sources"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
            val actualFormat = media0["format"]?.jsonPrimitive?.content?.let { fmt ->
                DeezerQuality.entries.firstOrNull { it.apiFormat == fmt }
            } ?: formats.first()
            DeezerStream(track, url, actualFormat)
        }

    private fun qualityChain(preferred: DeezerQuality): List<DeezerQuality> =
        (listOf(preferred) + DeezerQuality.MP3_128).distinct()

    // ---- HTTP ----

    /**
     * One public API list call. Deezer answers 200 with an error object rather than an HTTP status
     * when the ~50 calls per 5 s quota is hit, and returning an empty list there reads exactly like
     * "this artist released nothing", so a quota answer is waited out and retried.
     *
     * [background] is the release scan, which walks hundreds of artists: its calls are spaced to stay
     * under the quota and it waits patiently when it still trips it. A foreground call (a search the
     * user is watching) is never spaced and gives up after one short retry, because coming back
     * empty beats making them wait fifteen seconds behind the scan.
     */
    private suspend fun fetchDataArray(url: String, background: Boolean = false): List<JsonElement> {
        if (background) spaceBackgroundCall()
        val retries = if (background) QUOTA_RETRIES else 2
        val backoff = if (background) QUOTA_BACKOFF_MS else FOREGROUND_BACKOFF_MS
        repeat(retries) { attempt ->
            val conn = open(url, "GET")
            if (conn.responseCode !in 200..299) return emptyList()
            val root = json.parseToJsonElement(readBody(conn)).jsonObject
            root["data"]?.jsonArray?.let { return it }
            if (root["error"]?.toString()?.contains("quota", ignoreCase = true) != true) return emptyList()
            delay(backoff * (attempt + 1))
        }
        return emptyList()
    }

    /**
     * Holds background calls to [BACKGROUND_SPACING_MS] apart, leaving quota for what the user is
     * doing. Each caller reserves its own slot under the lock and then waits for it on its own, so
     * the requests overlap: waiting inside the lock instead would serialize the whole round trip and
     * make the spacing cost the request latency on top, which is what made the release scan crawl.
     */
    private suspend fun spaceBackgroundCall() {
        val slot = backgroundGate.withLock {
            val next = maxOf(System.currentTimeMillis(), lastBackgroundCallMs + BACKGROUND_SPACING_MS)
            lastBackgroundCallMs = next
            next
        }
        val wait = slot - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }

    private fun gw(method: String, body: String, apiToken: String): kotlinx.serialization.json.JsonElement {
        val url = "$GW_URL?method=${enc(method)}&input=3&api_version=1.0&api_token=${enc(apiToken)}"
        val conn = open(url, "POST").apply {
            setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            setRequestProperty("Cookie", cookieHeader())
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        captureCookies(conn)
        val root = json.parseToJsonElement(readBody(conn))
        val error = root.jsonObject["error"]
        val errStr = error?.toString().orEmpty()
        val hasError = error != null && errStr != "[]" && errStr != "{}"
        if (hasError) {
            val tokenError = TOKEN_ERRORS.any { errStr.contains(it, ignoreCase = true) }
            throw DeezerApiException("gw $method error: $errStr", tokenError)
        }
        return root
    }

    private fun postJson(url: String, body: String): String {
        val conn = open(url, "POST").apply { setRequestProperty("Content-Type", "application/json") }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readBody(conn)
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = method == "POST"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun captureCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { raw ->
            val pair = raw.substringBefore(";")
            val k = pair.substringBefore("=").trim()
            val v = pair.substringAfter("=", "").trim()
            if (k.isNotEmpty() && v.isNotEmpty() && v != "deleted") cookies[k] = v
        }
    }

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
