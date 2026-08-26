package com.example.myapp

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// What the weather screen prints: the labels, the emoji for a condition code, the day's one line
// summary and the message an error turns into. Kept apart from Weather.kt, which is all layout.

internal fun formatRainAmount(amount: Double): String =
    if (amount >= 10) amount.roundToInt().toString() else String.format(Locale.FRANCE, "%.1f", amount)

internal fun shortDayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Auj."
        today.plusDays(1) -> "Dem."
        else -> {
            val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRANCE)
                .replaceFirstChar { it.uppercase() }
            "$dayName ${date.dayOfMonth}"
        }
    }
}

internal fun weatherCodeToEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1, 2 -> "🌤️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57 -> "🌦️"
    61, 63, 65, 66, 67 -> "🌧️"
    71, 73, 75, 77 -> "🌨️"
    80, 81, 82 -> "🌧️"
    85, 86 -> "🌨️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}

// Reports the first hour of the selected day with a real chance of rain,
// rather than just the current condition (which is easy enough to check by looking outside).
internal fun daySummary(hours: List<HourlyPoint>, date: LocalDate): String {
    if (hours.isEmpty()) return "Prévisions indisponibles"

    val isToday = date == LocalDate.now()
    val rainThreshold = 50
    val rainHour = hours.firstOrNull { it.rainProb >= rainThreshold }
        ?: return if (isToday) "☀️ Pas de pluie prévue aujourd'hui" else "☀️ Pas de pluie prévue ce jour-là"

    return if (rainHour === hours.first()) {
        if (isToday) "🌧️ Pluie en cours ou imminente" else "🌧️ Pluie dès le matin"
    } else {
        "🌧️ Pluie prévue vers ${rainHour.time.hour}h"
    }
}

// Open-Meteo throttles per IP, and a mobile carrier's shared IP can hit that limit on traffic that
// isn't even ours. The request is already retried a couple of times before this shows, so what's
// left to say is "wait a moment", not an HTTP status.
internal fun weatherErrorMessage(e: Exception, fallbackPrefix: String): String =
    if (e is HttpStatusException && e.code == 429) "Service météo saturé, réessaie dans un instant."
    else "$fallbackPrefix: ${e.message}"
