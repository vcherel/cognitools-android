package com.example.myapp.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** One entry of the spine: the file to render and what to call it in the table of contents. */
data class EpubChapter(val href: String, val title: String)

data class EpubMetadata(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    val coverHref: String?
)

enum class BlockKind { Heading, Paragraph, Quote }

/** A bold or italic run inside a block, as character offsets into its text. */
data class InlineSpan(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

data class TextBlock(val kind: BlockKind, val text: String, val spans: List<InlineSpan> = emptyList())

/**
 * An epub is a zip of XHTML files plus an OPF manifest, so it needs no library: java.util.zip opens
 * it and jsoup, already here for scraping, parses both the manifest and the chapters. Only the
 * chapter being read is parsed, since a novel holds a few hundred kilobytes of markup per file and
 * all of them at once would be pointless work on the phone.
 */
object Epub {

    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "div", "section", "td", "pre"
    )
    private val SKIP_TAGS = setOf("script", "style", "svg", "head", "title", "nav")
    private val BOLD_TAGS = setOf("b", "strong")
    private val ITALIC_TAGS = setOf("i", "em", "cite", "dfn")

    fun metadata(file: File): EpubMetadata = ZipFile(file).use { zip ->
        val container = zip.text("META-INF/container.xml") ?: error("Ce fichier n'est pas un epub")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
            .getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() } ?: error("Ce fichier n'est pas un epub")

        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseOpf(zip.text(opfPath) ?: error("Ce fichier n'est pas un epub"), opfDir)
        if (opf.spine.isEmpty()) error("Cet epub ne contient aucun chapitre")

        val titles = opf.tocHref
            ?.let { toc -> zip.text(toc)?.let { parseToc(it, toc.substringBeforeLast('/', "")) } }
            .orEmpty()

        EpubMetadata(
            title = opf.title.ifBlank { file.nameWithoutExtension },
            author = opf.author,
            chapters = opf.spine.mapIndexed { index, href ->
                EpubChapter(href = href, title = titles[href]?.trim().orEmpty().ifBlank { "Chapitre ${index + 1}" })
            },
            coverHref = opf.coverHref
        )
    }

    fun chapter(file: File, href: String): List<TextBlock> = ZipFile(file).use { zip ->
        parseChapter(zip.text(href).orEmpty())
    }

    fun entry(file: File, href: String): ByteArray? = ZipFile(file).use { it.bytes(href) }

    internal data class OpfContent(
        val title: String,
        val author: String,
        val spine: List<String>,
        val coverHref: String?,
        val tocHref: String?
    )

    /** Manifest plus spine: which files make the book, in reading order, with their paths resolved. */
    internal fun parseOpf(xml: String, opfDir: String): OpfContent {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        val hrefById = mutableMapOf<String, String>()
        val typeById = mutableMapOf<String, String>()
        val propertiesById = mutableMapOf<String, String>()
        doc.getElementsByTag("item").forEach { item ->
            val id = item.attr("id")
            if (id.isBlank()) return@forEach
            hrefById[id] = resolve(opfDir, item.attr("href"))
            typeById[id] = item.attr("media-type")
            propertiesById[id] = item.attr("properties")
        }

        val spine = doc.getElementsByTag("itemref").mapNotNull { ref ->
            val id = ref.attr("idref")
            val type = typeById[id].orEmpty()
            // Anything that isn't a document (a stray image, a cover page declared as one) is not
            // a chapter to page through.
            if (type.contains("html") || type.isBlank()) hrefById[id] else null
        }

        val coverId = propertiesById.entries.firstOrNull { it.value.contains("cover-image") }?.key
            ?: doc.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            ?: hrefById.keys.firstOrNull {
                it.contains("cover", ignoreCase = true) && typeById[it].orEmpty().startsWith("image")
            }

        val tocId = propertiesById.entries.firstOrNull { it.value.split(" ").contains("nav") }?.key
            ?: doc.getElementsByTag("spine").firstOrNull()?.attr("toc")?.takeIf { it.isNotBlank() }
            ?: hrefById.keys.firstOrNull { typeById[it].orEmpty().contains("dtbncx") }

        return OpfContent(
            title = doc.firstText("dc:title", "title"),
            author = doc.firstText("dc:creator", "creator"),
            spine = spine,
            coverHref = coverId?.let { hrefById[it] },
            tocHref = tocId?.let { hrefById[it] }
        )
    }

    /** Chapter titles, from an EPUB 3 nav document or an EPUB 2 ncx, keyed by the file they point at. */
    internal fun parseToc(xml: String, tocDir: String): Map<String, String> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val titles = LinkedHashMap<String, String>()

        fun put(rawHref: String, label: String) {
            if (rawHref.isBlank() || label.isBlank()) return
            val href = resolve(tocDir, rawHref.substringBefore('#'))
            if (href !in titles) titles[href] = label
        }

        doc.getElementsByTag("navPoint").forEach { point ->
            put(
                point.getElementsByTag("content").firstOrNull()?.attr("src").orEmpty(),
                point.getElementsByTag("text").firstOrNull()?.text().orEmpty()
            )
        }
        doc.getElementsByTag("a").forEach { put(it.attr("href"), it.text()) }
        return titles
    }

    /**
     * The chapter's markup turned into the blocks to lay out. An element holding other blocks is
     * walked into rather than emitted, so a chapter wrapped in nested divs doesn't come out doubled.
     */
    internal fun parseChapter(html: String): List<TextBlock> {
        val body = Jsoup.parse(html).body()
        val blocks = mutableListOf<TextBlock>()
        body.children().forEach { collect(it, blocks) }
        if (blocks.isEmpty()) {
            // A chapter with no markup at all still has its text.
            body.text().split("\n").mapNotNull { line ->
                line.trim().takeIf { it.isNotEmpty() }?.let { TextBlock(BlockKind.Paragraph, it) }
            }.forEach { blocks += it }
        }
        return blocks
    }

    private fun collect(element: Element, out: MutableList<TextBlock>) {
        val tag = element.tagName().lowercase()
        if (tag in SKIP_TAGS) return
        if (element.children().any { it.tagName().lowercase() in BLOCK_TAGS }) {
            element.children().forEach { collect(it, out) }
            return
        }
        leafBlock(element, tag)?.let { out += it }
    }

    private fun leafBlock(element: Element, tag: String): TextBlock? {
        val text = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        appendNode(element, text, spans, bold = false, italic = false)

        // Only trailing whitespace is dropped, so the spans keep pointing at the right characters.
        while (text.isNotEmpty() && text.last() == ' ') text.deleteCharAt(text.length - 1)
        val content = text.toString()
        if (content.isBlank()) return null

        val kind = when {
            tag.length == 2 && tag[0] == 'h' && tag[1].isDigit() -> BlockKind.Heading
            tag == "blockquote" -> BlockKind.Quote
            else -> BlockKind.Paragraph
        }
        return TextBlock(
            kind = kind,
            text = content,
            spans = spans.filter { it.start < content.length }.map { it.copy(end = it.end.coerceAtMost(content.length)) }
        )
    }

    private fun appendNode(node: Node, out: StringBuilder, spans: MutableList<InlineSpan>, bold: Boolean, italic: Boolean) {
        when (node) {
            is TextNode -> appendText(out, node.wholeText)
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag in SKIP_TAGS) return
                if (tag == "br") {
                    appendText(out, " ")
                    return
                }
                val nowBold = bold || tag in BOLD_TAGS
                val nowItalic = italic || tag in ITALIC_TAGS
                val start = out.length
                node.childNodes().forEach { appendNode(it, out, spans, nowBold, nowItalic) }
                if ((nowBold != bold || nowItalic != italic) && out.length > start) {
                    spans += InlineSpan(start, out.length, nowBold, nowItalic)
                }
            }
        }
    }

    /** Collapses the markup's line breaks and indentation into single spaces as it writes. */
    private fun appendText(out: StringBuilder, raw: String) {
        raw.forEach { c ->
            if (c.isWhitespace()) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            } else {
                out.append(c)
            }
        }
    }

    /** Joins a path relative to the file that referenced it, resolving the `..` segments. */
    internal fun resolve(dir: String, href: String): String {
        val raw = href.substringBefore('#')
        if (raw.startsWith("/")) return raw.removePrefix("/")
        val parts = mutableListOf<String>()
        (dir.split("/") + raw.split("/")).forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> parts.removeLastOrNull()
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun Element.firstText(vararg tags: String): String =
        tags.firstNotNullOfOrNull { tag -> getElementsByTag(tag).firstOrNull()?.text()?.takeIf { it.isNotBlank() } }
            .orEmpty()

    private fun ZipFile.text(name: String): String? = bytes(name)?.toString(Charsets.UTF_8)

    /** Entry names can be percent encoded on either side, so the lookup tries both spellings. */
    private fun ZipFile.bytes(name: String): ByteArray? {
        val entry = getEntry(name)
            ?: getEntry(name.decodePath())
            ?: entries().asSequence().firstOrNull { it.name.decodePath() == name.decodePath() }
        return entry?.let { getInputStream(it).use { stream -> stream.readBytes() } }
    }

    private fun String.decodePath(): String =
        runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
}
