package com.example.myapp.news

import android.content.Context
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.matchNormalized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** How long a category's articles are reused before hitting the feeds again. */
private const val FRESH_FOR_MS = 10 * 60 * 1000L

/** Articles kept per category: enough to scroll a while, not enough to make the list state heavy. */
private const val MAX_PER_CATEGORY = 120

/**
 * How far down an article counts as read to the end. Not 1f: the last screenful is the byline, the
 * tags and whatever the outlet pads the page with, and stopping just short of the bottom is normal.
 */
private const val FINISHED_RATIO = 0.95f

/** Below this, the article was opened and not really started; nothing worth resuming. */
private const val STARTED_RATIO = 0.03f

/**
 * The news tool's one source of truth: the merged articles per category, the read state and the
 * saved articles. Lives on [com.example.myapp.MyApplication] so switching tabs (or leaving the tool
 * and coming back) doesn't refetch what was just loaded.
 */
class NewsRepository(context: Context) {
    private val dao = AppDatabase.get(context).newsDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _articles = MutableStateFlow<Map<String, List<NewsArticle>>>(emptyMap())
    val articles: StateFlow<Map<String, List<NewsArticle>>> = _articles.asStateFlow()

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    private val fetchedAt = mutableMapOf<String, Long>()

    val readLinks: StateFlow<Set<String>> = dao.observeReadLinks()
        .map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val saved: Flow<List<NewsSaved>> = dao.observeSaved()

    /** The article to offer picking back up: the last one left unfinished, if any. */
    val resumable: Flow<NewsProgress?> = dao.observeLatestProgress()

    /** Every article loaded so far, whatever the tab: what the search field looks through. */
    fun allLoaded(): List<NewsArticle> =
        _articles.value.values.flatten().distinctBy { it.link }.sortedByDescending { it.publishedAt }

    /**
     * Loads [categoryId]'s feeds, in parallel, and merges them newest first. A feed that fails is
     * skipped: one outlet being down shouldn't empty the tab. Throws only when every feed failed and
     * there is nothing cached to show instead.
     */
    suspend fun refresh(categoryId: String, force: Boolean = false) {
        val fresh = System.currentTimeMillis() - (fetchedAt[categoryId] ?: 0L) < FRESH_FOR_MS
        if (!force && fresh && _articles.value[categoryId]?.isNotEmpty() == true) return
        if (categoryId in _loading.value) return

        _loading.value = _loading.value + categoryId
        try {
            val category = newsCategory(categoryId)
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    category.feeds.map { feed ->
                        async { runCatching { fetchFeed(feed, categoryId) }.getOrDefault(emptyList()) }
                    }.awaitAll()
                }
            }
            val merged = mergeArticles(results.flatten())
            if (merged.isEmpty()) {
                if (_articles.value[categoryId].isNullOrEmpty()) throw IOException("Aucun article reçu")
                return
            }
            _articles.value = _articles.value + (categoryId to merged)
            fetchedAt[categoryId] = System.currentTimeMillis()
        } finally {
            _loading.value = _loading.value - categoryId
        }
    }

    suspend fun markRead(link: String) = dao.markRead(NewsRead(link, System.currentTimeMillis()))

    suspend fun markUnread(link: String) = dao.markUnread(link)

    suspend fun getSaved(link: String): NewsSaved? = dao.getSaved(link)

    suspend fun save(article: NewsArticle, text: String?) = dao.upsertSaved(article.toSaved(text))

    suspend fun unsave(link: String) = dao.deleteSaved(link)

    /** Where [link] was left, or null if it was never started or was read to the end. */
    suspend fun progressRatio(link: String): Float? = dao.getProgress(link)?.ratio

    /**
     * Remembers how far into [article] the reader got. Runs on the repository's own scope so the
     * article screen can record a last position on its way out, once its own scope is gone. Reaching
     * the end (or scrolling back to the top) drops the row instead of storing a useless one.
     */
    fun recordProgress(article: NewsArticle, ratio: Float) {
        scope.launch {
            if (ratio in STARTED_RATIO..FINISHED_RATIO) dao.upsertProgress(article.toProgress(ratio))
            else dao.deleteProgress(article.link)
        }
    }

    /** The article's body, from the network, falling back to the copy kept with a saved article. */
    suspend fun loadArticle(link: String): NewsArticleContent = withContext(Dispatchers.IO) {
        val fetched = runCatching { fetchArticle(link) }.getOrNull()
        if (fetched != null && !fetched.truncated) return@withContext fetched
        val offline = dao.getSaved(link)?.text?.takeIf { it.isNotBlank() }
        when {
            offline != null -> NewsArticleContent(null, fetched?.imageUrl, offline.split("\n\n"))
            fetched != null -> fetched
            else -> throw IOException("Article illisible")
        }
    }
}

/**
 * Same-story duplicates across outlets (the same wire copy under near identical titles) collapse to
 * one line, the first one seen, preferring a version that at least has a picture.
 */
internal fun mergeArticles(articles: List<NewsArticle>): List<NewsArticle> {
    val byKey = LinkedHashMap<String, NewsArticle>()
    articles.sortedByDescending { it.publishedAt }.forEach { article ->
        val key = article.title.matchNormalized().take(60)
        val existing = byKey[key]
        if (existing == null || (existing.imageUrl == null && article.imageUrl != null)) {
            byKey[key] = article
        }
    }
    return byKey.values.distinctBy { it.link }.take(MAX_PER_CATEGORY)
}
