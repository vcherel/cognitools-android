package com.example.myapp.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import java.text.Normalizer

// A note is plain text. Everything the app reads into it (checkboxes, separators, inline markers,
// quantity and waiting-date suffixes) is a convention on a line's characters, parsed here.

// The note titles the app gives their own behaviour to.
const val INGREDIENTS_TITLE = "Ingrédients"
const val INGREDIENT_MODEL_TITLE = "Modèle ingrédients"
const val COURSES_TITLE = "Courses"
const val COURSES_MODEL_TITLE = "Modèle courses"
const val TODO_LIST_TITLE = "Todo list"
const val CLAUDE_NOTE_TITLE = "Claude"

// The marker line right after a category title in the Claude note, flagging that
// category as having an unfinished Claude session to resume.
const val RESUME_LINE = "Resume"

/** True for a line wrapped in the bold+underline markers the /titre slash command inserts. */
fun String.isTitleLine(): Boolean = startsWith("**__") && endsWith("__**") && length > 8

// A line starting with one of these prefixes renders as a checkbox.
const val UNCHECKED_PREFIX = "[ ] "
const val CHECKED_PREFIX = "[x] "

// Both prefixes are the same length, which is what lets checkboxText cut a fixed number of
// characters whichever one the line carries.
private val prefixLength = UNCHECKED_PREFIX.length.also { check(it == CHECKED_PREFIX.length) }

fun String.isCheckboxLine(): Boolean =
    startsWith(UNCHECKED_PREFIX) || startsWith(CHECKED_PREFIX)

fun String.isCheckedLine(): Boolean = startsWith(CHECKED_PREFIX)

/** The line without its checkbox prefix, or the line itself for plain lines. */
fun String.checkboxText(): String =
    if (isCheckboxLine()) substring(prefixLength) else this

/** The checkbox prefix a line carries, empty for a plain line. */
fun String.checkboxPrefix(): String = when {
    isCheckedLine() -> CHECKED_PREFIX
    isCheckboxLine() -> UNCHECKED_PREFIX
    else -> ""
}

// Inline markers within a line: **gras**, *italique*, ***les deux***,
// __souligné__. The markers stay in the stored text and are hidden when
// rendering. Content inside a marker pair is parsed again, so markers combine.
fun String.formatInline(): AnnotatedString {
    val s = this
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '*' || c == '_') {
                var run = 1
                val maxRun = if (c == '*') 3 else 2
                while (run < maxRun && i + run < s.length && s[i + run] == c) run++
                if (c == '*' || run == 2) {
                    val marker = c.toString().repeat(run)
                    val close = s.indexOf(marker, i + run)
                    if (close > i + run) {
                        val style = when {
                            c == '_' -> SpanStyle(textDecoration = TextDecoration.Underline)
                            run == 3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                            run == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                            else -> SpanStyle(fontStyle = FontStyle.Italic)
                        }
                        withStyle(style) { append(s.substring(i + run, close).formatInline()) }
                        i = close + run
                        continue
                    }
                }
            }
            append(s[i])
            i++
        }
    }
}

/**
 * Display title and preview line. The title field wins when set; otherwise
 * the first non empty content line is the title, as before the field existed.
 */
fun noteTitleAndPreview(note: Note): Pair<String, String> {
    val lines = note.content.lineSequence()
        .filterNot { it.isSeparatorLine() }
        .map { it.checkboxText().formatInline().text.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .toList()
    return if (note.title.isNotBlank()) {
        note.title to lines.getOrElse(0) { "" }
    } else {
        lines.getOrElse(0) { "Note vide" } to lines.getOrElse(1) { "" }
    }
}

// ---- Searching notes ----

/**
 * Lowercased and stripped of accents, so "creme" finds "crème". Deliberately one output character
 * per input character (a decomposed letter keeps only its base): every index into the result is also
 * a valid index into the original, which is what lets [searchMatchRanges] highlight what it found.
 */
fun normalizeForSearch(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
        out.append(base.lowercaseChar())
    }
    return out.toString()
}

/**
 * The query split into the words a note has to contain. Order and position don't matter: "poulet
 * riz" finds a note holding both words anywhere, which is the useful reading for a shopping list.
 */
fun searchTermsOf(query: String): List<String> =
    normalizeForSearch(query).split(' ', '\n', '\t').filter { it.isNotBlank() }

/** True when every term of [terms] appears somewhere in the note's title or body. */
fun noteMatchesSearch(note: Note, terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val haystack = normalizeForSearch(note.title) + "\n" + normalizeForSearch(note.content)
    return terms.all { haystack.contains(it) }
}

/**
 * The note's own line that best explains why it matched: the one holding the most search terms.
 * Null when nothing in the body matched (the match was in the title alone), leaving the caller to
 * show its usual preview.
 */
