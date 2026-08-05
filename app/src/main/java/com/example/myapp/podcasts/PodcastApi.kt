package com.example.myapp.podcasts

import com.example.myapp.httpGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Searches Apple's public podcast directory (no key required) for [query]. */
fun searchPodcasts(query: String): List<PodcastSearchResult> {
    val url = "https://itunes.apple.com/search?media=podcast&limit=25&term=" +
        URLEncoder.encode(query, "UTF-8")
    val root = json.parseToJsonElement(httpGet(url)).jsonObject
    val results = root["results"]?.jsonArray ?: return emptyList()
    return results.mapNotNull { el ->
        val o = el.jsonObject
        val feedUrl = o["feedUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        PodcastSearchResult(
            feedUrl = feedUrl,
            title = o["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            author = o["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artworkUrl = o["artworkUrl600"]?.jsonPrimitive?.contentOrNull
                ?: o["artworkUrl100"]?.jsonPrimitive?.contentOrNull
        )
    }
}

/** Fetches and parses [favorite]'s RSS feed into episodes, newest first. [seen] is applied by the caller. */
fun fetchEpisodes(favorite: PodcastFavorite): List<PodcastEpisode> {
    val xml = httpGet(favorite.id, accept = "application/rss+xml, application/xml, text/xml, */*")
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val items = doc.select("channel > item")
    return items.mapNotNull { item ->
        val audioUrl = item.selectFirst("enclosure")?.attr("url")?.ifBlank { null } ?: return@mapNotNull null
        val title = item.selectFirst("title")?.text().orEmpty()
        val pubDate = parsePubDate(item.selectFirst("pubDate")?.text())
        val durationSec = parseDuration(
            item.getElementsByTag("itunes:duration").firstOrNull()?.text()
        )
        PodcastEpisode(
            id = audioUrl,
            podcastId = favorite.id,
            podcastTitle = favorite.title,
            podcastArtworkUrl = favorite.artworkUrl,
            title = title,
            pubDate = pubDate,
            audioUrl = audioUrl,
            durationSec = durationSec,
            seen = false
        )
    }.sortedByDescending { it.pubDate }
}

/** Parses an RSS pubDate (RFC 822, e.g. "Wed, 15 Jun 2022 09:00:00 +0000"). Falls back to 0 (unknown). */
private fun parsePubDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return runCatching {
        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            fmt.parse(raw.trim())?.time ?: 0L
        }.getOrDefault(0L)
    }
}

/** Parses an itunes:duration value: plain seconds, "mm:ss" or "hh:mm:ss". Null if unparsable. */
private fun parseDuration(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    if (!trimmed.contains(":")) return trimmed.toIntOrNull()
    val parts = trimmed.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0) { acc, part -> acc * 60 + part }
}
