package com.example.myapp.news

import com.example.myapp.userMessage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.newsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One article, read in the app: the body pulled out of the page, the picture, and the actions on it
 * (save, open the real page). Opening it marks it read; when the extraction comes back too thin (a
 * paywall), the browser is offered instead of pretending the article is there. The bar under the
 * header says how much is left, and where the article was left is remembered, so it reopens where
 * it was put down.
 */
@Composable
fun NewsArticleScreen(link: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.newsRepository
    val scope = rememberCoroutineScope()

    val saved by repo.saved.collectAsState(initial = emptyList())
    val savedRow = saved.firstOrNull { it.link == link }
    val article = remember(link, savedRow == null) {
        repo.allLoaded().firstOrNull { it.link == link }
            ?: savedRow?.toArticle()
            ?: NewsArticle(link, link, "", null, "", NEWS_CATEGORIES.first().id, 0L)
    }

    var content by remember { mutableStateOf<NewsArticleContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    var restored by remember(link) { mutableStateOf(false) }
    // An article that fits on one screen has nothing left to read, so it reads as done rather than
    // as untouched; while it is still loading there is simply nothing to measure yet.
    val readRatio by remember(loading) {
        derivedStateOf {
            val max = scrollState.maxValue
            when {
                max > 0 -> (scrollState.value.toFloat() / max).coerceIn(0f, 1f)
                loading -> 0f
                else -> 1f
            }
        }
    }

    LaunchedEffect(link) {
        repo.markRead(link)
        loading = true
        runCatching { repo.loadArticle(link) }
            .onSuccess { content = it }
            .onFailure { error = userMessage(it) }
        loading = false
    }

    // Jump back to where the article was left, but only once the paragraphs and the picture have
    // finished laying out: until maxValue stops growing, the same fraction lands somewhere else.
    LaunchedEffect(link, content) {
        if (restored || content == null) return@LaunchedEffect
        val target = repo.progressRatio(link)
        if (target != null) {
            var settled = -1
            while (scrollState.maxValue != settled) {
                settled = scrollState.maxValue
                delay(120)
            }
            if (settled > 0) scrollState.scrollTo((target * settled).toInt())
        }
        restored = true
    }

    // Recorded every 5 %, not every scrolled pixel; the exact position is caught on the way out.
    // Waiting for the restore matters: otherwise the jump's own starting point is written first.
    LaunchedEffect(link, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { (readRatio * 20).toInt() }
            .distinctUntilChanged()
            .collect { repo.recordProgress(article, it / 20f) }
    }

    val ratioOnLeave by rememberUpdatedState(readRatio)
    DisposableEffect(link) {
        onDispose { if (restored) repo.recordProgress(article, ratioOnLeave) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = article.source.ifBlank { "Article" },
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleWeight = true
        ) {
            IconButton(onClick = {
                scope.launch {
                    if (savedRow != null) {
                        repo.unsave(link)
                        AppSnackbar.show("Retiré des sauvegardés")
                    } else {
                        repo.save(article, content?.text)
                        AppSnackbar.show("Article sauvegardé")
                    }
                }
            }) {
                Icon(
                    if (savedRow != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (savedRow != null) "Retirer des sauvegardés" else "Sauvegarder",
                    tint = if (savedRow != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { openInBrowser(context, link) }) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = "Ouvrir dans le navigateur")
            }
        }

        LinearProgressIndicator(
            progress = { readRatio },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(3.dp)
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            (article.imageUrl ?: content?.imageUrl)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.height(12.dp))
            }
            // The title, the byline and the body are selectable as one block, so a quotation can be
            // dragged across the paragraph breaks in one go.
            SelectionContainer {
                Column {
                    Text(article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOf(article.source, newsFullDate(article.publishedAt)).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    content?.paragraphs?.forEach { paragraph ->
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error?.let {
                ErrorText(message = "Erreur: $it", onDismiss = { error = null }, modifier = Modifier.padding(vertical = 8.dp))
            }

            if (!loading && (content == null || content?.truncated == true)) {
                if (article.summary.isNotBlank() && content?.paragraphs.isNullOrEmpty()) {
                    SelectionContainer { Text(article.summary, style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Article incomplet (accès réservé ou page illisible).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(10.dp))
                        MyButton(
                            text = "Ouvrir dans le navigateur",
                            onClick = { openInBrowser(context, link) },
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 18.sp,
                            height = 52.dp
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
