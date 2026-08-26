package com.example.myapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Wikimedia and Open-Meteo both expect a descriptive User-Agent with contact info;
// requests without one can be rejected with an HTTP error.
const val USER_AGENT = "CognitoolsAndroid/1.0 (https://github.com/valentincherel; valentin.cherel22@yahoo.com)"

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000

// Growing pauses between retries, one per retried attempt. Long enough to outlast the throttling
// a shared carrier IP runs into, which a couple of hundred milliseconds never did.
private val RETRY_PAUSES_MS = longArrayOf(1_000, 3_000, 8_000)

/** A non-2xx response. Carries the status so callers can tell throttling (429) from a real failure. */
class HttpStatusException(val code: Int, message: String) : IOException(message)

fun httpGet(url: String, accept: String = "application/json"): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", accept)
        val code = conn.responseCode
        if (code !in 200..299) {
            val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText().orEmpty()
            throw HttpStatusException(code, "HTTP $code ${conn.responseMessage}: ${body.take(200)}")
        }
        return conn.inputStream.bufferedReader().readText()
    } finally {
        conn.disconnect()
    }
}

/**
 * [httpGet] on the IO dispatcher, retrying the failures that are transient by nature: 429, which a
 * public API hands out per IP (a mobile carrier's shared one hits the limit on traffic that isn't
 * even ours), and 5xx. Everything else fails on the first try, since retrying can't help.
 */
suspend fun httpGetRetrying(url: String, accept: String = "application/json", attempts: Int = 3): String =
    withContext(Dispatchers.IO) {
        repeat(attempts - 1) { attempt ->
            try {
                return@withContext httpGet(url, accept)
            } catch (e: HttpStatusException) {
                if (e.code != 429 && e.code !in 500..599) throw e
                delay(RETRY_PAUSES_MS[attempt.coerceAtMost(RETRY_PAUSES_MS.lastIndex)])
            }
        }
        httpGet(url, accept)
    }
