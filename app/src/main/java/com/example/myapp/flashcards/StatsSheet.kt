package com.example.myapp.flashcards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    StatSummaryItem("À réviser", s.dueCards.toString())
                    StatSummaryItem(
                        "Taux",
                        if (s.winRate < 0) "-" else "${(s.winRate * 100).toInt()}%"
                    )
                    StatSummaryItem(
                        "Délai moyen",
                        if (s.meanTimeUntilNextReviewMs < 0) "-"
                        else formatDuration(s.meanTimeUntilNextReviewMs)
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text("Répartition des scores", style = MaterialTheme.typography.titleSmall)
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

                if (s.waitTimeBuckets.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("Répartition des délais", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    val maxWaitCount = s.waitTimeBuckets.maxOf { it.count }.coerceAtLeast(1)
                    val waitColor = Color(0xFF2F86EB)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        s.waitTimeBuckets.forEach { bucket ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "${bucket.count}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            .fillMaxWidth(0.55f)
                                            .fillMaxHeight(bucket.count.toFloat() / maxWaitCount)
                                            .background(
                                                waitColor.copy(alpha = 0.75f),
                                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                            )
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    bucket.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
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
