package com.example.myapp

import java.net.HttpURLConnection
import java.net.URL

// Wikimedia and Open-Meteo both expect a descriptive User-Agent with contact info;
// requests without one can be rejected with an HTTP error.
const val USER_AGENT = "CognitoolsAndroid/1.0 (https://github.com/valentincherel; valentin.cherel22@yahoo.com)"

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000

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
            throw Exception("HTTP $code ${conn.responseMessage}: ${body.take(200)}")
        }
        return conn.inputStream.bufferedReader().readText()
    } finally {
        conn.disconnect()
    }
}
