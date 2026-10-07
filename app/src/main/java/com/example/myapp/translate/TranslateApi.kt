package com.example.myapp.translate

import com.example.myapp.httpGetRetrying
import org.json.JSONArray
import java.net.URLEncoder

/** The only two languages the tool juggles, which is what makes the automatic flip below well defined. */
enum class TranslateLang(val code: String, val label: String) {
    FR("fr", "Français"),
    EN("en", "Anglais");

    val other: TranslateLang get() = if (this == FR) EN else FR

    companion object {
        fun fromCode(code: String?): TranslateLang? = entries.firstOrNull { it.code == code }
    }
}

/** One dictionary word and the source words it translates back to, "ancien" for "former, old, ancient". */
data class DictionaryTerm(val word: String, val back: List<String>)

/** One part of speech and the words the endpoint offers for it. Only filled for single words. */
data class DictionaryEntry(val partOfSpeech: String, val terms: List<DictionaryTerm>)

/** A monolingual definition of the source word, in its own language, with its example when given. */
data class Definition(val partOfSpeech: String, val text: String, val example: String?)

data class TranslationResult(
    val source: String,
    val translation: String,
    /** What the endpoint detected, null when it is neither of the two languages. */
    val from: TranslateLang?,
    val to: TranslateLang,
    val entries: List<DictionaryEntry>,
    val definitions: List<Definition> = emptyList(),
    val examples: List<String> = emptyList(),
    /** Other ways to translate the whole text, the main one left out. */
    val alternatives: List<String> = emptyList(),
    /** The word the dictionary details describe when it is not [source], "former" for "the former". */
    val detailWord: String? = null
) {
    val hasDetails: Boolean
        get() = entries.isNotEmpty() || definitions.isNotEmpty() || examples.isNotEmpty() || alternatives.isNotEmpty()
}

/**
 * The endpoint the Google Translate web widget itself calls: no key, no account, and it answers with
 * more than the translation (detected language, dictionary entries per part of speech, definitions,
 * examples, alternatives), which is what turns a lookup into something worth putting on a flashcard.
 */
private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

/** Past this the query string gets long enough to be refused, so a paste is sent in pieces. */
private const val MAX_CHUNK = 1200

/** Leading words the dictionary has no entry for together with the next one: "the former" has none, "former" has. */
private val ARTICLES = setOf("the", "a", "an", "to", "le", "la", "les", "un", "une", "des", "du", "de")

/**
 * Translates into [to], flipping the direction once when the text turns out to already be in the
 * target language: asking for French while typing French otherwise hands the text back unchanged.
 * The flip names its source language, since "auto" then guesses again and a French word guessed as
 * English comes back with no dictionary at all.
 */
suspend fun translate(text: String, to: TranslateLang): TranslationResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return TranslationResult(trimmed, "", null, to, emptyList())
    val first = translateWhole(trimmed, to, from = null)
    val result = if (first.from == to) translateWhole(trimmed, to.other, from = to) else first
    if (result.entries.isNotEmpty() || result.definitions.isNotEmpty()) return result
    val word = headWord(trimmed) ?: return result
    val details = fetch(word, result.to, result.from)
    return result.copy(
        entries = details.entries,
        definitions = details.definitions,
        examples = details.examples,
        detailWord = word
    )
}

/** The single word left once leading articles are dropped, null when that is the text itself or not one word. */
internal fun headWord(text: String): String? {
    val words = text.split(Regex("""\s+""")).toMutableList()
    while (words.size > 1 && words.first().lowercase() in ARTICLES) words.removeAt(0)
    var word = words.singleOrNull() ?: return null
    val elided = Regex("""^[ldLD]['’]""")
    word = word.replace(elided, "")
    return word.takeIf { it.isNotBlank() && it != text }
}

