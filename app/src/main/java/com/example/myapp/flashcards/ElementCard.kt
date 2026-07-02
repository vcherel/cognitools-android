package com.example.myapp.flashcards

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One flashcard row in the detail list. [listName] is shown when browsing all lists at once. */
@Composable
fun FlashcardElementCard(
    element: FlashcardElement,
    listName: String?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (!listName.isNullOrBlank()) {
                Text(
                    text = listName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    var expandedName by remember { mutableStateOf(false) }
                    Text(
                        text = element.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (expandedName) Int.MAX_VALUE else 1,
                        overflow = if (expandedName) TextOverflow.Visible else TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expandedName = !expandedName }
                    )

                    var expandedDefinition by remember { mutableStateOf(false) }
                    Text(
                        text = element.definition,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expandedDefinition) Int.MAX_VALUE else 1,
                        overflow = if (expandedDefinition) TextOverflow.Visible else TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expandedDefinition = !expandedDefinition }
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Top,
                    modifier = Modifier.width(IntrinsicSize.Min)
                ) {
                    val timeUntilReview = remember(element.lastReview, element.interval) {
                        val nextReviewTime = element.lastReview + (element.interval * 60_000L)
                        formatDuration(nextReviewTime - System.currentTimeMillis())
                    }

                    Text(
                        timeUntilReview,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDue(element)) Color(0xFF009900) else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ScoreCircle(score = element.score.toInt())

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Éditer",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Supprimer",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = onReset, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Réinitialiser",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreCircle(score: Int) {
    val color = scoreColor(score)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(24.dp)
            .border(width = 2.dp, color = color, shape = CircleShape)
    ) {
        Text(
            "$score",
            style = MaterialTheme.typography.bodySmall.copy(
                // Light middle-of-scale colors need a halo to stay readable
                shadow = if (score <= 3 || score == 10) null else Shadow(
                    offset = Offset(0f, 0f),
                    blurRadius = 1f
                )
            ),
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
