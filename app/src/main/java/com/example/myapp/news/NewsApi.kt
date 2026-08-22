package com.example.myapp.news

import com.example.myapp.httpGet
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val FEED_ACCEPT = "application/rss+xml, application/xml, text/xml, */*"
private const val PAGE_ACCEPT = "text/html,application/xhtml+xml"

/** Fetches [feed] and parses it into [categoryId]'s articles. */
fun fetchFeed(feed: NewsFeed, categoryId: String): List<NewsArticle> =
    parseFeed(httpGet(feed.url, accept = FEED_ACCEPT), feed.source, categoryId)

/**
 * Parses an RSS 2.0 or Atom feed. The outlets here mix both, and put their illustration in whichever
 * of media:content, media:thumbnail or enclosure they please, so every shape is tried in turn.
 */
fun parseFeed(xml: String, source: String, categoryId: String): List<NewsArticle> {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val items = doc.select("item").ifEmpty { doc.select("entry") }
    return items.mapNotNull { item ->
        val link = canonicalLink(item.itemLink().orEmpty()) ?: return@mapNotNull null
        val title = item.selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val rawSummary = item.firstText("description", "summary", "content")
        NewsArticle(
            link = link,
            title = title,
            // Feed summaries routinely carry markup (a wrapping <p>, a lead <img>); only the text is wanted.
            summary = Jsoup.parse(rawSummary.orEmpty()).text().trim(),
            imageUrl = item.itemImage(rawSummary),
            source = source,
            categoryId = categoryId,
            publishedAt = parseFeedDate(item.firstText("pubDate", "published", "updated", "dc:date"))
        )
    }
}

/** RSS puts the URL in the element's text, Atom in a link's href. */
private fun Element.itemLink(): String? {
    val links = getElementsByTag("link")
    links.firstOrNull { it.text().isNotBlank() }?.let { return it.text().trim() }
    val alternate = links.firstOrNull { it.attr("rel").isBlank() || it.attr("rel") == "alternate" }
    return (alternate ?: links.firstOrNull())?.attr("href")?.trim()?.ifBlank { null }
        ?: selectFirst("guid")?.text()?.trim()?.takeIf { it.startsWith("http") }
}

private fun Element.itemImage(rawSummary: String?): String? {
    val fromMedia = getElementsByTag("media:content").firstOrNull { it.attr("url").isNotBlank() }
        ?: getElementsByTag("media:thumbnail").firstOrNull { it.attr("url").isNotBlank() }
    fromMedia?.let { return it.attr("url") }
    getElementsByTag("enclosure")
        .firstOrNull { it.attr("type").startsWith("image") && it.attr("url").isNotBlank() }
        ?.let { return it.attr("url") }
    return Jsoup.parse(rawSummary.orEmpty()).selectFirst("img")?.attr("src")?.ifBlank { null }
}

private fun Element.firstText(vararg tags: String): String? = tags.firstNotNullOfOrNull { tag ->
    getElementsByTag(tag).firstOrNull()?.text()?.trim()?.ifBlank { null }
}

/**
 * The article URL stripped of what varies between feeds pointing at the same page: the fragment and
 * the campaign parameters (franceinfo appends `#xtor=RSS-…`). This is the article's identity, so two
 * feeds carrying the same story dedupe, and its read state survives being seen in another tab.
 */
fun canonicalLink(raw: String): String? {
    val trimmed = raw.trim().substringBefore('#')
    if (!trimmed.startsWith("http")) return null
    val query = trimmed.substringAfter('?', "")
        .split('&')
        .filter { it.isNotBlank() && !it.startsWith("utm_") && !it.startsWith("xtor") }
        .joinToString("&")
    val base = trimmed.substringBefore('?')
    return if (query.isBlank()) base else "$base?$query"
}

/** RFC 822 (RSS) then ISO 8601 (Atom). 0 when unparsable, which sorts the article last. */
fun parseFeedDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    val text = raw.trim()
    listOf(DateTimeFormatter.RFC_1123_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME).forEach { fmt ->
        runCatching { return ZonedDateTime.parse(text, fmt).toInstant().toEpochMilli() }
    }
    return runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(text)?.time ?: 0L
    }.getOrDefault(0L)
}

/**
 * An article's readable body. [truncated] means the page gave up too little text to be the whole
 * article, which on these outlets means a paywall: the screen then offers the browser instead.
 */
data class NewsArticleContent(
    val title: String?,
    val imageUrl: String?,
    val paragraphs: List<String>
) {
    val text: String get() = paragraphs.joinToString("\n\n")
    val truncated: Boolean get() = text.length < 600
}

fun fetchArticle(url: String): NewsArticleContent =
    extractArticle(httpGet(url, accept = PAGE_ACCEPT), url)

private val ARTICLE_CONTAINERS = listOf(
    "article", "[itemprop=articleBody]", ".article__content", ".article-body",
    ".content-article", "main"
)

// Boilerplate the outlets slip in among the real paragraphs.
private val JUNK_STARTS = listOf(
    "lire aussi", "à lire aussi", "voir aussi", "abonnez-vous", "newsletter", "partager",
    "publié le", "mis à jour le", "cet article est réservé", "il vous reste"
)

/**
 * Pulls the readable article out of a news page: the biggest of the usual body containers, its
 * paragraphs cleaned of the "Lire aussi" style inserts. No site specific rules, so a redesign
 * degrades to fewer paragraphs rather than to a crash; the caller falls back to the browser.
 */
fun extractArticle(html: String, url: String): NewsArticleContent {
    val doc = Jsoup.parse(html, url)
    doc.select("script, style, noscript, aside, nav, header, footer, form, figure, .related, .newsletter").remove()
    val best = ARTICLE_CONTAINERS
        .mapNotNull { selector -> doc.select(selector).firstOrNull() }
        .map { it.readableParagraphs() }
        .maxByOrNull { paragraphs -> paragraphs.sumOf { it.length } }
        .orEmpty()
    val paragraphs = best.ifEmpty { doc.body().readableParagraphs() }
    return NewsArticleContent(
        title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.ifBlank { null },
        imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
        paragraphs = paragraphs
    )
}

private fun Element.readableParagraphs(): List<String> =
    select("p").map { it.text().trim() }
        .filter { it.length >= 40 && JUNK_STARTS.none { junk -> it.startsWith(junk, ignoreCase = true) } }
        .distinct()
