package com.example.myapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun WikipediaScreen(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(false) }
    var wikiContent by remember { mutableStateOf<WikipediaContent?>(null) }
    var displayedParagraphs by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedLanguage by remember { mutableStateOf("fr") }

    var navigationHistory by remember { mutableStateOf<List<WikipediaContent>>(emptyList()) }

    val scope = rememberCoroutineScope()

    // Shared loading choreography. Followed links stack onto the history,
    // a fresh random article clears it.
    fun load(addToHistory: Boolean, fetch: suspend () -> WikipediaContent) {
        scope.launch {
            isLoading = true
            error = null
            displayedParagraphs = 1
            try {
                val newContent = fetch()
                navigationHistory = if (addToHistory) {
                    (navigationHistory + listOfNotNull(wikiContent)).takeLast(5)
                } else {
                    emptyList()
                }
                wikiContent = newContent
            } catch (e: Exception) {
                error = "Erreur de chargement: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    val goBackInHistory: () -> Unit = {
        if (navigationHistory.isNotEmpty()) {
            wikiContent = navigationHistory.last()
            navigationHistory = navigationHistory.dropLast(1)
            displayedParagraphs = 1
            error = null
        } else {
            onBack()
        }
    }

    BackHandler { goBackInHistory() }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        ScreenTopBar(title = "Wikipedia", onBack = goBackInHistory) {
            if (navigationHistory.isNotEmpty()) {
                Text(
                    text = " (${navigationHistory.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                MyButton(
                    text = "Je veux me perdre",
                    onClick = {
                        load(addToHistory = false) { fetchCompleteWikipedia(selectedLanguage) }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.weight(0.8f).height(75.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                MyButton(
                    text = if (selectedLanguage == "fr") "FR" else "EN",
                    onClick = {
                        selectedLanguage = if (selectedLanguage == "fr") "en" else "fr"
                    },
                    modifier = Modifier.weight(0.2f).height(75.dp)
                )
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }

            error?.let {
                ErrorText(
                    message = it,
                    onDismiss = { error = null },
                    modifier = Modifier.padding(16.dp)
                )
            }

            wikiContent?.let { content ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        val doc = remember(content.fullContentHtml) {
                            Jsoup.parse(content.fullContentHtml)
                        }
                        val paragraphs = remember(doc) {
                            doc.body()
                                .select("p")
                                .filter { p ->
                                    val text = p.text().trim()
                                    val wordCount = text.split(whitespaceRegex).size
                                    wordCount >= 10 &&
                                            p.parents().none { it.tagName() == "table" && it.hasClass("infobox") } &&
                                            excludeStarts.none { start -> text.startsWith(start, ignoreCase = true) } &&
                                            !text.contains("redirige ici. Pour", ignoreCase = true) &&
                                            p.select("a").none { it.text().contains("Écouter") }
                                }
                        }
                        val paragraphsToShow = paragraphs.take(displayedParagraphs)

                        HtmlTextWithLinks(
                            html = paragraphsToShow.joinToString("\n\n") { it.outerHtml().trim() },
                            language = content.language,
                            onLinkClick = { url ->
                                val wikiPattern = """https?://(\w+)\.wikipedia\.org/wiki/(.+)""".toRegex()
                                wikiPattern.find(url)?.let { matchResult ->
                                    val lang = matchResult.groupValues[1]
                                    val title = URLDecoder.decode(matchResult.groupValues[2], "UTF-8")
                                    load(addToHistory = true) { fetchWikipediaByTitle(title, lang) }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (displayedParagraphs < paragraphs.size) {
                            Text(
                                text = "Lire plus",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    displayedParagraphs = paragraphs.size
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Boilerplate and hatnote paragraphs to skip when picking readable content
private val excludeStarts = listOf(
    "Vous lisez un",
    "Cet article est une",
    "Pour les articles",
    "modifier",
    "Cet article ne",
    "Si vous disposez",
    "Pour des articles plus généraux",
    "Pour un article plus général",
    "Cet article est orphelin",
    "Ne pas confondre avec",
    "Ne doit pas être confondu avec",
    "Cet article concerne",
    "N.B."
)

private val whitespaceRegex = "\\s+".toRegex()

data class WikipediaContent(
    val title: String,
    val fullContentHtml: String,
    // Wikipedia language code ("fr", "en", ...), needed to resolve relative /wiki/ links
    val language: String
)

private suspend fun fetchRandomTitle(language: String): String = withContext(Dispatchers.IO) {
    // Use the MediaWiki action API: it returns the title directly, with no HTTP
    // redirect to follow, unlike the deprecated rest_v1 random/summary endpoint.
    val url = "https://$language.wikipedia.org/w/api.php?action=query&format=json&list=random&rnnamespace=0&rnlimit=1"
    val json = JSONObject(httpGet(url))
    json.getJSONObject("query")
        .getJSONArray("random")
        .getJSONObject(0)
        .getString("title")
}

private suspend fun fetchPageViews(language: String, title: String): Int = withContext(Dispatchers.IO) {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    // Pageview data lags a day or two, so query the 30 days ending two days ago.
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    val end = java.time.LocalDate.now().minusDays(2)
    val start = end.minusDays(30)
    val viewsUrl = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/$language.wikipedia/all-access/all-agents/$encodedTitle/daily/${start.format(fmt)}/${end.format(fmt)}"
    try {
        val json = JSONObject(httpGet(viewsUrl))
        val items = json.optJSONArray("items") ?: return@withContext 0
        (0 until items.length()).sumOf { items.getJSONObject(it).optInt("views", 0) }
    } catch (_: Exception) {
        0
    }
}

private suspend fun fetchCompleteWikipedia(language: String): WikipediaContent = withContext(Dispatchers.IO) {
    // Fetch several random titles in parallel, each with its last-30-days pageviews, and keep the
    // ones that resolved. A single flaky fetch drops out instead of failing the whole batch.
    val viewCounts = coroutineScope {
        (1..5).map {
            async {
                runCatching {
                    val title = fetchRandomTitle(language)
                    title to fetchPageViews(language, title)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().toMap()
    }

    // Pick the most viewed article; if every fetch failed, surface the error to the user.
    val bestTitle = viewCounts.maxByOrNull { it.value }?.key
        ?: throw Exception("Aucun article n'a pu être chargé")

    fetchWikipediaByTitle(bestTitle, language)
}

private suspend fun fetchWikipediaByTitle(title: String, language: String): WikipediaContent = withContext(Dispatchers.IO) {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val contentUrl = "https://$language.wikipedia.org/w/api.php?action=parse&format=json&page=$encodedTitle&prop=text&redirects=1"
    val json = JSONObject(httpGet(contentUrl))

    if (json.has("error")) {
        throw Exception("Page not found: ${json.getJSONObject("error").getString("info")}")
    }

    val parseObject = json.getJSONObject("parse")
    WikipediaContent(
        title = parseObject.getString("title"),
        fullContentHtml = parseObject.getJSONObject("text").getString("*"),
        language = language
    )
}

@Composable
private fun HtmlTextWithLinks(html: String, language: String, onLinkClick: (String) -> Unit) {
    val doc = remember(html) { Jsoup.parse(html) }
    val annotatedString = remember(doc, language) {
        buildAnnotatedString {
            doc.body().children().forEach { element ->
                appendElementRecursively(element, this, language, onLinkClick)
                append("\n\n")
            }
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp)
    )
}

private fun appendElementRecursively(
    element: org.jsoup.nodes.Element,
    builder: AnnotatedString.Builder,
    language: String,
    onLinkClick: (String) -> Unit
) {
    // Skip reference links
    if (element.tagName() == "sup" && element.hasClass("reference")) return

    // Skip anything inside an infobox
    if (element.parents().any { it.tagName() == "table" && it.hasClass("infobox") }) return

    when (element.tagName()) {
        "a" -> {
            val url = element.attr("href")
            val text = element.text().replace("""\displaystyle""", "")

            // Skip red links (non-existing pages)
            val isRedLink = element.hasClass("new")

            // Purely numeric link text is a footnote marker, not worth linking
            val isFootnote = text.isNotBlank() && text.all { it.isDigit() }

            if (!isRedLink && text.isNotBlank() && !isFootnote) {
                val absoluteUrl = if (url.startsWith("/wiki/")) {
                    "https://$language.wikipedia.org$url"
                } else url

                val linkAnnotation = LinkAnnotation.Clickable(
                    tag = "wiki_link",
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                    )
                ) {
                    onLinkClick(absoluteUrl)
                }
                builder.withLink(linkAnnotation) { append(text) }
            } else {
                builder.append(text)
            }
        }
        else -> {
            element.childNodes().forEach { node ->
                when (node) {
                    is org.jsoup.nodes.TextNode -> builder.append(node.text())
                    is org.jsoup.nodes.Element -> appendElementRecursively(node, builder, language, onLinkClick)
                }
            }
        }
    }
}