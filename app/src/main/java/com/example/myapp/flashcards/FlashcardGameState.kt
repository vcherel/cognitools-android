package com.example.myapp.flashcards

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.myapp.flashcardRepository
import com.example.myapp.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val MAX_DIFFICULT_CARDS = 5

// One drawn card in this many comes up golden: double the wait on a win, a full wipe on a miss.
private const val GOLDEN_ODDS = 30

private data class DueState(val cards: List<FlashcardElement>, val totalCount: Int)

// The card drawn next, with its side and golden roll decided up front so an undo can bring it back as it was.
private data class Upcoming(val id: String, val showFront: Boolean, val golden: Boolean)

// A random-side card shows front or back with even odds each draw; a fixed-side one always shows the front.
private fun randomFrontSide(card: FlashcardElement, random: Random): Boolean =
    !card.randomSide || random.nextBoolean()

/** One review session: which cards are due, the card on screen, the answers and the undo of the last one. */
class FlashcardGameState(
    private val context: Context,
    private val listId: String,
    private val scope: CoroutineScope
) {
    private val repository = context.flashcardRepository
    private val sessionRandom = Random(System.currentTimeMillis())

    val isAllListsMode = listId == "all"

    // Read once: this screen is the only one writing cards while it is open, so its own copy stays
    // current, and an answer never waits on Room reading the whole table back.
    var cards by mutableStateOf<List<FlashcardElement>?>(null)
        private set
    var listNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var currentCard by mutableStateOf<FlashcardElement?>(null)
        private set
    var showDefinition by mutableStateOf(false)
    var showFront by mutableStateOf(true)
        private set
    var isGolden by mutableStateOf(false)
        private set

    // The card drawn next, picked one answer ahead so its picture is decoded before it shows.
    private var upcoming by mutableStateOf<Upcoming?>(null)

    // Rolling working set: at most MAX_DIFFICULT_CARDS difficult cards (score < 2) are in
    // play at once; a new one only enters when a slot frees up (see refillWorkingSet).
    private var activeDifficultCards by mutableStateOf<Set<String>>(emptySet())

    // Undo state: the card as it was before the last answer, and which side was shown
    private var lastCard by mutableStateOf<FlashcardElement?>(null)
    private var lastShowFront by mutableStateOf(true)
    private var lastGolden by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
        private set

    // Bumped when a waiting card comes due, so the due list (which reads the clock) recomputes
    private var dueTick by mutableIntStateOf(0)

    private val dueState by derivedStateOf {
        dueTick
        val due = cards.orEmpty().filter { isDue(it) }
        // Difficult cards only enter play through the working set
        val difficult = due.filter { it.score < 2 && it.id in activeDifficultCards }
        val easy = due.filter { it.score >= 2 }
        DueState(cards = difficult + easy, totalCount = due.size)
    }

    val dueCount: Int get() = dueState.totalCount

    suspend fun load() {
        if (isAllListsMode) listNames = repository.getListNames()
        cards = if (isAllListsMode) repository.getAllElements() else repository.getElements(listId)
        refillWorkingSet()
        pickIfIdle()
    }

    /** One sleep until the next waiting card comes due. Restart it whenever [cards] changes. */
    suspend fun wakeWhenCardsComeDue() {
        val loaded = cards ?: return
        while (true) {
            val now = System.currentTimeMillis()
            val nextDueAt = loaded.asSequence().map { it.nextReviewAt }.filter { it > now }.minOrNull() ?: return
            delay(nextDueAt - now)
            dueTick++
            refillWorkingSet()
            pickIfIdle()
        }
    }

    private fun store(card: FlashcardElement) {
        cards = cards?.map { if (it.id == card.id) card else it }
        scope.launch { repository.updateElement(card) }
    }

    // A member leaves the working set as soon as it graduates or stops being due (a win schedules
    // it minutes away), so a waiting card never holds a slot another due difficult card could use.
    private fun refillWorkingSet() {
        val difficultDue = cards.orEmpty().filter { it.score < 2 && isDue(it) }
        val kept = difficultDue.filter { it.id in activeDifficultCards }.map { it.id }
        val added = difficultDue
            .filter { it.id !in activeDifficultCards }
            .shuffled(sessionRandom)
            .take((MAX_DIFFICULT_CARDS - kept.size).coerceAtLeast(0))
            .map { it.id }
        activeDifficultCards = (kept + added).toSet()
    }

    private fun draw(card: FlashcardElement?) {
        currentCard = card
        val preset = upcoming?.takeIf { it.id == card?.id }
        upcoming = null
        if (card == null) return
        showFront = preset?.showFront ?: randomFrontSide(card, sessionRandom)
        isGolden = preset?.golden ?: (sessionRandom.nextInt(GOLDEN_ODDS) == 0)
        val next = dueState.cards.filter { it.id != card.id }.randomOrNull(sessionRandom) ?: return
        upcoming = Upcoming(next.id, randomFrontSide(next, sessionRandom), sessionRandom.nextInt(GOLDEN_ODDS) == 0)
        next.image?.let { prefetchCardImage(context, it) }
    }

    private fun pickIfIdle() {
        if (currentCard == null && dueState.cards.isNotEmpty()) draw(dueState.cards.random(sessionRandom))
    }

    fun answer(wasCorrect: Boolean) {
        currentCard?.let { card ->
            lastCard = card
            lastShowFront = showFront
            lastGolden = isGolden

            val quality = if (wasCorrect) 4 else 2
            val updatedCard = reviewCard(card, quality, sawAnswer = !showDefinition, golden = isGolden)

            if (isGolden) {
                val message = if (wasCorrect) {
                    "Golden card won: wait ×2, back in ${formatDuration(updatedCard.interval * 60_000L)}"
                } else {
                    "Golden card lost: progress reset"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }

            store(updatedCard)
            canUndo = true

            refillWorkingSet()

            showDefinition = false
            // The card just answered only comes straight back when it is the last one still due
            val availableCards = dueState.cards.filter { it.id != card.id }.ifEmpty { dueState.cards }

            draw(availableCards.firstOrNull { it.id == upcoming?.id } ?: availableCards.randomOrNull(sessionRandom))
        }
    }

    fun undo() {
        lastCard?.let { card ->
            store(card)

            // The card the answer had brought up comes next again, same side, same golden roll
            upcoming = currentCard?.let { Upcoming(it.id, showFront, isGolden) }
            currentCard = card
            showFront = lastShowFront
            isGolden = lastGolden
            showDefinition = false

            // If the answer had graduated the card, put it back in the working set
            if (card.score < 2) activeDifficultCards = activeDifficultCards + card.id

            canUndo = false
            lastCard = null
        }
    }
}

@Composable
fun rememberFlashcardGameState(listId: String): FlashcardGameState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { FlashcardGameState(context, listId, scope) }
}
