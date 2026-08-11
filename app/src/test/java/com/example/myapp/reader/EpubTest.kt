package com.example.myapp.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTest {

    @Test
    fun `resolves chapter paths relative to the manifest`() {
        assertEquals("OEBPS/text/ch1.xhtml", Epub.resolve("OEBPS", "text/ch1.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", Epub.resolve("OEBPS/text", "../ch1.xhtml"))
        assertEquals("ch1.xhtml", Epub.resolve("", "ch1.xhtml"))
        assertEquals("ch1.xhtml", Epub.resolve("OEBPS", "/ch1.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", Epub.resolve("OEBPS", "ch1.xhtml#part2"))
    }

    @Test
    fun `reads the spine in order with its metadata`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Les Misérables</dc:title>
                <dc:creator>Victor Hugo</dc:creator>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()

        val parsed = Epub.parseOpf(opf, "OEBPS")

        assertEquals("Les Misérables", parsed.title)
        assertEquals("Victor Hugo", parsed.author)
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"), parsed.spine)
        assertEquals("OEBPS/images/cover.jpg", parsed.coverHref)
        assertEquals("OEBPS/nav.xhtml", parsed.tocHref)
    }

    @Test
    fun `takes chapter titles from an ncx table of contents`() {
        val ncx = """
            <?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="p1"><navLabel><text>Fantine</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
                <navPoint id="p2"><navLabel><text>Cosette</text></navLabel><content src="text/ch2.xhtml#start"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val titles = Epub.parseToc(ncx, "OEBPS")

        assertEquals("Fantine", titles["OEBPS/text/ch1.xhtml"])
        assertEquals("Cosette", titles["OEBPS/text/ch2.xhtml"])
    }

    @Test
    fun `turns chapter markup into blocks without repeating nested containers`() {
        val html = """
            <html><body>
              <div class="chapter">
                <h1>Chapitre premier</h1>
                <p>En <em>1815</em>, M. Charles était
                   évêque de Digne.</p>
                <blockquote>Un vieillard.</blockquote>
              </div>
            </body></html>
        """.trimIndent()

        val blocks = Epub.parseChapter(html)

        assertEquals(3, blocks.size)
        assertEquals(BlockKind.Heading, blocks[0].kind)
        assertEquals("Chapitre premier", blocks[0].text)
        assertEquals(BlockKind.Paragraph, blocks[1].kind)
        // The markup's line break and indentation collapse into a single space.
        assertEquals("En 1815, M. Charles était évêque de Digne.", blocks[1].text)
        assertEquals(BlockKind.Quote, blocks[2].kind)
    }

    @Test
    fun `keeps italics pointing at the right characters`() {
        val blocks = Epub.parseChapter("<html><body><p>En <em>1815</em>, à Digne.</p></body></html>")

        val block = blocks.single()
        val span = block.spans.single()
        assertEquals("1815", block.text.substring(span.start, span.end))
        assertTrue(span.italic)
    }

    @Test
    fun `finds the word under a long press and nothing between words`() {
        val text = "Un vieillard s'approcha."

        assertEquals("vieillard", wordAt(text, 5))
        assertEquals("s'approcha", wordAt(text, 15))
        assertEquals("Un", wordAt(text, 0))
        assertNull(wordAt(text, 2))
        assertNull(wordAt(text, text.length))
    }
}
