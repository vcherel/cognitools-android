package com.example.myapp

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val MONTH_MS = 30 * DAY_MS

/** Formats a duration in French: compact keeps the largest unit, detailed spells them all out. */
fun formatDuration(ms: Long, detailed: Boolean = false): String {
    if (ms <= 0) return "Maintenant"
    if (!detailed) return when {
        ms < HOUR_MS -> "${ms / MINUTE_MS}min"
        ms < DAY_MS -> "${ms / HOUR_MS}h"
        ms < MONTH_MS -> "${ms / DAY_MS}j"
        else -> "${ms / MONTH_MS}mois"
    }
    val months = ms / MONTH_MS
    val days = ms % MONTH_MS / DAY_MS
    val hours = ms % DAY_MS / HOUR_MS
    val minutes = ms % HOUR_MS / MINUTE_MS
    return when {
        ms < MINUTE_MS -> "${ms / 1000}s"
        ms < HOUR_MS -> "${minutes}min"
        ms < DAY_MS -> "${hours}h ${minutes}min"
        ms < MONTH_MS -> "${days}j ${hours}h ${minutes}min"
        else -> "${months}mois ${days}j ${hours}h ${minutes}min"
    }
}
