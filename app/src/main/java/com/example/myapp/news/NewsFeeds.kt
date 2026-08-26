package com.example.myapp.news

/** One outlet's feed inside a category. [source] is what the article line credits. */
data class NewsFeed(val source: String, val url: String)

/**
 * A tab of the news screen: several outlets merged, newest first. The list is hardcoded on purpose,
 * there is no source picker: adding or dropping an outlet is a one line edit here.
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

/**
 * The outlets whose feed carries articles the extractor cannot read whole (Le Figaro puts part of
 * its output behind a paywall, and nothing in the feed says which). Their articles are fetched in
 * the background after a refresh and dropped from the list when the body comes back cut short.
 */
val PRECHECKED_SOURCES = setOf("Le Figaro")

fun newsCategory(id: String): NewsCategory =
    NEWS_CATEGORIES.firstOrNull { it.id == id } ?: NEWS_CATEGORIES.first()
