package com.example.myapp

import java.text.Normalizer

/**
 * Accent-free lowercase form, the base every comparison in the app normalizes to: "Best pépites 💎"
 * becomes "best pepites 💎". Decomposes to NFD so an accent becomes a separate combining mark, then
 * drops those marks; anything that isn't a letter is left alone (see [matchNormalized] to drop it).
 */
fun String.deaccented(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

/**
 * [deaccented] with every space and punctuation mark removed too, so two spellings of the same title
 * ("Le Masque & la Plume", "le masque et la plume" aside) compare equal. What titles are matched on.
 */
fun String.matchNormalized(): String = deaccented().replace(NON_ALPHANUMERIC, "")

/** [deaccented] as a URL path segment: runs of anything non-alphanumeric become a single dash. */
fun String.slugified(): String = deaccented().replace(NON_ALPHANUMERIC, "-").trim('-')

/**
 * Lowercased and stripped of accents, so "creme" finds "crème". Deliberately one output character
 * per input character (a decomposed letter keeps only its base): every index into the result is also
 * a valid index into the original, which is what lets the notes search highlight what it found.
 */
fun normalizeForSearch(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) out.append(foldChar(ch))
    return out.toString()
}

// Folding one character is the expensive part: NFD normalization allocates a String per call.
// Plain ASCII skips it, and the Latin range every accented letter the app sees lives in is folded
// once and kept, so a search over long notes stops normalizing the same letters over and over.
private const val FOLD_CACHE_SIZE = 0x250
private val foldCache = CharArray(FOLD_CACHE_SIZE)

private fun foldChar(ch: Char): Char {
    if (ch.code < 128) return ch.lowercaseChar()
    if (ch.code >= FOLD_CACHE_SIZE) return decomposedBase(ch)
    val cached = foldCache[ch.code]
    if (cached != '\u0000') return cached
    return decomposedBase(ch).also { foldCache[ch.code] = it }
}

private fun decomposedBase(ch: Char): Char =
    (Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch).lowercaseChar()

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
