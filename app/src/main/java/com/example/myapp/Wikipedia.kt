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
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Composable
fun WikipediaScreen(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var wikiContent by remember { mutableStateOf<WikipediaContent?>(null) }
    var fullContent by remember { mutableStateOf<String?>(null) }
    var displayedParagraphs by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedLanguage by remember { mutableStateOf("fr") }

    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    "Wikipedia",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 8.dp)
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
                        scope.launch {
                            isLoading = true
                            error = null
                            fullContent = null
                            displayedParagraphs = 0
                            try {
                                wikiContent = fetchRandomWikipedia(selectedLanguage)
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

                // Smaller language selection button
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

                        // Summary (extract)
                        Text(
                            text = content.extract,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 24.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Full content with links
                        fullContent?.let { contentHtml ->
                            val doc = remember(contentHtml) { Jsoup.parse(contentHtml) }
                            val paragraphs = doc.body().select("p")
                            val paragraphsToShow = paragraphs.take(displayedParagraphs)

                            HtmlTextWithLinks(
                                html = paragraphsToShow.joinToString("") { it.outerHtml() }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Lire plus button
                        if (!isLoadingMore) {
                            Text(
                                text = "Lire plus",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        isLoadingMore = true
                                        try {
                                            if (fullContent == null) {
                                                fullContent = fetchFullWikipediaContentHtml(content.title, selectedLanguage)
                                            }
                                            displayedParagraphs += 1
                                        } catch (e: Exception) {
                                            error = "Erreur de chargement: ${e.message}"
                                        } finally {
                                            isLoadingMore = false
                                        }
                                    }
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

data class WikipediaContent(
    val title: String,
    val extract: String,
    val url: String
)

suspend fun fetchRandomWikipedia(language: String = "fr"): WikipediaContent = withContext(Dispatchers.IO) {
    val url = URL("https://$language.wikipedia.org/api/rest_v1/page/random/summary")
    val connection = url.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "WikiApp/1.0")

        val response = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)

        WikipediaContent(
            title = json.getString("title"),
            extract = json.getString("extract"),
            url = JSONObject(json.getString("content_urls"))
                .getJSONObject("desktop")
                .getString("page")
        )
    } finally {
        connection.disconnect()
    }
}

suspend fun fetchFullWikipediaContentHtml(title: String, language: String = "fr"): String = withContext(Dispatchers.IO) {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val url = URL("https://$language.wikipedia.org/w/api.php?action=parse&format=json&page=$encodedTitle&prop=text")
    val connection = url.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "WikiApp/1.0")

        val response = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        json.getJSONObject("parse").getJSONObject("text").getString("*")
    } finally {
        connection.disconnect()
    }
}

@Composable
fun HtmlTextWithLinks(html: String) {
    val doc = remember(html) { Jsoup.parse(html) }
    val annotatedString = buildAnnotatedString {
        doc.body().children().forEach { element ->
            appendElementRecursively(element, this)
            append("\n\n")
        }
    }

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
        onClick = { offset ->
            annotatedString.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.let { annotation ->
                    val url = annotation.item
                    // Open in browser
                    println("Clicked: $url")
                }
        }
    )
}

fun appendElementRecursively(element: org.jsoup.nodes.Element, builder: AnnotatedString.Builder) {
    when (element.tagName()) {
        "a" -> {
            val url = element.attr("href")
            val text = element.text()
            builder.pushStringAnnotation(tag = "URL", annotation = url)
            builder.withStyle(style = SpanStyle(
                fontWeight = FontWeight.Bold
            )
            ) {
                append(text)
            }
            builder.pop()
        }
        else -> {
            element.childNodes().forEach { node ->
                when (node) {
                    is org.jsoup.nodes.TextNode -> builder.append(node.text())
                    is org.jsoup.nodes.Element -> appendElementRecursively(node, builder)
                }
            }
        }
    }
}