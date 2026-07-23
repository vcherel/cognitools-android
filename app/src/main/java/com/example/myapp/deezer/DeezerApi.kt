package com.example.myapp.deezer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    private val cookies = linkedMapOf<String, String>()

    // A realistic desktop browser UA; a scraper-looking UA can get blocked on gw calls.
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private companion object {
        const val GW_URL = "https://www.deezer.com/ajax/gw-light.php"
        const val GET_URL = "https://media.deezer.com/v1/get_url"
        val TOKEN_ERRORS = listOf("VALID_TOKEN_REQUIRED", "INVALID_TOKEN", "GATEWAY_ERROR", "CSRF_TOKEN")
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

    /** The owner's favorite (loved) tracks, most recently added first. */
    suspend fun getFavorites(session: DeezerSession, start: Int = 0, count: Int = 200): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val body = """{"user_id":"${session.userId}","start":$start,"nb":$count}"""
            val data = gw("favorite_song.getList", body, session.apiToken)
                .jsonObject["results"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
            data.sortedByDescending { it.jsonObject["DATE_ADD"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
                .map { parseGwTrack(it.jsonObject, it.jsonObject["SNG_ID"]?.jsonPrimitive?.content.orEmpty()) }
        }

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

    /** Tracks of a playlist in order. */
    suspend fun getPlaylistTracks(session: DeezerSession, playlistId: String, count: Int = 500): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val body = """{"playlist_id":"$playlistId","start":0,"nb":$count}"""
            val data = gw("playlist.getSongs", body, session.apiToken)
                .jsonObject["results"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
            data.map { parseGwTrack(it.jsonObject, it.jsonObject["SNG_ID"]?.jsonPrimitive?.content.orEmpty()) }
        }

    /** Catalog search via the public API (no auth). Returns tracks. */
    suspend fun searchTracks(query: String, limit: Int = 40): List<DeezerTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://api.deezer.com/search?q=${enc(query)}&limit=$limit"
        val conn = open(url, "GET")
        if (conn.responseCode !in 200..299) return@withContext emptyList()
        val data = json.parseToJsonElement(readBody(conn)).jsonObject["data"]?.jsonArray.orEmpty()
        data.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            DeezerTrack(
                sngId = id,
                title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                artist = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty(),
                album = o["album"]?.jsonObject?.get("title")?.jsonPrimitive?.content.orEmpty(),
                durationSec = o["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                coverMd5 = o["md5_image"]?.jsonPrimitive?.content?.ifBlank { null }
            )
        }
    }

    /** Builds a DeezerTrack from a gw song object (favorites / playlist / song.getData all share these keys). */
    private fun parseGwTrack(o: kotlinx.serialization.json.JsonObject, fallbackId: String) = DeezerTrack(
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

    /** Downloads the raw encrypted bytes from a CDN URL. Caller decrypts with DeezerCrypto. */
    suspend fun downloadEncrypted(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url, "GET").apply { readTimeout = 30_000 }
        if (conn.responseCode !in 200..299) throw DeezerApiException("CDN download failed: HTTP ${conn.responseCode}")
        conn.inputStream.use { it.readBytes() }
    }

    // ---- HTTP ----

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
