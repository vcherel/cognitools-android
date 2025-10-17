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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

            MyButton(
                text = "Je veux me perdre",
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        fullContent = null
                        displayedParagraphs = 0
                        try {
                            wikiContent = fetchRandomWikipedia()
                        } catch (e: Exception) {
                            error = "Erreur de chargement: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            )

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
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Text(
                            text = content.extract,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 24.sp
                        )

                        // Display additional content if loaded
                        if (fullContent != null && displayedParagraphs > 0) {
                            val paragraphs = fullContent!!
                                .split("\n\n")
                                .filter { it.isNotBlank() }
                                .drop(1) // Skip the first paragraph (extract)
                            val toDisplay = paragraphs.take(displayedParagraphs)

                            // Regex for section titles (== Title ==) and subsection titles (=== Title ===)
                            val sectionTitleRegex = Regex("^=+\\s*(.+?)\\s*=+$", RegexOption.MULTILINE)

                            var previousWasTitle = false

                            toDisplay.forEachIndexed { index, paragraph ->
                                val lines = paragraph.trim().lines()

                                lines.forEach { line ->
                                    val trimmed = line.trim()

                                    if (trimmed.isEmpty()) return@forEach

                                    val match = sectionTitleRegex.matchEntire(trimmed)

                                    if (match != null) {
                                        // Section or subsection title
                                        val title = match.groupValues[1].trim()
                                        val equalsCount = trimmed.takeWhile { it == '=' }.length

                                        Spacer(modifier = Modifier.height(if (equalsCount == 2) 24.dp else 16.dp))
                                        Text(
                                            text = title,
                                            style = if (equalsCount == 2)
                                                MaterialTheme.typography.headlineSmall
                                            else
                                                MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        previousWasTitle = true
                                    } else {
                                        // Normal paragraph
                                        Spacer(modifier = Modifier.height(if (previousWasTitle) 4.dp else 12.dp))
                                        Text(
                                            text = trimmed,
                                            style = MaterialTheme.typography.bodyMedium,
                                            lineHeight = 24.sp
                                        )
                                        previousWasTitle = false
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(16.dp)
                            )
                        } else {
                            Text(
                                text = "Lire plus",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        isLoadingMore = true
                                        try {
                                            if (fullContent == null) {
                                                fullContent =
                                                    fetchFullWikipediaContent(content.title)
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

suspend fun fetchRandomWikipedia(): WikipediaContent = withContext(Dispatchers.IO) {
    val url = URL("https://fr.wikipedia.org/api/rest_v1/page/random/summary")
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

suspend fun fetchFullWikipediaContent(title: String): String = withContext(Dispatchers.IO) {
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    val url = URL("https://fr.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&explaintext=1&titles=$encodedTitle")
    val connection = url.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "WikiApp/1.0")

        val response = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        val pages = json.getJSONObject("query").getJSONObject("pages")
        val pageId = pages.keys().next()
        val page = pages.getJSONObject(pageId)

        page.getString("extract")
    } finally {
        connection.disconnect()
    }
}