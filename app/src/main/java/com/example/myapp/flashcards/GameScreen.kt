package com.example.myapp.flashcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.myapp.MyButton
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

const val MAX_DIFFICULT_CARDS = 5

@Composable
fun FlashcardGameScreen(listId: String, navController: NavController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Create repository instance
    val repository = remember { FlashcardRepository(context) }

    // Observe elements from repository
    val allElements by repository.observeElements(listId).collectAsState(initial = emptyList())

    var currentCard by remember { mutableStateOf<FlashcardElement?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDefinition by remember { mutableStateOf(false) }
    var cardOffset by remember { mutableFloatStateOf(0f) }
    var isProcessingSwipe by remember { mutableStateOf(false) }
    var showFront by remember { mutableStateOf(true) }
    var activeDifficultCards by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localUpdates by remember { mutableStateOf<Map<String, FlashcardElement>>(emptyMap()) } // To update UI faster than database

    val dueCards by remember(allElements, localUpdates) {
        derivedStateOf {
            val updated = allElements.map { element ->
                localUpdates[element.id] ?: element
            }

            val due = updated.filter { isDue(it) }
            val difficult = due.filter { it.score < 2 }.take(MAX_DIFFICULT_CARDS)
            val easy = due.filter { it.score >= 2 }
            difficult + easy
        }
    }

    BackHandler {
        onBack()
    }

    // Load and filter due cards
    LaunchedEffect(allElements) {
        if (allElements.isEmpty()) return@LaunchedEffect

        isLoading = false

        // Keep currently active cards that are still difficult
        val currentActiveDifficultCards = allElements.filter {
            it.id in activeDifficultCards && it.score < 2
        }

        // Calculate how many more difficult cards we need to reach MAX_DIFFICULT_CARDS
        val needed = (MAX_DIFFICULT_CARDS - currentActiveDifficultCards.size).coerceAtLeast(0)
        val availableToAdd = allElements.filter {
            it.score < 2 && it.id !in activeDifficultCards
        }

        if (needed > 0 && availableToAdd.isNotEmpty()) {
            activeDifficultCards = (currentActiveDifficultCards.map { it.id } +
                    availableToAdd.take(needed).map { it.id }).toSet()
        }

        // Pick a random current card if none is selected yet
        if (currentCard == null && dueCards.isNotEmpty()) {
            currentCard = dueCards.random()
            showFront = Random.nextBoolean()
        }
    }

    fun updateCards(card: FlashcardElement, quality: Int): FlashcardElement {
        // Update ease factor
        var newEF = card.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
        if (newEF < 1.3) newEF = 1.3

        // Bonus if didn't see answer
        if (!showDefinition) {
            newEF *= 1.05
        }

        // Update repetitions
        val newReps = if (quality >= 3) card.repetitions + 1 else 0

        // Update total wins/losses
        val newWins = card.totalWins + if (quality >= 3) 1 else 0
        val newLosses = card.totalLosses + if (quality < 3) 1 else 0

        // Calculate new score
        val newScore = ((newWins.toDouble() / (newWins + newLosses)) * 10).coerceIn(0.0, 10.0)

        // Update interval
        val newInterval = when {
            // If we fail, the card comes again quickly (25% chance to wait a bit depending on score)
            quality < 3 -> { if (Math.random() < 0.25) newScore.coerceAtLeast(1.0) else 0.0 }

            // On the first win we have to wait 6 minutes
            newReps == 1 -> 6.0
            else -> {
                // Low score have less chance to have times 2 multiplier
                val randomFactor = Math.random().pow(1.0 + ((10 - newScore) / 10.0))
                val randomMultiplier = 0.8 + randomFactor * 1.2
                card.interval * newEF * randomMultiplier
            }
        }

        return card.copy(
            easeFactor = newEF,
            interval = newInterval.toInt(),
            repetitions = newReps,
            lastReview = System.currentTimeMillis(),
            totalWins = newWins,
            totalLosses = newLosses,
            score = newScore
        )
    }

    fun handleAnswer(wasCorrect: Boolean) {
        if (isProcessingSwipe) return
        isProcessingSwipe = true

        currentCard?.let { card ->
            val quality = if (wasCorrect) 4 else 2
            val updatedCard = updateCards(card, quality)

            localUpdates = localUpdates + (card.id to updatedCard)

            scope.launch {
                repository.updateElement(listId, updatedCard)
            }

            if (wasCorrect && card.score >= 2) {
                activeDifficultCards = activeDifficultCards - card.id

                val remainingDifficult = allElements.filter {
                    it.score < 2 && it.id !in activeDifficultCards
                }
                val needed = MAX_DIFFICULT_CARDS - activeDifficultCards.size
                if (needed > 0) {
                    activeDifficultCards = activeDifficultCards +
                            remainingDifficult.take(needed).map { it.id }.toSet()
                }
            }

            showDefinition = false
            val availableCards = dueCards.filter { it.id != card.id }
            currentCard = if (availableCards.isNotEmpty()) {
                val card = availableCards.random()
                showFront = Random.nextBoolean()
                card
            } else {
                null
            }
            isProcessingSwipe = false
        }
    }

    // Clear localUpdates when allElements updates to avoid memory buildup
    LaunchedEffect(allElements) {
        if (localUpdates.isNotEmpty()) {
            localUpdates = emptyMap()
        }
    }

    // Box that display gradients when swiping
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val greenColor = Color(0xFF56C92F)
        val redColor = Color(0xFFDE1E1E)

        if (cardOffset != 0f) {
            val swipeProgress = (abs(cardOffset) / 200f).coerceIn(0f, 1f)
            val shadowColor = if (cardOffset < 0) greenColor else redColor

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val containerWidth = constraints.maxWidth.toFloat()

                // Gradient width grows with swipe, max half the screen
                val gradientWidth = min(abs(cardOffset) * 2f, containerWidth / 2f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (cardOffset < 0) {
                                // Left swipe (green)
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        shadowColor.copy(alpha = swipeProgress * 0.4f),
                                        Color.Transparent
                                    ),
                                    startX = 0f,
                                    endX = gradientWidth
                                )
                            } else {
                                // Right swipe (red)
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        shadowColor.copy(alpha = swipeProgress * 0.4f)
                                    ),
                                    startX = containerWidth - gradientWidth,
                                    endX = containerWidth
                                )
                            }
                        )
                )
            }
        }

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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    text = "Cartes à réviser: ${dueCards.size}",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(32.dp))

            // Main content
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else if (currentCard != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Card
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
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        val scoreColor = when (currentCard?.score?.toInt() ?: 0) {
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

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Text on the card
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Text(
                                    text = if (showFront != showDefinition) currentCard!!.name else currentCard!!.definition,
                                    style = MaterialTheme.typography.headlineMedium,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold
                                )

                                if (showDefinition) {
                                    Spacer(Modifier.height(24.dp))
                                    Text(
                                        text = if (showFront) currentCard!!.name else currentCard!!.definition,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Score circle
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                                    .size(36.dp)
                                    .border(width = 3.dp, color = scoreColor, shape = CircleShape)
                            ) {
                                Text(
                                    "${currentCard?.score?.toInt() ?: 0}",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        shadow = if ((currentCard?.score?.toInt() ?: 0) <= 3 ||
                                            currentCard?.score?.toInt() == 10) null
                                        else Shadow(
                                            offset = Offset(0f, 0f),
                                            blurRadius = 4f
                                        )
                                    ),
                                    color = scoreColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))

                    // Answer buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        val yesInteractionSource = remember { MutableInteractionSource() }
                        val yesPressed by yesInteractionSource.collectIsPressedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 6.dp)
                                    .background(Color(0xFF2E7D32), RoundedCornerShape(16.dp))
                            )

                            Button(
                                onClick = { handleAnswer(true) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(if (yesPressed) 0.95f else 1f),
                                colors = ButtonDefaults.buttonColors(containerColor = greenColor),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp),
                                interactionSource = yesInteractionSource
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "YES",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        val noInteractionSource = remember { MutableInteractionSource() }
                        val noPressed by noInteractionSource.collectIsPressedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 6.dp)
                                    .background(Color(0xFF7A0707), RoundedCornerShape(16.dp))
                            )

                            Button(
                                onClick = { handleAnswer(false) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(if (noPressed) 0.95f else 1f),
                                colors = ButtonDefaults.buttonColors(containerColor = redColor),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp),
                                interactionSource = noInteractionSource
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "NO",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // No cards due
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
                        onClick = {
                            navController.navigate("lists") {
                                popUpTo("lists") { inclusive = true }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    )

                    Spacer(Modifier.height(32.dp))
                    MyButton(
                        text = "Retour",
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    )

                }
            }
        }
    }
}