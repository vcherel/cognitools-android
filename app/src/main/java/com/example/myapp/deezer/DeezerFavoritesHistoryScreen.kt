package com.example.myapp.deezer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.myapp.ScreenTopBar
import com.example.myapp.plural
import com.example.myapp.runIgnoringErrors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** The favorites count over time: the number today, how it moved lately, and the curve since the first like. */
@Composable
fun DeezerFavoritesHistoryScreen(repo: DeezerRepository, onBack: () -> Unit) {
    val history by repo.favoritesHistory.history.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val count = favorites?.size ?: history.lastOrNull()?.count ?: 0
    val today = LocalDate.now()
    val monthAgo = countAt(history, today.minusDays(30))
    val yearAgo = countAt(history, today.minusDays(365))
    var loading by remember { mutableStateOf(false) }

    // The library screen's own refresh dies with that screen, and a tap on the count a moment after
    // landing there is exactly how this one opens: fetch here when there is nothing to draw yet.
    LaunchedEffect(Unit) {
        if (history.isNotEmpty() || !repo.hasNetwork()) return@LaunchedEffect
        loading = true
        runIgnoringErrors { repo.refreshLibrary() }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Favoris", onBack = onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text("$count", style = MaterialTheme.typography.displayMedium)
            Text("titre${plural(count)} aimé${plural(count)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            history.firstOrNull()?.let { first ->
                Text(
                    "Depuis le ${first.day.format(FULL_DATE)} · +${count - monthAgo} sur 30 jours · +${count - yearAgo} sur un an",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
            when {
                history.isNotEmpty() -> FavoritesCurve(history, today, Modifier.fillMaxWidth().height(300.dp))
                loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                else -> Text("Pas encore de relevé, reviens ici une fois en ligne.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The count on [day]: the last sample taken on or before it, 0 before the first. */
private fun countAt(history: List<FavoritesSample>, day: LocalDate): Int =
    history.lastOrNull { it.day <= day }?.count ?: 0

private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

/**
 * The curve on one Canvas: samples joined by straight segments, the last one carried flat to today,
 * a light fill under it, three gridlines on the left and a date tick per year or per month
 * depending on the span.
 */
@Composable
private fun FavoritesCurve(history: List<FavoritesSample>, today: LocalDate, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()

    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
    ) {
        val firstDay = minOf(history.first().day, today.minusDays(30))
        val spanDays = (today.toEpochDay() - firstDay.toEpochDay()).coerceAtLeast(1)
        val maxCount = history.maxOf { it.count }.coerceAtLeast(1)
        val yTop = niceCeiling(maxCount)
        val labelW = measurer.measure("$yTop", labelStyle).size.width + 8.dp.toPx()
        val labelH = measurer.measure("2000", labelStyle).size.height
        val plotLeft = labelW
        val plotRight = size.width
        val plotTop = 0f
        val plotBottom = size.height - labelH - 6.dp.toPx()
        val plotH = plotBottom - plotTop
        val plotW = plotRight - plotLeft

        fun x(day: LocalDate) = plotLeft + plotW * (day.toEpochDay() - firstDay.toEpochDay()) / spanDays
        fun y(count: Int) = plotBottom - plotH * count / yTop

        val gridStyle = TextStyle(color = labelColor, fontSize = labelStyle.fontSize)
        for (i in 0..3) {
            val value = yTop * i / 3
            val yy = y(value)
            drawLine(grid, Offset(plotLeft, yy), Offset(plotRight, yy), strokeWidth = 1.dp.toPx())
            val text = measurer.measure("$value", gridStyle)
            drawText(text, topLeft = Offset(plotLeft - text.size.width - 6.dp.toPx(), yy - text.size.height / 2f))
        }

        val ticks = dateTicks(firstDay, today, spanDays)
        var lastTickRight = Float.NEGATIVE_INFINITY
        ticks.forEach { (day, label) ->
            val xx = x(day)
            drawLine(grid, Offset(xx, plotTop), Offset(xx, plotBottom), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(label, gridStyle)
            val left = (xx - text.size.width / 2f).coerceIn(plotLeft, plotRight - text.size.width)
            if (left > lastTickRight + 4.dp.toPx()) {
                drawText(text, topLeft = Offset(left, plotBottom + 6.dp.toPx()))
                lastTickRight = left + text.size.width
            }
        }

        val path = Path()
        val points = history.map { Offset(x(it.day), y(it.count)) } + Offset(x(today), y(history.last().count))
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        val fill = Path().apply {
            addPath(path)
            lineTo(points.last().x, plotBottom)
            lineTo(points.first().x, plotBottom)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent), startY = plotTop, endY = plotBottom))
        drawPath(path, line, style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(line, radius = 4.dp.toPx(), center = points.last())
    }
}

/** Rounds [max] up to a friendly axis top divisible by 3 gridlines. */
private fun niceCeiling(max: Int): Int {
    val step = when {
        max <= 30 -> 10
        max <= 150 -> 50
        max <= 300 -> 100
        max <= 1500 -> 500
        else -> 1000
    }
    var top = (max + step - 1) / step * step
    while (top % 3 != 0) top += step
    return top
}

/** One tick per year over a long span, per month otherwise, each on its first day. */
private fun dateTicks(first: LocalDate, today: LocalDate, spanDays: Long): List<Pair<LocalDate, String>> {
    val ticks = ArrayList<Pair<LocalDate, String>>()
    if (spanDays > 730) {
        var day = LocalDate.of(first.year + 1, 1, 1)
        while (day <= today) {
            ticks += day to "${day.year}"
            day = day.plusYears(1)
        }
    } else {
        var day = first.withDayOfMonth(1).plusMonths(1)
        while (day <= today) {
            val label = day.month.getDisplayName(JavaTextStyle.SHORT, Locale.FRENCH).trimEnd('.')
            ticks += day to if (day.monthValue == 1) "${day.year}" else label
            day = day.plusMonths(1)
        }
    }
    return ticks
}
