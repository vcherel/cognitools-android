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

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
