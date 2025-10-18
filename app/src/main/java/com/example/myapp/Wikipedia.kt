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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun WikipediaScreen(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var wikiContent by remember { mutableStateOf<WikipediaContent?>(null) }
    var displayedParagraphs by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedLanguage by remember { mutableStateOf("fr") }

    // Navigation history stack
    var navigationHistory by remember { mutableStateOf<List<WikipediaContent>>(emptyList()) }

    val scope = rememberCoroutineScope()

    val loadArticle: (String, String, Boolean) -> Unit = { title, language, addToHistory ->
        scope.launch {
            isLoading = true
            error = null
            displayedParagraphs = 1
            try {
                val newContent = fetchWikipediaByTitle(title, language)

                // Add current content to history before loading new one
                if (addToHistory && wikiContent != null) {
                    navigationHistory = navigationHistory + wikiContent!!
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
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = goBackInHistory) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    "Wikipedia",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )

                // Show history depth indicator
                if (navigationHistory.isNotEmpty()) {
                    Text(
                        text = " (${navigationHistory.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        scope.launch {
                            isLoading = true
                            error = null
                            displayedParagraphs = 1
                            try {
                                val newContent = fetchCompleteWikipedia(selectedLanguage)

                                // Clear history before loading random article
                                navigationHistory = emptyList()

                                wikiContent = newContent
                            } catch (e: Exception) {
                                error = "Erreur de chargement: ${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
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
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
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
                        // Title
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // Content with links
                        val doc = remember(content.fullContentHtml) {
                            Jsoup.parse(content.fullContentHtml)
                        }
                        val paragraphs = remember(doc) {
                            doc.body()
                                .select("p")
                                .filter {
                                    val text = it.text().trim()
                                    text.isNotBlank() &&
                                            !text.startsWith("Vous lisez un", ignoreCase = true) && // Article de qualité
                                            !text.startsWith("Cet article est une", ignoreCase = true) && // Ebauche
                                            !text.startsWith("Pour les articles", ignoreCase = true) && // Homonymes
                                            !text.startsWith("modifier", ignoreCase = true) && // Modifier code
                                            !text.startsWith("Cet article ne", ignoreCase = true) && // Problème source
                                    !text.startsWith("Si vous disposez", ignoreCase = true) // Ouvrage de référence
                                }
                        }
                        val paragraphsToShow = paragraphs.take(displayedParagraphs)

                        HtmlTextWithLinks(
                            html = paragraphsToShow.joinToString("") { it.outerHtml() },
                            onLinkClick = { url ->
                                // Extract title from Wikipedia URL
                                val wikiPattern = """https?://(\w+)\.wikipedia\.org/wiki/(.+)""".toRegex()
                                wikiPattern.find(url)?.let { matchResult ->
                                    val lang = matchResult.groupValues[1]
                                    val title = URLDecoder.decode(matchResult.groupValues[2], "UTF-8")
                                    loadArticle(title, lang, true) // true = add to history
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Lire plus button - only show if there are more paragraphs
                        if (displayedParagraphs < paragraphs.size) {
                            if (!isLoadingMore) {
                                Text(
                                    text = "Lire plus",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        isLoadingMore = true
                                        displayedParagraphs += 2 // Load 2 paragraphs at a time
                                        isLoadingMore = false
                                    }
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class WikipediaContent(
    val title: String,
    val fullContentHtml: String,
    val url: String
)

suspend fun fetchCompleteWikipedia(language: String = "fr"): WikipediaContent = withContext(Dispatchers.IO) {
    // First, get a random article
    val randomUrl = URL("https://$language.wikipedia.org/api/rest_v1/page/random/summary")
    val randomConnection = randomUrl.openConnection() as HttpURLConnection

    val title = try {
        randomConnection.requestMethod = "GET"
        randomConnection.setRequestProperty("User-Agent", "WikiApp/1.0")

        val response = randomConnection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        json.getString("title")
    } finally {
        randomConnection.disconnect()
    }

    // Then fetch the full content with HTML
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val contentUrl = URL("https://$language.wikipedia.org/w/api.php?action=parse&format=json&page=$encodedTitle&prop=text")
    val contentConnection = contentUrl.openConnection() as HttpURLConnection

    try {
        contentConnection.requestMethod = "GET"
        contentConnection.setRequestProperty("User-Agent", "WikiApp/1.0")

        val response = contentConnection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        val htmlContent = json.getJSONObject("parse").getJSONObject("text").getString("*")

        WikipediaContent(
            title = title,
            fullContentHtml = htmlContent,
            url = "https://$language.wikipedia.org/wiki/${encodedTitle}"
        )
    } finally {
        contentConnection.disconnect()
    }
}

suspend fun fetchWikipediaByTitle(title: String, language: String = "fr"): WikipediaContent = withContext(Dispatchers.IO) {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val contentUrl = URL("https://$language.wikipedia.org/w/api.php?action=parse&format=json&page=$encodedTitle&prop=text")
    val contentConnection = contentUrl.openConnection() as HttpURLConnection

    try {
        contentConnection.requestMethod = "GET"
        contentConnection.setRequestProperty("User-Agent", "WikiApp/1.0")

        val response = contentConnection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        val htmlContent = json.getJSONObject("parse").getJSONObject("text").getString("*")

        WikipediaContent(
            title = title,
            fullContentHtml = htmlContent,
            url = "https://$language.wikipedia.org/wiki/${encodedTitle}"
        )
    } finally {
        contentConnection.disconnect()
    }
}

@Composable
fun HtmlTextWithLinks(html: String, onLinkClick: (String) -> Unit) {
    val doc = remember(html) { Jsoup.parse(html) }
    val annotatedString = buildAnnotatedString {
        doc.body().children().forEach { element ->
            appendElementRecursively(element, this, onLinkClick)
            append("\n\n")
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp)
    )
}

fun appendElementRecursively(
    element: org.jsoup.nodes.Element,
    builder: AnnotatedString.Builder,
    onLinkClick: (String) -> Unit
) {
    // Skip reference links
    if (element.tagName() == "sup" && element.hasClass("reference")) return

    when (element.tagName()) {
        "a" -> {
            val url = element.attr("href")
            val text = element.text()
            if (text.isNotBlank() && !text.matches(Regex("""^\d+$"""))) { // Skip purely numeric links
                val absoluteUrl = if (url.startsWith("/wiki/")) {
                    "https://fr.wikipedia.org$url"
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
                builder.withLink(linkAnnotation) {
                    append(text)
                }
            } else {
                // If it's numeric or blank, just append text without link
                builder.append(text)
            }
        }
        else -> {
            element.childNodes().forEach { node ->
                when (node) {
                    is org.jsoup.nodes.TextNode -> builder.append(node.text())
                    is org.jsoup.nodes.Element -> appendElementRecursively(node, builder, onLinkClick)
                }
            }
        }
    }
}