private suspend fun translateWhole(text: String, to: TranslateLang, from: TranslateLang?): TranslationResult {
    val parts = splitForTranslation(text).map { fetch(it, to, from) }
    val first = parts.first()
    // The chunks keep their own separators, so they go back together as they were cut.
    val translation = parts.joinToString("") { it.translation }
    // Everything past the translation describes a single word or phrase; a text sent in pieces has none anyway.
    return if (parts.size == 1) first else TranslationResult(text, translation, first.from, to, emptyList())
}

private suspend fun fetch(text: String, to: TranslateLang, from: TranslateLang?): TranslationResult {
    val sl = from?.code ?: "auto"
    val url = "$ENDPOINT?client=gtx&hl=fr&sl=$sl&tl=${to.code}&dt=t&dt=bd&dt=md&dt=ex&dt=at" +
        "&q=${URLEncoder.encode(text, "UTF-8")}"
    return parseTranslation(httpGetRetrying(url, attempts = 4), text, to)
}

/**
 * The answer is a bare array: [0] the translated sentences, [1] the dictionary entries (absent for
 * anything longer than a word), [2] the detected source language, [5] the alternatives per segment,
 * [12] the definitions per part of speech, [13] the example sentences.
 */
internal fun parseTranslation(body: String, source: String, to: TranslateLang): TranslationResult {
    val root = JSONArray(body)

    val sentences = root.optJSONArray(0)
    val translation = buildString {
        for (i in 0 until (sentences?.length() ?: 0)) {
            append(sentences?.optJSONArray(i)?.optString(0).orEmpty())
        }
    }

    val entries = root.arrays(1).mapNotNull { entry ->
        val terms = entry.arrays(2).mapNotNull { term ->
            val word = term.optString(0).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DictionaryTerm(word, term.strings(1))
        }.ifEmpty { entry.strings(1).map { DictionaryTerm(it, emptyList()) } }
        if (terms.isEmpty()) null else DictionaryEntry(entry.optString(0), terms)
    }

    val definitions = root.arrays(12).flatMap { group ->
        val partOfSpeech = group.optString(0)
        group.arrays(1).mapNotNull { def ->
            val text = def.optString(0).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Definition(partOfSpeech, text, def.optString(2).takeIf { it.isNotBlank() && it != "null" })
        }
    }

    val defined = definitions.mapNotNull { it.example }.toSet()
    val examples = root.optJSONArray(13)?.arrays(0).orEmpty()
        .mapNotNull { it.optString(0).replace(Regex("</?b>"), "").takeIf { e -> e.isNotBlank() } }
        .filter { it !in defined }

    // Only a text translated in one segment has alternatives for the whole of it.
    val segments = root.arrays(5)
    val alternatives = if (segments.size == 1) {
        segments[0].arrays(2).map { it.optString(0).trim() }
            .filter { it.isNotBlank() && !it.equals(translation.trim(), ignoreCase = true) }
            .distinctBy { it.lowercase() }
    } else emptyList()

    return TranslationResult(
        source = source,
        translation = translation,
        from = TranslateLang.fromCode(root.optString(2).takeIf { it.isNotBlank() }),
        to = to,
        entries = entries,
        definitions = definitions,
        examples = examples,
        alternatives = alternatives
    )
}

private fun JSONArray.arrays(index: Int): List<JSONArray> {
    val array = optJSONArray(index) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONArray(it) }
}

private fun JSONArray.strings(index: Int): List<String> {
    val array = optJSONArray(index) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
}

/** Cuts long text at a sentence end, or failing that at a space, keeping every character. */
internal fun splitForTranslation(text: String, max: Int = MAX_CHUNK): List<String> {
    if (text.length <= max) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        if (text.length - start <= max) {
            chunks += text.substring(start)
            break
        }
        val window = text.substring(start, start + max)
        val sentenceEnd = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        val cut = when {
            sentenceEnd > max / 2 -> sentenceEnd + 1
            window.lastIndexOf(' ') > max / 2 -> window.lastIndexOf(' ') + 1
            else -> max
        }
        chunks += text.substring(start, start + cut)
        start += cut
    }
    return chunks
}
