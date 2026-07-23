package com.example.myapp.deezer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phase 0 feasibility spike. Not a normal unit test: it hits Deezer's live private API with a real
 * ARL and proves the whole pipeline end to end, writing a playable MP3 to disk. It is SKIPPED unless
 * an ARL is supplied, so it never runs in a normal build and the credential never touches git.
 *
 * Run it with:
 *   ./gradlew :app:testReleaseUnitTest --tests "com.example.myapp.deezer.DeezerSpikeTest" \
 *       -Ddeezer.arl=PASTE_ARL_HERE -Ddeezer.sngId=3135556
 *
 * The output file path is printed at the end; play it to confirm the decrypt worked.
 * Delete this file once Phase 1 is proven; only DeezerCrypto is meant to survive the spike.
 */
class DeezerSpikeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Session cookies accumulated across calls (starts with arl, gains sid etc.).
    private val cookies = linkedMapOf<String, String>()

    @Test
    fun endToEnd_arlToPlayableFile() {
        val arl = prop("deezer.arl")
        assumeTrue("Set -Ddeezer.arl=... to run the Deezer spike", !arl.isNullOrBlank())
        val sngId = prop("deezer.sngId") ?: "3135556"
        cookies["arl"] = arl!!.trim()

        // 1. Session bootstrap: api_token (checkForm) + license_token.
        val userData = gw("deezer.getUserData", "{}", apiToken = "")
        val results = userData["results"]!!.jsonObject
        val apiToken = results["checkForm"]!!.jsonPrimitive.content
        val user = results["USER"]!!.jsonObject
        val userId = user["USER_ID"]!!.jsonPrimitive.content
        val licenseToken = user["OPTIONS"]!!.jsonObject["license_token"]!!.jsonPrimitive.content
        println("SESSION userId=$userId apiToken.len=${apiToken.length} licenseToken.len=${licenseToken.length}")
        assert(apiToken.isNotBlank() && apiToken != "0") { "Empty api_token: ARL likely expired / guest session" }

        // 2. Track metadata: fresh TRACK_TOKEN.
        val songBody = """{"sng_id":"$sngId"}"""
        val song = gw("song.getData", songBody, apiToken = apiToken)["results"]!!.jsonObject
        val trackToken = song["TRACK_TOKEN"]!!.jsonPrimitive.content
        val title = song["SNG_TITLE"]?.jsonPrimitive?.content
        val artist = song["ART_NAME"]?.jsonPrimitive?.content
        println("TRACK $sngId = \"$title\" by $artist, trackToken.len=${trackToken.length}")

        // 3. Encrypted CDN URL from get_url. Ask for 320 then 128 as fallback.
        val cdnUrl = getUrl(licenseToken, trackToken)
        println("CDN url host=${URL(cdnUrl).host}")

        // 4. Download the encrypted stream.
        val encrypted = download(cdnUrl)
        println("Downloaded ${encrypted.size} encrypted bytes")

        // 5. Decrypt with the production crypto and write to disk.
        val decrypted = DeezerCrypto.decryptFullTrack(sngId, encrypted)
        val outFile = File(System.getProperty("java.io.tmpdir"), "deezer-spike-$sngId.mp3")
        outFile.writeBytes(decrypted)

        // MP3 sanity: file should start with an ID3 tag or an MPEG frame sync (0xFF 0xFB/0xFA/...).
        val b0 = decrypted[0].toInt() and 0xFF
        val b1 = decrypted[1].toInt() and 0xFF
        val looksLikeMp3 = (decrypted.size >= 3 &&
                decrypted[0].toInt() == 'I'.code && decrypted[1].toInt() == 'D'.code && decrypted[2].toInt() == '3'.code) ||
                (b0 == 0xFF && (b1 and 0xE0) == 0xE0)
        println("OUTPUT ${outFile.absolutePath} (${decrypted.size} bytes) firstBytes=%02x %02x looksLikeMp3=$looksLikeMp3".format(b0, b1))
        assert(looksLikeMp3) { "Decrypted output is not a recognizable MP3; key/stripe likely wrong" }
    }

    // ---- Deezer gw-light gateway ----

    private fun gw(method: String, body: String, apiToken: String): JsonObject {
        val url = "https://www.deezer.com/ajax/gw-light.php" +
                "?method=${enc(method)}&input=3&api_version=1.0&api_token=${enc(apiToken)}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("User-Agent", browserUa)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            setRequestProperty("Cookie", cookieHeader())
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        captureCookies(conn)
        val text = readBody(conn)
        val root = json.parseToJsonElement(text).jsonObject
        val error = root["error"]
        val hasError = error != null && error.toString() != "[]" && error.toString() != "{}"
        require(!hasError) { "gw $method returned error: $error" }
        return root
    }

    private fun getUrl(licenseToken: String, trackToken: String): String {
        val body = """
            {"license_token":"$licenseToken",
             "media":[{"type":"FULL","formats":[
                {"cipher":"BF_CBC_STRIPE","format":"MP3_320"},
                {"cipher":"BF_CBC_STRIPE","format":"MP3_128"}]}],
             "track_tokens":["$trackToken"]}
        """.trimIndent()
        val conn = (URL("https://media.deezer.com/v1/get_url").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("User-Agent", browserUa)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val root = json.parseToJsonElement(readBody(conn)).jsonObject
        val data0 = root["data"]!!.jsonArray[0].jsonObject
        val errors = data0["errors"]
        require(errors == null || errors.jsonArray.isEmpty()) { "get_url errors: $errors" }
        return data0["media"]!!.jsonArray[0].jsonObject["sources"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
    }

    private fun download(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", browserUa)
        }
        require(conn.responseCode in 200..299) { "CDN download failed: HTTP ${conn.responseCode}" }
        val buf = ByteArrayOutputStream()
        conn.inputStream.use { it.copyTo(buf) }
        return buf.toByteArray()
    }

    // ---- helpers ----

    private fun readBody(conn: HttpURLConnection): String =
        (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().use { it.readText() }

    private fun captureCookies(conn: HttpURLConnection) {
        conn.headerFields["Set-Cookie"]?.forEach { raw ->
            val pair = raw.substringBefore(";")
            val k = pair.substringBefore("=").trim()
            val v = pair.substringAfter("=", "").trim()
            if (k.isNotEmpty()) cookies[k] = v
        }
    }

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Resolves a value from, in order: a -D system property, an env var (DEEZER_ARL), or a
     * gitignored file at the repo root (.deezer_arl.local) for the ARL specifically. The file path
     * keeps the credential out of shell history and process args.
     */
    private fun prop(name: String): String? {
        System.getProperty(name)?.let { return it }
        System.getenv(name.replace('.', '_').uppercase())?.let { return it }
        if (name == "deezer.arl") {
            // Tests run with cwd at app/, so the repo root is one level up.
            for (path in listOf(File(".deezer_arl.local"), File("../.deezer_arl.local"))) {
                if (path.isFile) return path.readText().trim()
            }
        }
        return null
    }
}
