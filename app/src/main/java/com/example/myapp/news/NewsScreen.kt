package com.example.myapp.news

import com.example.myapp.userMessage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.RecentSearchChips
import com.example.myapp.ScreenTopBar
import com.example.myapp.SearchHistory
import com.example.myapp.SearchSurface
import com.example.myapp.deaccented
import com.example.myapp.newsRepository
import kotlinx.coroutines.launch

/**
 * The news tool: one tab per category, each merging several outlets' RSS feeds. Tapping an article
 * opens it read in the app; the search field looks through everything already loaded, whatever tab
 * it came from. The last article left unfinished sits on top of the list, one tap from where it was
 * put down.
 */
@Composable
fun NewsScreen(onBack: () -> Unit, onOpenArticle: (String) -> Unit, onOpenSaved: () -> Unit) {
    val context = LocalContext.current
    val repo = context.newsRepository
    val scope = rememberCoroutineScope()

    var tabIndex by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val category = NEWS_CATEGORIES[tabIndex]
    val articlesByCategory by repo.articles.collectAsState()
    val loading by repo.loading.collectAsState()
    val readLinks by repo.readLinks.collectAsState()
    val saved by repo.saved.collectAsState(initial = emptyList())
    val savedLinks = remember(saved) { saved.map { it.link }.toSet() }
    val resumable by repo.resumable.collectAsState(initial = null)
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    fun load(force: Boolean) {
        scope.launch {
            error = null
            runCatching { repo.refresh(category.id, force) }.onFailure { error = userMessage(it) }
        }
    }

    LaunchedEffect(category.id) { load(force = false) }
    LaunchedEffect(searching) { if (searching) focusRequester.requestFocus() }

    // Leaving the search closes it first, so the back arrow doesn't drop the whole tool.
    BackHandler(enabled = searching) { searching = false; query = "" }

    val shown = if (searching && query.isNotBlank()) {
        val needle = query.deaccented()
        repo.allLoaded().filter {
            it.title.deaccented().contains(needle) || it.summary.deaccented().contains(needle)
        }
    } else {
        articlesByCategory[category.id].orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Actus",
            onBack = { if (searching) { searching = false; query = "" } else onBack() },
            titleStyle = MaterialTheme.typography.titleLarge,
            titleWeight = true
        ) {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(
                    if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searching) "Fermer la recherche" else "Rechercher"
                )
            }
            IconButton(onClick = onOpenSaved) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Articles sauvegardés")
            }
            IconButton(onClick = { load(force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rafraîchir")
            }
        }

        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Rechercher dans les articles") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(focusRequester)
            )
            if (query.isBlank()) {
                RecentSearchChips(
                    surface = SearchSurface.NEWS,
                    onPick = { query = it },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        } else {
            PrimaryScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 8.dp) {
                NEWS_CATEGORIES.forEachIndexed { index, cat ->
                    Tab(
                        selected = index == tabIndex,
                        onClick = {
                            tabIndex = index
                            scope.launch { listState.scrollToItem(0) }
                        },
                        text = { Text(cat.label) }
                    )
                }
            }
        }

        if (category.id in loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        error?.let {
            ErrorText(
                message = "Erreur: $it",
                onDismiss = { error = null },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (searching && query.isNotBlank()) "Aucun article trouvé." else "Aucun article.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                // Not while searching: the results are the answer to the query, nothing else.
                resumable?.takeIf { !searching }?.let { progress ->
                    item(key = "resume") {
                        NewsResumeCard(progress = progress, onClick = { onOpenArticle(progress.link) })
                    }
                }
                items(shown, key = { it.link }) { article ->
                    NewsArticleRow(
                        article = article,
                        isRead = article.link in readLinks,
                        isSaved = article.link in savedLinks,
                        onClick = {
                            if (searching && query.isNotBlank()) {
                                SearchHistory.record(context, SearchSurface.NEWS, query)
                            }
                            onOpenArticle(article.link)
                        },
                        onLongClick = {
                            scope.launch {
                                if (article.link in readLinks) {
                                    repo.markUnread(article.link)
                                    AppSnackbar.show("Marqué comme non lu")
                                } else {
                                    repo.markRead(article.link)
                                    AppSnackbar.show("Marqué comme lu")
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
