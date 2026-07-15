package com.example.myapp

import java.net.HttpURLConnection
import java.net.URL

// Wikimedia and Open-Meteo both expect a descriptive User-Agent with contact info;
// requests without one can be rejected with an HTTP error.
const val USER_AGENT = "CognitoolsAndroid/1.0 (https://github.com/valentincherel; valentin.cherel22@yahoo.com)"

fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
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
