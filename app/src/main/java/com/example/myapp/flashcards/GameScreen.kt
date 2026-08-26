package com.example.myapp.flashcards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.myapp.rememberHeldPressed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.myapp.BackIconButton
import com.example.myapp.flashcardRepository
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.popBackStackOnce
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

private const val MAX_DIFFICULT_CARDS = 5

private data class DueState(val cards: List<FlashcardElement>, val totalCount: Int)

// A random-side card shows front or back with even odds each draw; a fixed-side one always shows the front.
private fun randomFrontSide(card: FlashcardElement, random: Random): Boolean =
    if (card.randomSide) random.nextBoolean() else true

@Composable
fun FlashcardGameScreen(listId: String, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isDarkMode = LocalIsDarkMode.current
    val greenColor = if (isDarkMode) Color(0xFF2D7516) else Color(0xFF56C92F)
    val redColor = if (isDarkMode) Color(0xFF771212) else Color(0xFFDE1E1E)

    val repository = context.flashcardRepository

    val isAllListsMode = listId == "all"

    val sessionRandom = remember { Random(System.currentTimeMillis()) }

    val allElements by if (isAllListsMode) {
        repository.observeAllElements().collectAsState(initial = emptyList())
    } else {
        repository.observeElements(listId).collectAsState(initial = emptyList())
    }

    var currentCard by remember { mutableStateOf<FlashcardElement?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDefinition by remember { mutableStateOf(false) }
    var cardOffset by remember { mutableFloatStateOf(0f) }
    var isProcessingSwipe by remember { mutableStateOf(false) }
    var showFront by remember { mutableStateOf(true) }
    // Rolling working set: at most MAX_DIFFICULT_CARDS difficult cards (score < 2) are in
    // play at once; a new one only enters when a member graduates to score >= 2.
    var activeDifficultCards by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localUpdates by remember { mutableStateOf<Map<String, FlashcardElement>>(emptyMap()) }
    var listName by remember { mutableStateOf("") }

    // Undo state: the card as it was before the last answer, and which side was shown
    var lastCard by remember { mutableStateOf<FlashcardElement?>(null) }
    var lastShowFront by remember { mutableStateOf(true) }
    var canUndo by remember { mutableStateOf(false) }

    var hasNavigatedBack by remember { mutableStateOf(false) }

    LaunchedEffect(currentCard) {
        val card = currentCard
        listName = if (isAllListsMode && card != null) repository.getListNameById(card.listId) else ""
    }

    val dueState by remember {
        derivedStateOf {
            val updated = allElements.map { element ->
                localUpdates[element.id] ?: element
            }

            val due = updated.filter { isDue(it) }
            // Difficult cards only enter play through the working set
            val difficult = due.filter { it.score < 2 && it.id in activeDifficultCards }
            val easy = due.filter { it.score >= 2 }
            DueState(cards = difficult + easy, totalCount = due.size)
        }
    }

    LaunchedEffect(allElements) {
        if (allElements.isEmpty()) return@LaunchedEffect

        isLoading = false

        // Keep currently active cards that are still difficult
        val currentActiveDifficultCards = allElements.filter {
            it.id in activeDifficultCards && it.score < 2
        }

        // Top up the working set to MAX_DIFFICULT_CARDS with due difficult cards
        val needed = (MAX_DIFFICULT_CARDS - currentActiveDifficultCards.size).coerceAtLeast(0)
        val availableToAdd = allElements.filter {
            it.score < 2 && isDue(it) && it.id !in activeDifficultCards
        }.shuffled(sessionRandom)

        if (needed > 0 && availableToAdd.isNotEmpty()) {
            activeDifficultCards = (currentActiveDifficultCards.map { it.id } +
                    availableToAdd.take(needed).map { it.id }).toSet()
        }

        if (currentCard == null && dueState.cards.isNotEmpty()) {
            val picked = dueState.cards.random(sessionRandom)
            currentCard = picked
            showFront = randomFrontSide(picked, sessionRandom)
        }
    }

    fun handleAnswer(wasCorrect: Boolean) {
        if (isProcessingSwipe) return
        isProcessingSwipe = true

        currentCard?.let { card ->
            lastCard = card
            lastShowFront = showFront

            val quality = if (wasCorrect) 4 else 2
            val updatedCard = reviewCard(card, quality, sawAnswer = !showDefinition)

            localUpdates = localUpdates + (card.id to updatedCard)
            canUndo = true

            scope.launch {
                repository.updateElement(updatedCard)
            }

            // Graduation: the card leaves the working set and a new difficult card enters
            if (wasCorrect && updatedCard.score >= 2 && card.id in activeDifficultCards) {
                val remaining = activeDifficultCards - card.id
                val replacements = allElements
                    .map { localUpdates[it.id] ?: it }
                    .filter { it.score < 2 && isDue(it) && it.id !in remaining }
                    .shuffled(sessionRandom)
                    .take((MAX_DIFFICULT_CARDS - remaining.size).coerceAtLeast(0))
                activeDifficultCards = remaining + replacements.map { it.id }
            }

            showDefinition = false
            val availableCards = dueState.cards.filter { it.id != card.id }

            currentCard = if (availableCards.isNotEmpty()) {
                val next = availableCards.random(sessionRandom)
                showFront = randomFrontSide(next, sessionRandom)
                next
            } else null
            isProcessingSwipe = false
        }
    }

    fun handleUndo() {
        lastCard?.let { card ->
            localUpdates = localUpdates + (card.id to card)

            scope.launch {
                repository.updateElement(card)
            }

            currentCard = card
            showFront = lastShowFront
            showDefinition = false

            // If the answer had graduated the card, put it back in the working set
            if (card.score < 2) activeDifficultCards = activeDifficultCards + card.id

            canUndo = false
            lastCard = null
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackIconButton(
                    onBack = {
                        if (!hasNavigatedBack) {
                            hasNavigatedBack = true
                            navController.popBackStackOnce()
                        }
                    }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cartes à réviser: ${dueState.totalCount}",
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Undo button
                    IconButton(
                        onClick = { handleUndo() },
                        enabled = canUndo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Annuler",
                            tint = if (canUndo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            val card = currentCard
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else if (card != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                            .graphicsLayer {
                                translationX = cardOffset
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        when {
                                            cardOffset < -200 -> handleAnswer(true)
                                            cardOffset > 200 -> handleAnswer(false)
                                        }
                                        cardOffset = 0f
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    cardOffset += dragAmount.x
                                }
                            }
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (!showDefinition) showDefinition = true
                            },
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        CardFace(
                            card = card,
                            showFront = showFront,
                            showDefinition = showDefinition,
                            listName = if (isAllListsMode) listName else null
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        AnswerButton(
                            label = "YES",
                            icon = Icons.Default.Check,
                            color = greenColor,
                            shadowColor = Color(0xFF2E7D32),
                            onClick = { handleAnswer(true) }
                        )

                        AnswerButton(
                            label = "NO",
                            icon = Icons.Default.Close,
                            color = redColor,
                            shadowColor = Color(0xFF7A0707),
                            onClick = { handleAnswer(false) }
                        )
                    }
                }
            } else {
                AllReviewedScreen(
                    greenColor = greenColor,
                    showPlainBack = !isAllListsMode,
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
 * What the card shows: the side being asked, the other one once revealed, its score in the corner,
 * and, when playing across every list ([listName] non-null), which list it came from.
 */
/**
 * Washes the edge the card is being dragged towards in its verdict's color, deepening with the
 * drag. [offset] is read in the draw phase, not in composition, so dragging only invalidates the
 * draw and never recomposes the card.
 */
private fun Modifier.swipeVerdictGradient(offset: () -> Float, green: Color, red: Color): Modifier =
    drawBehind {
        val current = offset()
        if (current == 0f) return@drawBehind
        val progress = (abs(current) / 200f).coerceIn(0f, 1f)
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

@Composable
private fun CardFace(
    card: FlashcardElement,
    showFront: Boolean,
    showDefinition: Boolean,
    listName: String?
) {
    val score = card.score.toInt()
    val scoreColor = scoreColor(score)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = if (showFront != showDefinition) card.name else card.definition,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
            if (showDefinition) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = if (showFront) card.name else card.definition,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
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

        if (listName != null) {
            Text(
                text = listName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.Gray,
                    fontStyle = FontStyle.Italic
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
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
            text = "Félicitations !",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Vous avez révisé toutes les cartes disponibles",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))
        MyButton(
            text = "Retour aux listes",
            onClick = onBackToLists,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        )

        if (showPlainBack) {
            Spacer(Modifier.height(32.dp))
            MyButton(
                text = "Retour",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
        }
    }
}

@Composable
private fun RowScope.AnswerButton(
    label: String,
    icon: ImageVector,
    color: Color,
    shadowColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by rememberHeldPressed(interactionSource)

    Box(
        modifier = Modifier
            .weight(1f)
            .height(140.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 6.dp)
                .background(shadowColor, RoundedCornerShape(16.dp))
        )

        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .scale(if (pressed) 0.95f else 1f),
            colors = ButtonDefaults.buttonColors(containerColor = color),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(0.dp),
            interactionSource = interactionSource
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    label,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}