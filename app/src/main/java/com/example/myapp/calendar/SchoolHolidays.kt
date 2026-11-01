package com.example.myapp.calendar

import android.content.Context
import com.example.myapp.httpGet
import com.example.myapp.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** A school holiday of the académie de Rennes: [start] is the first day off, [end] the day classes resume. */
data class SchoolHoliday(val name: String, val start: LocalDate, val end: LocalDate) {
    fun contains(day: LocalDate) = !day.isBefore(start) && day.isBefore(end)
}

private val PARIS = ZoneId.of("Europe/Paris")

/**
 * Reads the ministry's open data answer. Its dates are instants at Paris midnight; the teachers'
 * copy of the summer break and the zero length markers ("Début des Vacances d'Été") are left out.
 */
fun parseSchoolHolidays(json: String): List<SchoolHoliday> {
    val results = JSONObject(json).getJSONArray("results")
    return (0 until results.length())
        .map { results.getJSONObject(it) }
        .filter { it.optString("population") != "Enseignants" }
        .map {
            SchoolHoliday(
                name = it.getString("description"),
                start = OffsetDateTime.parse(it.getString("start_date")).atZoneSameInstant(PARIS).toLocalDate(),
                end = OffsetDateTime.parse(it.getString("end_date")).atZoneSameInstant(PARIS).toLocalDate()
            )
        }
        .filter { it.start.isBefore(it.end) }
        .distinctBy { it.name to it.start }
}

/** The holidays cached on the phone, refetched once the copy is a month old. Offline, the old copy stays. */
object SchoolHolidays {
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private fun cacheFile(context: Context) = File(context.filesDir, "school_holidays.json")

    suspend fun cached(context: Context): List<SchoolHoliday> = withContext(Dispatchers.IO) {
        runCatching { parseSchoolHolidays(cacheFile(context).readText()) }.getOrDefault(emptyList())
    }

    /** The fresh list when the cache was stale and the fetch worked, null otherwise. */
    suspend fun refreshIfStale(context: Context): List<SchoolHoliday>? = withContext(Dispatchers.IO) {
        val file = cacheFile(context)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return@withContext null
        runCatching {
            val since = LocalDate.now().minusYears(1)
            val where = URLEncoder.encode("location=\"Rennes\" and end_date>\"$since\"", "UTF-8")
            val json = httpGet(
                "https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/fr-en-calendrier-scolaire/records" +
                    "?where=$where&order_by=start_date&limit=100"
            )
            parseSchoolHolidays(json).also { file.writeAtomically(json) }
        }.getOrNull()
    }
}
