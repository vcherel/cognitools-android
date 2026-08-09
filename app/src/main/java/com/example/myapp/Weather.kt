package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
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
    var selectedCity by remember { mutableStateOf(loadSavedCity(context)) }
    var showCityDialog by remember { mutableStateOf(false) }
    // Bumped by the error's "Réessayer" button, the one way to re-run the fetch for a position
    // already selected.
    var reloadKey by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission, selectedCity, reloadKey) {
        val city = selectedCity
        if (city == null && !hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        try {
            val coords = if (city != null) {
                city.lat to city.lon
            } else {
                getCurrentLocation(context)?.let { it.latitude to it.longitude }
            }
            forecast = if (coords == null) {
                error = "Position indisponible, réessaie plus tard."
                null
            } else {
                fetchWeatherForecast(coords.first, coords.second)
            }
        } catch (e: Exception) {
            // Drop what was on screen: a stale forecast shown as if it were current is worse
            // than the error, and it would hide the message below.
            forecast = null
            error = weatherErrorMessage(e, "Erreur de chargement")
        } finally {
            isLoading = false
        }
    }

    BackHandler { onBack() }

    if (showCityDialog) {
        CitySearchDialog(
            onCitySelected = { city ->
                saveCity(context, city)
                selectedCity = city
            },
            onDismiss = { showCityDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Météo", onBack = onBack)

        LocationChip(
            label = selectedCity?.name ?: "Position actuelle",
            onClick = { showCityDialog = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        val currentForecast = forecast
        val currentError = error
        if (currentForecast != null) {
            WeatherContent(
                forecast = currentForecast,
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
                    !hasPermission -> {
                        Text(
                            "L'accès à la position est nécessaire pour afficher la météo locale, " +
                                "ou choisis une ville ci-dessus.",
                            modifier = Modifier.padding(16.dp)
                        )
                        TextButton(
                            onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text("Réessayer")
                        }
                    }
                    currentError != null -> {
                        ErrorText(
                            message = currentError,
                            onDismiss = { error = null },
                            modifier = Modifier.padding(16.dp)
                        )
                        TextButton(
                            onClick = { error = null; reloadKey++ },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text("Réessayer")
                        }
                    }
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
private fun LocationChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CitySearchDialog(onCitySelected: (CityLocation?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CityLocation>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Debounced so every keystroke doesn't fire a request.
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            searchError = null
            return@LaunchedEffect
        }
        delay(400)
        isSearching = true
        searchError = null
        try {
            results = searchCities(query.trim())
        } catch (e: Exception) {
            searchError = weatherErrorMessage(e, "Recherche impossible")
        } finally {
            isSearching = false
        }
        if (results.isNotEmpty()) SearchHistory.record(context, SearchSurface.CITY, query.trim())
    }

    AppDialog(onDismiss = onDismiss) {
        Text("Choisir une ville", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Nom de la ville") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (query.isBlank()) {
            RecentSearchChips(
                surface = SearchSurface.CITY,
                onPick = { query = it },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onCitySelected(null)
                    onDismiss()
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Utiliser ma position actuelle", style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        val currentError = searchError
        when {
            isSearching -> Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            currentError != null -> ErrorText(
                message = currentError,
                onDismiss = { searchError = null },
                modifier = Modifier.padding(vertical = 12.dp)
            )
            else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(results, key = { "${it.name}${it.lat}${it.lon}" }) { city ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCitySelected(city)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Column {
                            Text(city.name, style = MaterialTheme.typography.bodyLarge)
                            val subtitle = listOfNotNull(city.admin1, city.country).joinToString(", ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    }
}

// Open-Meteo throttles per IP, and a mobile carrier's shared IP can hit that limit on traffic that
// isn't even ours. The request is already retried a couple of times before this shows, so what's
// left to say is "wait a moment", not an HTTP status.
private fun weatherErrorMessage(e: Exception, fallbackPrefix: String): String =
    if (e is HttpStatusException && e.code == 429) "Service météo saturé, réessaie dans un instant."
    else "$fallbackPrefix: ${e.message}"

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
        Column(horizontalAlignment = Alignment.End) {
            Text("${day.rainProb}% 🌧️", color = contentColor)
            if (day.rainAmount >= 0.1) {
                Text(
                    "${formatRainAmount(day.rainAmount)}mm",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
        }
    }
}

private fun formatRainAmount(amount: Double): String =
    if (amount >= 10) amount.roundToInt().toString() else String.format(Locale.FRANCE, "%.1f", amount)

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
