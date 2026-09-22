package com.example.myapp.mail

/**
 * The one-time code a mail carries, if any: what the list shows as a chip to copy without opening
 * the mail. The subject wins over the body, since services that put the code there put nothing
 * ambiguous next to it. A number only counts when it sits close to a word saying it is a code, so an
 * order number or a price in a newsletter stays a number.
 */
fun findVerificationCode(subject: String, body: String): String? =
    codeIn(subject) ?: codeIn(body.take(BODY_SCAN_LIMIT))

private fun codeIn(text: String): String? {
    val keywords = KEYWORD.findAll(text).map { it.range }.toList()
    if (keywords.isEmpty()) return null
    return CANDIDATE.findAll(text)
        .map { match -> match to keywords.minOf { distance(it, match.range) } }
        .filter { (match, distance) -> distance <= MAX_DISTANCE && !looksLikeYear(match.value) }
        .minByOrNull { (_, distance) -> distance }
        ?.first?.value?.filter { it.isLetterOrDigit() }
}

private fun distance(keyword: IntRange, candidate: IntRange): Int = when {
    keyword.last < candidate.first -> candidate.first - keyword.last
    candidate.last < keyword.first -> keyword.first - candidate.last
    else -> Int.MAX_VALUE
}

private fun looksLikeYear(value: String): Boolean =
    value.length == 4 && value.all { it.isDigit() } && value.toInt() in 1990..2099

private const val BODY_SCAN_LIMIT = 6000
private const val MAX_DISTANCE = 120

private val KEYWORD = Regex(
    "code|v[ée]rification|verify|otp|one[- ]time|passcode|mot de passe|password|s[ée]curit|security|connexion|login|sign[- ]?in|authenti|confirm",
    RegexOption.IGNORE_CASE
)

// Digits (a 6 digit code may be printed as two groups of 3), or an upper case run mixing letters and
// digits. Never glued to a word, a time, a decimal or a currency sign.
private val CANDIDATE = Regex(
    "(?<![\\w:.,/€$#])(\\d{3}[ -]\\d{3}|\\d{4,8}|(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,10})(?![\\w:/%]|[.,]\\d|\\s?[€$%])"
)
