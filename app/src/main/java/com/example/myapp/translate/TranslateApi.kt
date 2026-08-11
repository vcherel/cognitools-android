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

/** One part of speech and the words the endpoint offers for it. Only filled for single words. */
data class DictionaryEntry(val partOfSpeech: String, val terms: List<String>)

data class TranslationResult(
    val source: String,
    val translation: String,
    /** What the endpoint detected, null when it is neither of the two languages. */
    val from: TranslateLang?,
    val to: TranslateLang,
    val entries: List<DictionaryEntry>
)

/**
 * The endpoint the Google Translate web widget itself calls: no key, no account, and it answers with
 * more than the translation (detected language, dictionary entries per part of speech), which is
 * what turns a lookup into something worth putting on a flashcard.
 */
private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

/** Past this the query string gets long enough to be refused, so a paste is sent in pieces. */
private const val MAX_CHUNK = 1200

/**
 * Translates into [to], flipping the direction once when the text turns out to already be in the
 * target language: asking for French while typing French otherwise hands the text back unchanged.
 */
suspend fun translate(text: String, to: TranslateLang): TranslationResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return TranslationResult(trimmed, "", null, to, emptyList())
    val result = translateWhole(trimmed, to)
    return if (result.from == to) translateWhole(trimmed, to.other) else result
}

private suspend fun translateWhole(text: String, to: TranslateLang): TranslationResult {
    val parts = splitForTranslation(text).map { fetch(it, to) }
    val first = parts.first()
    return TranslationResult(
        source = text,
        // The chunks keep their own separators, so they go back together as they were cut.
        translation = parts.joinToString("") { it.translation },
        from = first.from,
        to = to,
        // A dictionary entry describes a single word; a text sent in pieces has none anyway.
        entries = if (parts.size == 1) first.entries else emptyList()
    )
}

private suspend fun fetch(text: String, to: TranslateLang): TranslationResult {
    val url = "$ENDPOINT?client=gtx&sl=auto&tl=${to.code}&dt=t&dt=bd&q=${URLEncoder.encode(text, "UTF-8")}"
    return parseTranslation(httpGetRetrying(url), text, to)
}

/**
 * The answer is a bare array: [0] the translated sentences, [1] the dictionary entries (absent for
 * anything longer than a word), [2] the detected source language.
 */
internal fun parseTranslation(body: String, source: String, to: TranslateLang): TranslationResult {
    val root = JSONArray(body)

    val sentences = root.optJSONArray(0)
    val translation = buildString {
        for (i in 0 until (sentences?.length() ?: 0)) {
            append(sentences?.optJSONArray(i)?.optString(0).orEmpty())
        }
    }

    val entries = mutableListOf<DictionaryEntry>()
    val dictionary = root.optJSONArray(1)
    for (i in 0 until (dictionary?.length() ?: 0)) {
        val entry = dictionary?.optJSONArray(i) ?: continue
        val terms = entry.optJSONArray(1) ?: continue
        val words = (0 until terms.length()).mapNotNull { terms.optString(it).takeIf { w -> w.isNotBlank() } }
        if (words.isNotEmpty()) entries += DictionaryEntry(entry.optString(0), words)
    }

    return TranslationResult(
        source = source,
        translation = translation,
        from = TranslateLang.fromCode(root.optString(2).takeIf { it.isNotBlank() }),
        to = to,
        entries = entries
    )
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
