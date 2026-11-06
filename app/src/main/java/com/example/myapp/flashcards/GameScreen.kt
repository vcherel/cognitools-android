package com.example.myapp.flashcards

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.myapp.BackIconButton
import com.example.myapp.formatDuration
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.popBackStackOnce
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

private const val SWIPE_THRESHOLD_PX = 200f

private val Gold = Color(0xFFFFC107)

@Composable
fun FlashcardGameScreen(listId: String, navController: NavController) {
    val scope = rememberCoroutineScope()

    val isDarkMode = LocalIsDarkMode.current
    val greenColor = if (isDarkMode) Color(0xFF2D7516) else Color(0xFF56C92F)
    val redColor = if (isDarkMode) Color(0xFF771212) else Color(0xFFDE1E1E)

    val game = rememberFlashcardGameState(listId)
    var cardOffset by remember { mutableFloatStateOf(0f) }
    var isSliding by remember { mutableStateOf(false) }

    LaunchedEffect(listId) { game.load() }
    LaunchedEffect(game.cards) { game.wakeWhenCardsComeDue() }

    // Past the threshold the card slides off on its verdict's side before the next one shows,
    // short of it the card springs back.
    fun release(width: Float) {
        val verdict = when {
            cardOffset < -SWIPE_THRESHOLD_PX -> true
            cardOffset > SWIPE_THRESHOLD_PX -> false
            else -> null
        }
        isSliding = true
        scope.launch {
            val target = when (verdict) {
                true -> -width * 1.3f
                false -> width * 1.3f
                null -> 0f
            }
            animate(cardOffset, target, animationSpec = tween(140)) { value, _ -> cardOffset = value }
            if (verdict != null) game.answer(verdict)
            cardOffset = 0f
            isSliding = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .swipeVerdictGradient({ cardOffset }, greenColor, redColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackIconButton(onBack = { navController.popBackStackOnce() })

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cards to review: ${game.dueCount}",
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(
                        onClick = { if (!isSliding) game.undo() },
                        enabled = game.canUndo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (game.canUndo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val card = game.currentCard
            if (game.cards == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else if (card != null) {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                        .height(510.dp)
                        .graphicsLayer {
                            translationX = cardOffset
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (!isSliding) release(size.width.toFloat()) },
                                onDragCancel = { if (!isSliding) release(size.width.toFloat()) }
                            ) { change, dragAmount ->
                                change.consume()
                                if (!isSliding) cardOffset += dragAmount
                            }
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            if (!game.showDefinition) game.showDefinition = true
                        },
                    border = if (game.isGolden) BorderStroke(4.dp, Gold) else null,
                    // A deep shadow under a card this big is redrawn every frame of the drag.
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    CardFace(
                        card = card,
                        showFront = game.showFront,
                        showDefinition = game.showDefinition,
                        listName = if (game.isAllListsMode) game.listNames[card.listId].orEmpty() else null,
                        isGolden = game.isGolden
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CardStats(card = card, greenColor = greenColor, redColor = redColor)
                }
            } else {
                AllReviewedScreen(
                    greenColor = greenColor,
                    showPlainBack = !game.isAllListsMode,
                    onBackToLists = {
                        navController.navigate("lists") { popUpTo("lists") { inclusive = true } }
                    },
                    onBack = { navController.popBackStackOnce() }
                )
            }
        }
    }
}

/**
 * Washes the edge the card is being dragged towards in its verdict's color, deepening with the
 * drag. [offset] is read in the draw phase, not in composition, so dragging only invalidates the
 * draw and never recomposes the card.
 */
private fun Modifier.swipeVerdictGradient(offset: () -> Float, green: Color, red: Color): Modifier =
    drawBehind {
        val current = offset()
        if (current == 0f) return@drawBehind
        val progress = (abs(current) / SWIPE_THRESHOLD_PX).coerceIn(0f, 1f)
        val tint = (if (current < 0) green else red).copy(alpha = progress * 0.6f)
        // Grows with the swipe, up to half the screen.
        val width = min(abs(current) * 2f, size.width / 2f)
        val brush = if (current < 0) {
            Brush.horizontalGradient(listOf(tint, Color.Transparent), startX = 0f, endX = width)
        } else {
            Brush.horizontalGradient(
                listOf(Color.Transparent, tint),
                startX = size.width - width,
                endX = size.width
            )
        }
        drawRect(brush)
    }

/**
 * What the card shows: the side being asked, the other one once revealed, its score in the corner,
 * and, when playing across every list ([listName] non-null), which list it came from.
 */
@Composable
private fun CardFace(
    card: FlashcardElement,
    showFront: Boolean,
    showDefinition: Boolean,
    listName: String?,
    isGolden: Boolean
) {
    val score = card.score.toInt()
    val scoreColor = scoreColor(score)
    val isMap = card.image != null && isMapImage(card.image)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            // The picture belongs to the front, so it shows whenever the front side is visible.
            if (card.image != null && (showFront || showDefinition)) {
                // Pinned below the score at a fixed size, so it stays put on reveal and when it loads.
                FullSizeCardImage(
                    image = card.image,
                    modifier = if (isMap) {
                        Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 56.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    } else {
                        Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 90.dp)
                            .fillMaxWidth()
                            .height(200.dp)
                    }
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = if (isMap) 8.dp else 24.dp)
            ) {
                val asked = if (showFront != showDefinition) card.name else card.definition
                if (asked.isNotBlank()) {
                    Text(
                        text = asked,
                        style = if (isMap) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                }
                val revealed = if (showFront) card.name else card.definition
                if (showDefinition && revealed.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = revealed,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (listName != null) {
                Text(
                    text = listName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.Gray,
                        fontStyle = FontStyle.Italic
                    ),
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(36.dp)
                .border(width = 3.dp, color = scoreColor, shape = CircleShape)
        ) {
            Text(
                "$score",
                style = MaterialTheme.typography.bodyLarge.copy(
                    // A glow on the middle scores only: the extremes read clearly on their own.
                    shadow = if (score <= 3 || score == 10) null
                    else Shadow(offset = Offset(0f, 0f), blurRadius = 4f)
                ),
                color = scoreColor,
                fontWeight = FontWeight.Bold
            )
        }

        if (isGolden) {
            Text(
                text = "Golden card",
                style = MaterialTheme.typography.titleMedium,
                color = Gold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }
    }
}

/** The card's record under it: right answers out of all, as a bar, its current streak and when it was last seen. */
@Composable
internal fun CardStats(card: FlashcardElement, greenColor: Color, redColor: Color) {
    val played = card.totalWins + card.totalLosses
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "${card.totalWins}/$played",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (played == 0) "New card" else "${card.totalWins * 100 / played}% right",
                style = MaterialTheme.typography.titleMedium,
                color = muted,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (played == 0) MaterialTheme.colorScheme.surfaceVariant else redColor)
        ) {
            if (card.totalWins > 0) {
                Box(
                    Modifier
                        .weight(card.totalWins.toFloat())
                        .fillMaxHeight()
                        .background(greenColor)
                )
            }
            if (card.totalLosses > 0) Spacer(Modifier.weight(card.totalLosses.toFloat()))
        }
        if (played > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                            append("${card.repetitions}")
                        }
                        append(" in a row")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted
                )
                Text(
                    text = buildAnnotatedString {
                        append("last seen ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                            append(formatDuration(System.currentTimeMillis() - card.lastReview))
                        }
                        append(" ago")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted
                )
            }
        }
    }
}

/** Nothing left to review. [showPlainBack] adds a second way out for a single list's game. */
@Composable
private fun AllReviewedScreen(
    greenColor: Color,
    showPlainBack: Boolean,
    onBackToLists: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(100.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = 6.dp)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(24.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(greenColor, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(80.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Well done!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "You reviewed every card available",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))
        MyButton(
            text = "Back to the lists",
            onClick = onBackToLists,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        )

        if (showPlainBack) {
            Spacer(Modifier.height(32.dp))
            MyButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                secondary = true
            )
        }
    }
}