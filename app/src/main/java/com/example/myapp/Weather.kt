package com.example.myapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

@Composable
fun WeatherScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var isLoading by remember { mutableStateOf(false) }
    var forecast by remember { mutableStateOf<WeatherForecast?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        try {
            val location = getCurrentLocation(context)
            forecast = if (location == null) {
                error = "Position indisponible, réessaie plus tard."
                null
            } else {
                withContext(Dispatchers.IO) {
                    fetchWeatherForecast(location.latitude, location.longitude)
                }
            }
        } catch (e: Exception) {
            error = "Erreur de chargement: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Météo", onBack = onBack)

        if (forecast != null) {
            WeatherContent(
                forecast = forecast!!,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    isLoading -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                    !hasPermission -> Text(
                        "L'accès à la position est nécessaire pour afficher la météo locale.",
                        modifier = Modifier.padding(16.dp)
                    )
                    error != null -> Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherContent(forecast: WeatherForecast, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val now = LocalDateTime.now()

    // One expanded day at a time (accordion), so only its hour strip is ever rendered. Defaults
    // to today; tapping the open day collapses it, and the summary card falls back to today.
    var expandedDate by remember(forecast) { mutableStateOf<LocalDate?>(today) }
    val summaryDate = expandedDate ?: today

    // Hours grouped by day once, so expanding a day is a cheap lookup rather than a full filter.
    val hoursByDate = remember(forecast) { forecast.hourly.groupBy { it.time.toLocalDate() } }

    // One slot in from the left edge, so the current hour isn't flush against the strip's border.
    val currentHourTarget = remember(forecast) {
        val currentIndex = hoursByDate[today].orEmpty().indexOfFirst { it.time.hour == now.hour }
        (currentIndex - 1).coerceAtLeast(0)
    }
    // Every day's HourStrip binds this one state. That only stays correct because a closing strip
    // leaves composition instantly (the ExitTransition.None below is load-bearing): if a strip ever
    // animated out, two strips would bind sharedHourState at once during a day switch.
    val sharedHourState = remember(forecast) { LazyListState(firstVisibleItemIndex = currentHourTarget) }
    // Which edge of a newly opened day to land on: the start when moving forward (next-day arrow,
    // or opening a day from the list), the end when moving backward (previous-day arrow). Today is
    // always the exception, snapping to the current hour instead, regardless of edge.
    var landOnEndOfDay by remember(forecast) { mutableStateOf(false) }
    LaunchedEffect(expandedDate) {
        val date = expandedDate ?: return@LaunchedEffect
        val hours = hoursByDate[date].orEmpty()
        if (hours.isEmpty()) return@LaunchedEffect
        val dayIndex = forecast.daily.indexOfFirst { it.date == date }
        // Days other than the first have a leading previous-day arrow item shifting every index by 1.
        val offset = if (dayIndex > 0) 1 else 0
        val target = when {
            date == today -> offset + currentHourTarget
            landOnEndOfDay -> offset + hours.size - 1
            else -> offset
        }
        sharedHourState.scrollToItem(target)
    }

    val summaryHours = hoursByDate[summaryDate].orEmpty()
    // Only current/future hours, used to figure out when the next rain is.
    val upcomingHours = if (summaryDate == today) summaryHours.filter { it.time >= now.withMinute(0) } else summaryHours

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = daySummary(upcomingHours, summaryDate),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        forecast.daily.forEachIndexed { index, day ->
            val expanded = day.date == expandedDate
            DayRow(
                day = day,
                selected = expanded,
                onClick = {
                    landOnEndOfDay = false
                    expandedDate = if (expanded) null else day.date
                }
            )
            // A closing strip leaves composition immediately (no exit animation) so two strips
            // never share sharedHourState at once during a day switch.
            AnimatedVisibility(visible = expanded, exit = ExitTransition.None) {
                HourStrip(
                    hours = hoursByDate[day.date].orEmpty(),
                    state = sharedHourState,
                    today = today,
                    now = now,
                    onPrevious = {
                        landOnEndOfDay = true
                        expandedDate = forecast.daily[index - 1].date
                    }.takeIf { index > 0 },
                    onNext = {
                        landOnEndOfDay = false
                        expandedDate = forecast.daily[index + 1].date
                    }.takeIf { index < forecast.daily.lastIndex }
                )
            }
        }
    }
}

@Composable
private fun HourStrip(
    hours: List<HourlyPoint>,
    state: LazyListState,
    today: LocalDate,
    now: LocalDateTime,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?
) {
    val hourItemWidth = 64.dp
    val isToday = hours.firstOrNull()?.time?.toLocalDate() == today

    LazyRow(
        state = state,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        if (onPrevious != null) {
            item(key = "previous") {
                DayNavArrow(icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, onClick = onPrevious)
            }
        }
        items(hours, key = { it.time.toString() }) { point ->
            val isCurrent = isToday && point.time.hour == now.hour
            val textColor = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(hourItemWidth)
                    .padding(horizontal = 2.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .then(if (isCurrent) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
                    .padding(vertical = 6.dp)
            ) {
                Text("${point.time.hour}h", style = MaterialTheme.typography.bodySmall, color = textColor)
                Text(weatherCodeToEmoji(point.weatherCode), fontSize = 20.sp)
                Text("${point.temp.roundToInt()}°", fontWeight = FontWeight.Medium, color = textColor)
                Text(
                    "${point.rainProb}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) textColor else MaterialTheme.colorScheme.primary
                )
            }
        }
        if (onNext != null) {
            item(key = "next") {
                DayNavArrow(icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, onClick = onNext)
            }
        }
    }
}

@Composable
private fun DayNavArrow(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DayRow(day: DailyPoint, selected: Boolean, onClick: () -> Unit) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            shortDayLabel(day.date),
            color = contentColor,
            modifier = Modifier.width(90.dp)
        )
        Text(weatherCodeToEmoji(day.weatherCode), fontSize = 20.sp, modifier = Modifier.width(36.dp))
        Text(
            "${day.tempMax.roundToInt()}° / ${day.tempMin.roundToInt()}°",
            color = contentColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text("${day.rainProb}% 🌧️", color = contentColor)
    }
}

private fun shortDayLabel(date: LocalDate): String {
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

private fun weatherCodeToEmoji(code: Int): String = when (code) {
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
private fun daySummary(hours: List<HourlyPoint>, date: LocalDate): String {
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

data class HourlyPoint(val time: LocalDateTime, val temp: Double, val rainProb: Int, val weatherCode: Int)
data class DailyPoint(val date: LocalDate, val tempMax: Double, val tempMin: Double, val rainProb: Int, val weatherCode: Int)
data class WeatherForecast(val hourly: List<HourlyPoint>, val daily: List<DailyPoint>)

private fun fetchWeatherForecast(lat: Double, lon: Double): WeatherForecast {
    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,precipitation_probability,weathercode" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weathercode" +
            "&timezone=auto&forecast_days=16"
    val json = JSONObject(httpGet(url))

    val hourlyJson = json.getJSONObject("hourly")
    val hourlyTimes = hourlyJson.getJSONArray("time")
    val hourlyTemps = hourlyJson.getJSONArray("temperature_2m")
    val hourlyRain = hourlyJson.getJSONArray("precipitation_probability")
    val hourlyCodes = hourlyJson.getJSONArray("weathercode")
    // Temperature and weather code are also null for the last few hours of the forecast window,
    // same as precipitation probability: drop those hours instead of crashing.
    val hourly = (0 until hourlyTimes.length()).mapNotNull { i ->
        val temp = hourlyTemps.optDouble(i, Double.NaN)
        if (temp.isNaN()) return@mapNotNull null
        HourlyPoint(
            time = LocalDateTime.parse(hourlyTimes.getString(i)),
            temp = temp,
            rainProb = hourlyRain.optInt(i, 0),
            weatherCode = hourlyCodes.optInt(i, 0)
        )
    }

    val dailyJson = json.getJSONObject("daily")
    val dailyDates = dailyJson.getJSONArray("time")
    val dailyMax = dailyJson.getJSONArray("temperature_2m_max")
    val dailyMin = dailyJson.getJSONArray("temperature_2m_min")
    val dailyRain = dailyJson.getJSONArray("precipitation_probability_max")
    val dailyCodes = dailyJson.getJSONArray("weathercode")
    val daily = (0 until dailyDates.length()).mapNotNull { i ->
        val tempMax = dailyMax.optDouble(i, Double.NaN)
        val tempMin = dailyMin.optDouble(i, Double.NaN)
        if (tempMax.isNaN() || tempMin.isNaN()) return@mapNotNull null
        DailyPoint(
            date = LocalDate.parse(dailyDates.getString(i)),
            tempMax = tempMax,
            tempMin = tempMin,
            rainProb = dailyRain.optInt(i, 0),
            weatherCode = dailyCodes.optInt(i, 0)
        )
    }

    return WeatherForecast(hourly, daily)
}

// Caller must have already checked/requested ACCESS_COARSE_LOCATION.
@SuppressLint("MissingPermission")
private suspend fun getCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
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
