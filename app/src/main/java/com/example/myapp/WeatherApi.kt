package com.example.myapp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.resume

// Where the Météo screen gets its data: the Open-Meteo forecast and geocoding APIs, the saved
// city, and the device's own position.

data class HourlyPoint(val time: LocalDateTime, val temp: Double, val rainProb: Int, val rainAmount: Double, val weatherCode: Int)
data class DailyPoint(val date: LocalDate, val tempMax: Double, val tempMin: Double, val rainProb: Int, val rainAmount: Double, val weatherCode: Int)
data class WeatherForecast(val hourly: List<HourlyPoint>, val daily: List<DailyPoint>)
data class CityLocation(val name: String, val admin1: String?, val country: String?, val lat: Double, val lon: Double)

private const val WEATHER_PREFS = "weather_prefs"
private const val KEY_SELECTED_CITY = "selected_city"

fun loadSavedCity(context: Context): CityLocation? {
    val json = context.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_CITY, null) ?: return null
    return runCatching {
        val obj = JSONObject(json)
        CityLocation(
            name = obj.getString("name"),
            admin1 = obj.optString("admin1").ifBlank { null },
            country = obj.optString("country").ifBlank { null },
            lat = obj.getDouble("lat"),
            lon = obj.getDouble("lon")
        )
    }.getOrNull()
}

fun saveCity(context: Context, city: CityLocation?) {
    val prefs = context.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)
    if (city == null) {
        prefs.edit().remove(KEY_SELECTED_CITY).apply()
        return
    }
    val json = JSONObject().apply {
        put("name", city.name)
        put("admin1", city.admin1 ?: "")
        put("country", city.country ?: "")
        put("lat", city.lat)
        put("lon", city.lon)
    }
    prefs.edit().putString(KEY_SELECTED_CITY, json.toString()).apply()
}

suspend fun searchCities(query: String): List<CityLocation> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=fr&format=json"
    val json = JSONObject(httpGetRetrying(url))
    val results = json.optJSONArray("results") ?: return@withContext emptyList()
    (0 until results.length()).map { i ->
        val obj = results.getJSONObject(i)
        CityLocation(
            name = obj.getString("name"),
            admin1 = obj.optString("admin1").ifBlank { null },
            country = obj.optString("country").ifBlank { null },
            lat = obj.getDouble("latitude"),
            lon = obj.getDouble("longitude")
        )
    }
}

// One field, requested from both models at once: Météo-France (AROME over France, seamlessly
// falling back to their coarser global ARPEGE model elsewhere) and Open-Meteo's best_match blend.
private class ModelSeries(json: JSONObject, field: String) {
    private val meteoFrance = json.getJSONArray("${field}_meteofrance_seamless")
    private val bestMatch = json.getJSONArray("${field}_best_match")

    private fun series(useFallback: Boolean) = if (useFallback) bestMatch else meteoFrance
    fun double(index: Int, useFallback: Boolean, fallbackValue: Double) =
        series(useFallback).optDouble(index, fallbackValue)
    fun int(index: Int, useFallback: Boolean, fallbackValue: Int) =
        series(useFallback).optInt(index, fallbackValue)
}

// A forecast doesn't change from one minute to the next, and Open-Meteo throttles by IP, so every
// position keeps its last one for a quarter of an hour. Switching between two cities, or leaving
// and reopening the screen, then costs no request at all, which is what used to earn a 429.
private const val FORECAST_TTL_MS = 15 * 60_000L
private val forecastCache = mutableMapOf<String, Pair<Long, WeatherForecast>>()

/** Rounded to Open-Meteo's own grid resolution: two nearby positions share one forecast. */
private fun cacheKey(lat: Double, lon: Double) = String.format(Locale.US, "%.2f,%.2f", lat, lon)

/** The 16 day forecast for a position, from the cache when it holds a recent enough one. */
suspend fun fetchWeatherForecast(lat: Double, lon: Double): WeatherForecast {
    val key = cacheKey(lat, lon)
    synchronized(forecastCache) {
        forecastCache[key]?.let { (fetchedAt, forecast) ->
            if (System.currentTimeMillis() - fetchedAt < FORECAST_TTL_MS) return forecast
        }
    }
    val forecast = downloadForecast(lat, lon)
    synchronized(forecastCache) { forecastCache[key] = System.currentTimeMillis() to forecast }
    return forecast
}

