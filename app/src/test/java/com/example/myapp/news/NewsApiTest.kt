package com.example.myapp.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsApiTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
          <title>Un journal</title>
          <item>
            <title><![CDATA[Un titre]]></title>
            <link>https://exemple.fr/article-1?utm_source=rss&amp;id=7#xtor=RSS-3</link>
            <description><![CDATA[<p>Un <b>résumé</b>.</p>]]></description>
            <pubDate>Sat, 22 Aug 2026 14:32:03 +0200</pubDate>
            <media:content url="https://exemple.fr/photo.jpg" width="800"/>
          </item>
          <item>
            <title>Sans lien</title>
          </item>
        </channel></rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>Titre atom</title>
            <link rel="alternate" href="https://exemple.fr/atom-1"/>
            <summary>Résumé atom.</summary>
            <published>2026-08-22T12:00:00+02:00</published>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parses an rss feed and drops what has no link`() {
        val articles = parseFeed(rss, "Un journal", "une")
        assertEquals(1, articles.size)
        val article = articles.first()
        assertEquals("Un titre", article.title)
        assertEquals("https://exemple.fr/article-1?id=7", article.link)
        assertEquals("Un résumé.", article.summary)
        assertEquals("https://exemple.fr/photo.jpg", article.imageUrl)
        assertEquals("Un journal", article.source)
        assertTrue(article.publishedAt > 0)
    }

    @Test
    fun `parses an atom feed`() {
        val articles = parseFeed(atom, "Autre", "monde")
        assertEquals(1, articles.size)
        assertEquals("https://exemple.fr/atom-1", articles.first().link)
        assertEquals("Résumé atom.", articles.first().summary)
        assertTrue(articles.first().publishedAt > 0)
    }

    @Test
    fun `canonical link drops the fragment and the tracking parameters`() {
        assertEquals("https://a.fr/x", canonicalLink("https://a.fr/x#xtor=RSS-3-[titres]"))
        assertEquals("https://a.fr/x", canonicalLink("https://a.fr/x?utm_medium=rss&utm_source=a"))
        assertEquals("https://a.fr/x?p=2", canonicalLink("https://a.fr/x?p=2&xtor=RSS"))
        assertNull(canonicalLink("/relatif"))
    }

    @Test
    fun `extracts the article body and ignores the boilerplate`() {
        val body = "Ceci est un vrai paragraphe qui dépasse largement la longueur minimale exigée."
        val html = """
            <html><head><meta property="og:image" content="https://a.fr/img.jpg"/>
            <meta property="og:title" content="Le vrai titre"/></head>
            <body><nav><p>Un menu qui traîne et qui est bien assez long pour passer</p></nav>
            <article><p>$body</p><p>Lire aussi : un autre article qui n'a rien à faire ici</p>
            <p>court</p><p>$body</p></article></body></html>
        """.trimIndent()
        val content = extractArticle(html, "https://a.fr/x")
        assertEquals(listOf(body), content.paragraphs)
        assertEquals("Le vrai titre", content.title)
        assertEquals("https://a.fr/img.jpg", content.imageUrl)
        assertTrue(content.truncated)
    }

    @Test
    fun `merging keeps one line per story, preferring the one with a picture`() {
        val withoutImage = NewsArticle("https://a.fr/1", "Le même titre !", "", null, "A", "une", 200L)
        val withImage = NewsArticle("https://b.fr/1", "Le meme titre", "", "https://b.fr/i.jpg", "B", "une", 100L)
        val other = NewsArticle("https://c.fr/1", "Autre chose", "", null, "C", "une", 50L)
        val merged = mergeArticles(listOf(withoutImage, withImage, other))
        assertEquals(2, merged.size)
        assertEquals("https://b.fr/1", merged.first().link)
        assertEquals("https://c.fr/1", merged.last().link)
    }
}