fun matchingLineOf(note: Note, terms: List<String>): String? {
    if (terms.isEmpty()) return null
    return note.content.lineSequence()
        .filterNot { it.isSeparatorLine() }
        .map { it.checkboxText().formatInline().text.trim() }
        .filter { it.isNotEmpty() }
        .map { line -> line to terms.count { normalizeForSearch(line).contains(it) } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

/** Where each term of [terms] sits in [text], for highlighting. Ranges may overlap. */
fun searchMatchRanges(text: String, terms: List<String>): List<IntRange> {
    val normalized = normalizeForSearch(text)
    return terms.flatMap { term ->
        val ranges = mutableListOf<IntRange>()
        var from = normalized.indexOf(term)
        while (from >= 0) {
            ranges += from until (from + term.length)
            from = normalized.indexOf(term, from + term.length)
        }
        ranges
    }
}

// Note cards come in eight colors and two themes, so the highlight carries its own text color
// rather than inheriting one that might land on top of it.
private val SEARCH_HIGHLIGHT_STYLE = SpanStyle(
    background = Color(0xFFFFD54F),
    color = Color(0xFF1F1F1F),
    fontWeight = FontWeight.Bold
)

/** [text] with every hit of [terms] painted, for the search result cards. */
fun highlightedSearchText(text: String, terms: List<String>): AnnotatedString {
    val ranges = searchMatchRanges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { range -> addStyle(SEARCH_HIGHLIGHT_STYLE, range.first, range.last + 1) }
    }
}

// A line with this prefix renders as a horizontal separator named by the rest
// of the line; a bare "---" is an unnamed separator.
const val SEPARATOR_PREFIX = "--- "

fun String.isSeparatorLine(): Boolean = startsWith(SEPARATOR_PREFIX) || this == "---"

/** The separator name, empty for an unnamed separator. */
fun String.separatorName(): String =
    if (startsWith(SEPARATOR_PREFIX)) substring(SEPARATOR_PREFIX.length).trim() else ""

/** True if any line of the content is a checkbox line. */
fun String.hasCheckboxLine(): Boolean = lineSequence().any { it.isCheckboxLine() }

/** True if any line of the content is a checked checkbox line. */
fun String.hasCheckedLine(): Boolean = lineSequence().any { it.isCheckedLine() }

/** Content with every checked checkbox line removed. */
fun removeCheckedCheckboxes(content: String): String =
    content.lineSequence().filterNot { it.isCheckedLine() }.joinToString("\n")

// A checkbox line may end with "(N)" to mean the item counts N times, e.g. "Yaourts (4)".
private val QUANTITY_SUFFIX = Regex("""\((\d+)\)\s*$""")

/** The quantity from a trailing "(N)" suffix, or 1 if there is none. */
fun String.itemQuantity(): Int = QUANTITY_SUFFIX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 1

/** The text with its trailing "(N)" quantity suffix removed. */
fun String.withoutQuantitySuffix(): String = QUANTITY_SUFFIX.replace(this, "").trimEnd()

/** The text with its quantity changed by delta, floored at 1. The "(1)" suffix is dropped. */
fun String.withQuantityDelta(delta: Int): String {
    val newQuantity = (itemQuantity() + delta).coerceAtLeast(1)
    val base = withoutQuantitySuffix()
    return if (newQuantity <= 1) base else "$base ($newQuantity)"
}

// A checkbox line may end with a non-numeric "(...)" suffix to mean it's on hold until
// that day or date arrives, e.g. "Renouveler passeport (12/09)" or "Appeler Paul (lundi)".
// Requiring the content to not be all digits keeps this distinct from the "(N)" quantity suffix.
private val DATE_SUFFIX = Regex("""\(([^)]+)\)\s*$""")

/** True if the line ends with a non-numeric "(...)" suffix, e.g. a waiting day/date. */
fun String.hasDateSuffix(): Boolean =
    DATE_SUFFIX.find(this)?.groupValues?.get(1)?.let { it.isNotEmpty() && !it.all(Char::isDigit) } ?: false

/** The text with its trailing non-numeric "(...)" suffix removed. */
fun String.withoutDateSuffix(): String = if (hasDateSuffix()) DATE_SUFFIX.replace(this, "").trimEnd() else this

/** Sum of quantities of unchecked checkbox lines, honoring the "(N)" suffix. */
fun String.uncheckedItemCount(): Int = lineSequence()
    .filter { it.isCheckboxLine() && !it.isCheckedLine() }
    .sumOf { it.checkboxText().itemQuantity() }

/** Content with every checkbox line set to the given state. */
fun setAllCheckboxes(content: String, checked: Boolean): String {
    val prefix = if (checked) CHECKED_PREFIX else UNCHECKED_PREFIX
    return content.lineSequence().joinToString("\n") { line ->
        if (line.isCheckboxLine()) prefix + line.checkboxText() else line
    }
}

/** Content with blank lines at the very top and bottom removed. */
fun String.trimBlankEdgeLines(): String {
    val lines = split("\n")
    val start = lines.indexOfFirst { it.isNotBlank() }
    if (start == -1) return ""
    val end = lines.indexOfLast { it.isNotBlank() }
    return lines.subList(start, end + 1).joinToString("\n")
}
