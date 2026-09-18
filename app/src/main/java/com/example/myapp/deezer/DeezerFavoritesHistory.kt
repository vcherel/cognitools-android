package com.example.myapp.deezer

import android.content.Context
import com.example.myapp.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** The favorites count on one day. */
data class FavoritesSample(val day: LocalDate, val count: Int)

/**
 * How many liked tracks there were, day by day, so the library can draw the curve. One sample per
 * day, the day's last complete fetch winning. The first fetch seeds the past from each track's like
 * date, which reads as "tracks still liked, by when they were liked": an unlike is invisible back
 * there, and only shows in the samples taken from then on.
 */
class DeezerFavoritesHistory(context: Context) {
    private val file = File(context.filesDir, "deezer_favorites_history.json")
    private val samples = TreeMap<LocalDate, Int>()
    private val _history = MutableStateFlow<List<FavoritesSample>>(emptyList())
    val history: StateFlow<List<FavoritesSample>> = _history

    init {
        runCatching {
            if (file.exists()) {
                DeezerLibraryCache.json.parseToJsonElement(file.readText()).jsonObject.forEach { (day, count) ->
                    samples[LocalDate.parse(day)] = count.jsonPrimitive.content.toInt()
                }
            }
        }
        publish()
    }

    /**
     * Records today's count from a favorites list. An incomplete one (the disk snapshot, a like
     * before the library loaded) only seeds the past, its size says nothing about today. Callers
     * are on IO.
     */
    @Synchronized
    fun record(favorites: List<DeezerTrack>, complete: Boolean = true) {
        val today = LocalDate.now()
        val seeding = samples.isEmpty() && favorites.any { it.addedAtSec > 0 }
        if (seeding) seed(favorites, today)
        if (complete) samples[today] = favorites.size
        if (!seeding && !complete) return
        publish()
        runCatching {
            file.writeAtomically(buildJsonObject {
                samples.forEach { (day, count) -> put(day.toString(), count) }
            }.toString())
        }
    }

    private fun seed(favorites: List<DeezerTrack>, today: LocalDate) {
        val zone = ZoneId.systemDefault()
        val days = favorites.asSequence()
            .filter { it.addedAtSec > 0 }
            .map { Instant.ofEpochSecond(it.addedAtSec).atZone(zone).toLocalDate() }
            .filter { it < today }
            .sorted()
        var running = 0
        days.forEach { day -> samples[day] = ++running }
    }

    private fun publish() {
        _history.value = samples.map { (day, count) -> FavoritesSample(day, count) }
    }
}
