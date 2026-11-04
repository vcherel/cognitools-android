package com.example.myapp.flashcards

import com.example.myapp.formatDuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSheet(
    title: String,
    onDismiss: () -> Unit,
    loadStats: suspend () -> FlashcardStats
) {
    var stats by remember { mutableStateOf<FlashcardStats?>(null) }

    LaunchedEffect(Unit) { stats = loadStats() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            val s = stats
            if (s == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatSummaryItem("Total", s.totalCards.toString())
                    StatSummaryItem("To review", s.dueCards.toString())
                    StatSummaryItem(
                        "Rate",
                        if (s.winRate < 0) "-" else "${(s.winRate * 100).toInt()}%"
                    )
                    StatSummaryItem(
                        "Average wait",
                        if (s.meanTimeUntilNextReviewMs < 0) "-"
                        else formatDuration(s.meanTimeUntilNextReviewMs)
                    )
                }

                if (s.totalCards > 0) {
                    Spacer(Modifier.height(24.dp))
                    Text("Coming up", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    ForecastChart(s.forecast)

                    Spacer(Modifier.height(24.dp))
                    Text("Maturity", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    MaturityBar(s.maturity)
                }

                Spacer(Modifier.height(24.dp))
                Text("Score distribution", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                val maxCount = s.scoreBuckets.maxOf { it.second }.coerceAtLeast(1)

                s.scoreBuckets.forEach { (score, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "$score",
                            modifier = Modifier.width(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = scoreColor(score),
                            fontWeight = FontWeight.Bold
                        )
                        Box(modifier = Modifier.weight(1f).height(14.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(count.toFloat() / maxCount)
                                    .background(
                                        scoreColor(score).copy(alpha = 0.75f),
                                        RoundedCornerShape(7.dp)
                                    )
                            )
                        }
                        Text(
                            "$count",
                            modifier = Modifier.width(36.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)

@Composable
private fun ForecastChart(forecast: List<Int>) {
    val maxCount = forecast.max().coerceAtLeast(1)
    val today = LocalDate.now()
    val barColor = Color(0xFF2F86EB)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        forecast.forEachIndexed { day, count ->
            val isToday = day == 0
            val textColor =
                if (isToday) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            val weight = if (isToday) FontWeight.SemiBold else FontWeight.Normal
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    fontWeight = weight,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .fillMaxHeight(count.toFloat() / maxCount)
                            .background(
                                if (isToday) barColor else barColor.copy(alpha = 0.75f),
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isToday) "Today" else today.plusDays(day.toLong()).format(dayFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

private fun maturityColor(maturity: Maturity): Color = when (maturity) {
    Maturity.New -> Color(0xFFB0BEC5)
    Maturity.Learning -> Color(0xFFFF9900)
    Maturity.Young -> Color(0xFFFFCC00)
    Maturity.Mature -> Color(0xFF7CC63A)
    Maturity.Mastered -> Color(0xFF2E9E2E)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaturityBar(counts: Map<Maturity, Int>) {
    val present = Maturity.entries.filter { (counts[it] ?: 0) > 0 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        present.forEach { stage ->
            Box(
                modifier = Modifier
                    .weight(counts.getValue(stage).toFloat())
                    .fillMaxHeight()
                    .background(maturityColor(stage))
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Maturity.entries.forEach { stage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(maturityColor(stage), CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stage.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${counts[stage] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StatSummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

fun scoreColor(score: Int): Color = when (score) {
    0 -> Color(0xFFFF0000)
    1 -> Color(0xFFFF3300)
    2 -> Color(0xFFFF6600)
    3 -> Color(0xFFFF9900)
    4 -> Color(0xFFFFCC00)
    5 -> Color(0xFFFFFF00)
    6 -> Color(0xFFCCFF00)
    7 -> Color(0xFF99FF00)
    8 -> Color(0xFF66FF00)
    9 -> Color(0xFF33FF00)
    else -> Color(0xFF00CC00)
}
