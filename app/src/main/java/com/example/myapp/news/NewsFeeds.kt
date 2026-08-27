package com.example.myapp.news

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One outlet's feed inside a category. [source] is what the article line credits. */
data class NewsFeed(val source: String, val url: String)

/**
 * A tab of the news screen: the enabled outlets merged, newest first. Which outlets those are is the
 * one thing that is settable ([NewsSources]); adding a feed is still a one line edit here.
 */
data class NewsCategory(val id: String, val label: String, val feeds: List<NewsFeed>)

val NEWS_CATEGORIES = listOf(
    NewsCategory(
        "une", "À la une",
        listOf(
            NewsFeed("Le Monde", "https://www.lemonde.fr/rss/une.xml"),
            NewsFeed("franceinfo", "https://www.franceinfo.fr/titres.rss"),
            NewsFeed("Le Figaro", "https://www.lefigaro.fr/rss/figaro_actualites.xml")
        )
    ),
    NewsCategory(
        "france", "France",
        listOf(
            NewsFeed("franceinfo", "https://www.franceinfo.fr/france.rss"),
            NewsFeed("franceinfo", "https://www.franceinfo.fr/politique.rss"),
            NewsFeed("Le Monde", "https://www.lemonde.fr/politique/rss_full.xml")
        )
    ),
    NewsCategory(
        "monde", "Monde",
        listOf(
            NewsFeed("Le Monde", "https://www.lemonde.fr/international/rss_full.xml"),
            NewsFeed("franceinfo", "https://www.franceinfo.fr/monde.rss"),
            NewsFeed("Le Figaro", "https://www.lefigaro.fr/rss/figaro_international.xml")
        )
    ),
    NewsCategory(
        "eco", "Éco",
        listOf(
            NewsFeed("Le Monde", "https://www.lemonde.fr/economie/rss_full.xml"),
            NewsFeed("franceinfo", "https://www.franceinfo.fr/economie.rss"),
            NewsFeed("Le Figaro", "https://www.lefigaro.fr/rss/figaro_flash-eco.xml")
        )
    ),
    NewsCategory(
        "tech", "Tech",
        listOf(
            NewsFeed("Le Monde", "https://www.lemonde.fr/pixels/rss_full.xml"),
            NewsFeed("franceinfo", "https://www.franceinfo.fr/internet.rss"),
            NewsFeed("Le Figaro", "https://www.lefigaro.fr/rss/figaro_secteur_high-tech.xml")
        )
    ),
    NewsCategory(
        "sciences", "Sciences",
        listOf(
            NewsFeed("Le Monde", "https://www.lemonde.fr/sciences/rss_full.xml"),
            NewsFeed("franceinfo", "https://www.franceinfo.fr/sciences.rss"),
            NewsFeed("Le Figaro", "https://www.lefigaro.fr/rss/figaro_sciences.xml")
        )
    )
)

/** Every outlet the feeds above cover, in the order the settings menu lists them. */
val NEWS_SOURCES = listOf("franceinfo", "Le Monde", "Le Figaro")

/** What a fresh install reads: franceinfo alone, the rest is opt-in. */
val DEFAULT_NEWS_SOURCES = setOf("franceinfo")

val Context.newsDataStore by preferencesDataStore("news")

/** The outlets to actually fetch. Never empty: unchecking the last one falls back to the default. */
object NewsSources {
    private val KEY_SOURCES = stringSetPreferencesKey("sources")

    fun enabled(context: Context): Flow<Set<String>> = context.newsDataStore.data.map {
        it[KEY_SOURCES]?.takeIf { set -> set.isNotEmpty() } ?: DEFAULT_NEWS_SOURCES
    }

    suspend fun setEnabled(context: Context, sources: Set<String>) {
        val kept = sources.ifEmpty { DEFAULT_NEWS_SOURCES }
        context.newsDataStore.edit { it[KEY_SOURCES] = kept }
    }
}

/**
 * The outlets whose feed carries articles the extractor cannot read whole (Le Figaro puts part of
 * its output behind a paywall, and nothing in the feed says which). Their articles are fetched in
 * the background after a refresh and dropped from the list when the body comes back cut short.
 */
val PRECHECKED_SOURCES = setOf("Le Figaro")

fun newsCategory(id: String): NewsCategory =
    NEWS_CATEGORIES.firstOrNull { it.id == id } ?: NEWS_CATEGORIES.first()