/**
 * Rain probability only ever comes from best_match, since Météo-France's deterministic model
 * doesn't expose one, only an amount. Every other field prefers Météo-France and falls back to
 * best_match past its much shorter horizon, where its values turn null, rather than leaving the
 * rest of the 16 days empty.
 */
private suspend fun downloadForecast(lat: Double, lon: Double): WeatherForecast = withContext(Dispatchers.IO) {
    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weathercode" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,weathercode" +
            "&timezone=auto&forecast_days=16&models=meteofrance_seamless,best_match"
    val json = JSONObject(httpGetRetrying(url))

    val hourlyJson = json.getJSONObject("hourly")
    val hourlyTimes = hourlyJson.getJSONArray("time")
    val hourlyTemps = ModelSeries(hourlyJson, "temperature_2m")
    val hourlyAmounts = ModelSeries(hourlyJson, "precipitation")
    val hourlyCodes = ModelSeries(hourlyJson, "weathercode")
    val hourlyRainProb = hourlyJson.getJSONArray("precipitation_probability_best_match")
    val hourly = (0 until hourlyTimes.length()).mapNotNull { i ->
        // A missing temperature is what marks the end of Météo-France's horizon for that hour.
        val useFallback = hourlyTemps.double(i, false, Double.NaN).isNaN()
        val temp = hourlyTemps.double(i, useFallback, Double.NaN)
        if (temp.isNaN()) return@mapNotNull null
        HourlyPoint(
            time = LocalDateTime.parse(hourlyTimes.getString(i)),
            temp = temp,
            rainProb = hourlyRainProb.optInt(i, 0),
            rainAmount = hourlyAmounts.double(i, useFallback, 0.0),
            weatherCode = hourlyCodes.int(i, useFallback, 0)
        )
    }

    val dailyJson = json.getJSONObject("daily")
    val dailyDates = dailyJson.getJSONArray("time")
    val dailyMax = ModelSeries(dailyJson, "temperature_2m_max")
    val dailyMin = ModelSeries(dailyJson, "temperature_2m_min")
    val dailyAmounts = ModelSeries(dailyJson, "precipitation_sum")
    val dailyCodes = ModelSeries(dailyJson, "weathercode")
    val dailyRainProb = dailyJson.getJSONArray("precipitation_probability_max_best_match")
    val daily = (0 until dailyDates.length()).mapNotNull { i ->
        val useFallback = dailyMax.double(i, false, Double.NaN).isNaN() ||
            dailyMin.double(i, false, Double.NaN).isNaN()
        val tempMax = dailyMax.double(i, useFallback, Double.NaN)
        val tempMin = dailyMin.double(i, useFallback, Double.NaN)
        if (tempMax.isNaN() || tempMin.isNaN()) return@mapNotNull null
        DailyPoint(
            date = LocalDate.parse(dailyDates.getString(i)),
            tempMax = tempMax,
            tempMin = tempMin,
            rainProb = dailyRainProb.optInt(i, 0),
            rainAmount = dailyAmounts.double(i, useFallback, 0.0),
            weatherCode = dailyCodes.int(i, useFallback, 0)
        )
    }

    WeatherForecast(hourly, daily)
}

// Caller must have already checked/requested ACCESS_COARSE_LOCATION.
@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { locationManager.isProviderEnabled(it) }

    // A cached fix is instant and precise enough for weather.
    providers.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { return@withContext it }

    // No cached fix: ask for a fresh one, with a timeout so the screen doesn't hang forever.
    val provider = providers.firstOrNull() ?: return@withContext null
    suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                if (cont.isActive) cont.resume(location)
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
        cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
        mainHandler.postDelayed({
            locationManager.removeUpdates(listener)
            if (cont.isActive) cont.resume(null)
        }, 10_000)
    }
}
