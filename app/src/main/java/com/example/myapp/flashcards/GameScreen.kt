package com.example.myapp.flashcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapp.MyButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

@Composable
fun FlashcardGameScreen(listId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val key = stringPreferencesKey("elements_$listId")
    val elementsJson by context.dataStore.data
        .map { prefs -> prefs[key] }
        .collectAsState(initial = null)

    var allElements by remember { mutableStateOf<List<FlashcardElement>>(emptyList()) }
    var currentCard by remember { mutableStateOf<FlashcardElement?>(null) }
    var showDefinition by remember { mutableStateOf(false) }
    var cardOffset by remember { mutableFloatStateOf(0f) }
    var isProcessingSwipe by remember { mutableStateOf(false) }
    var showFront by remember { mutableStateOf(true) }
    var activeDifficultCards by remember { mutableStateOf<Set<String>>(emptySet()) }

    val dueCards by remember(allElements, activeDifficultCards) {
        derivedStateOf {
            allElements.filter { card ->
                if (card.id in activeDifficultCards && card.score > 2) {
                    true
                } else {
                    isDue(card) && card.totalWins > 0
                }
            }
        }
    }

    BackHandler {
        onBack()
    }

    // Load and filter due cards
    LaunchedEffect(elementsJson) {
        elementsJson?.let { jsonString ->
            // Parse JSON string into FlashcardElement objects
            val loaded = FlashcardElement.listFromJsonString(jsonString)
            allElements = loaded

            // Get all difficult cards
            val difficultCards = loaded.filter { it.score > 2 }

            // Keep currently active cards that are still difficult
            val currentActiveDifficultCards = activeDifficultCards.mapNotNull { activeId ->
                loaded.find { it.id == activeId }
            }.filter { it.score > 2 }

            // Calculate how many more difficult cards we need to reach 10
            val needed = (10 - currentActiveDifficultCards.size).coerceAtLeast(0)

            // Pick difficult cards not already active, up to the needed amount
            val availableToAdd = difficultCards
                .filter { it.id !in currentActiveDifficultCards.map { c -> c.id }.toSet() }
                .take(needed)

            // Update active difficult cards set if there are difficult cards to add
            if (availableToAdd.isNotEmpty()) {
                activeDifficultCards = (currentActiveDifficultCards.map { it.id } + availableToAdd.map { it.id }).toSet()
            }

            // Pick a random current card if none is selected yet
            if (currentCard == null && dueCards.isNotEmpty()) {
                currentCard = dueCards.random()
            }
        }
    }

    fun updateCards(card: FlashcardElement, quality: Int /* quality = 2 if lost, 4 if won */): FlashcardElement {
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
            // If we fail, the card comes again quickly
            quality < 3 -> {
                val totalCards = dueCards.size.coerceAtLeast(1)
                val baseProbability = ((2.5 - newScore) / 2.5).coerceIn(0.0, 1.0)
                val sizeFactor = (totalCards / 90.0).coerceIn(0.01, 1.0) // 1% at few cards → 90% at 100+
                val probability = baseProbability * sizeFactor

                when {
                    (newScore < 2.5 && Math.random() < probability) -> 60 + Math.random() * 120
                    (Math.random() < 0.33) -> newScore.coerceAtLeast(1.0)
                    else -> 0.0
                }
            }
            // On the first win we have to wait 6 minutes
            newReps == 1 -> 6.0
            else -> {
                // Low score have less chance to have times 2 multiplier
                val randomFactor = Math.random().pow(1.0 + ((10 - newScore) / 10.0))
                val randomMultiplier = 0.8 + randomFactor * 1.2 // Multiplier from 0.8 to 2 on the time
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

    // Function to save updated elements
    fun saveElements(updated: List<FlashcardElement>) {
        scope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[key] = FlashcardElement.listToJsonString(updated)
            }
        }
    }

    // Handle card swipe/answer
    fun handleAnswer(wasCorrect: Boolean) {
        if (isProcessingSwipe) return
        isProcessingSwipe = true

        currentCard?.let { card ->
            val quality = if (wasCorrect) 4 else 2 // 4 = correct, 2 = incorrect
            val updatedCard = updateCards(card, quality)

            // Update the card in allElements
            val updatedElements = allElements.map {
                if (it.id == card.id) updatedCard else it
            }
            allElements = updatedElements
            saveElements(updatedElements)

            // Remove the card from active difficult cards if the score is high enough
            if (wasCorrect && card.score > 2) {
                // Remove this card from active difficult cards
                activeDifficultCards = activeDifficultCards - card.id

                // Find remaining difficult cards (never won)
                val remainingDifficultCards = updatedElements.filter { it.score > 2 }

                // Add difficult cards until we have 10 active or run out of difficult cards
                val needed = 10 - activeDifficultCards.size
                val available = remainingDifficultCards.filter { it.id !in activeDifficultCards }.take(needed)

                if (available.isNotEmpty()) {
                    activeDifficultCards = activeDifficultCards + available.map { it.id }.toSet()
                }
            }

            // Move to next card (exclude current card)
            showDefinition = false
            val availableCards = dueCards.filter { it.id != card.id }
            currentCard = if (availableCards.isNotEmpty()) {
                val card = availableCards.random()
                showFront = Random.nextBoolean() // Randomly show front or back
                card
            } else if (dueCards.isNotEmpty()) {
                val card = dueCards.random()
                showFront = Random.nextBoolean()
                card
            } else {
                null
            }

            isProcessingSwipe = false
        }
    }

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

            // Shadow at corners
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (cardOffset < 0) {
                            Brush.radialGradient(
                                colors = listOf(
                                    shadowColor.copy(alpha = swipeProgress * 0.8f),
                                    shadowColor.copy(alpha = swipeProgress * 0.6f),
                                    shadowColor.copy(alpha = swipeProgress * 0.2f),
                                    Color.Transparent
                                ),
                                center = Offset(0f, 0.5f),
                                radius = 800f
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(
                                    shadowColor.copy(alpha = swipeProgress * 0.8f),
                                    shadowColor.copy(alpha = swipeProgress * 0.6f),
                                    shadowColor.copy(alpha = swipeProgress * 0.2f),
                                    Color.Transparent
                                ),
                                center = Offset(Float.POSITIVE_INFINITY, 0.5f),
                                radius = 800f
                            )
                        }
                    )
            )
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
            if (currentCard != null) {
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
                            .offset { IntOffset(cardOffset.toInt(), 0) }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        when {
                                            cardOffset < -200 -> handleAnswer(true) // Swipe left = Correct
                                            cardOffset > 200 -> handleAnswer(false) // Swipe right = Incorrect
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

                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
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

                            // Score circle at top right
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
                                        shadow = if ((currentCard?.score?.toInt() ?: 0) <= 3 || currentCard?.score?.toInt() == 10
                                        ) null else Shadow(
                                            color = Color.Black,
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

                    // Alternative buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                        ) {
                            // Shadow layer
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 6.dp)
                                    .background(
                                        Color(0xFF2E7D32),
                                        RoundedCornerShape(16.dp)
                                    )
                            )

                            // Main button
                            Button(
                                onClick = { handleAnswer(true) },
                                modifier = Modifier.fillMaxSize(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = greenColor
                                ),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
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

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                        ) {
                            // Shadow layer
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 6.dp)
                                    .background(
                                        Color(0xFF7A0707),
                                        RoundedCornerShape(16.dp)
                                    )
                            )

                            // Main button
                            Button(
                                onClick = { handleAnswer(false) },
                                modifier = Modifier.fillMaxSize(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = redColor
                                ),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
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
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                    ) {
                        // Shadow layer
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(y = 6.dp)
                                .background(Color(0xFF2E7D32), RoundedCornerShape(24.dp))
                        )

                        // Main icon background
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