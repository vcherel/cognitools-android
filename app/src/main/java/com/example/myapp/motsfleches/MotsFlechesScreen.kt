package com.example.myapp.motsfleches

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

private const val KEYBOARD_ROW_1 = "QWERTYUIOP"
private const val KEYBOARD_ROW_2 = "ASDFGHJKL"
private const val KEYBOARD_ROW_3 = "ZXCVBNM"

/**
 * The Mots fleches tool: one grid at a time, picked up where it was left. Nothing is checked or
 * given away unless asked for, and finishing a grid hands over the next one.
 */
@Composable
fun MotsFlechesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<PuzzleState?>(null) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var explained by remember { mutableStateOf<Slot?>(null) }
    var confirmNewGrid by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun apply(next: PuzzleState) {
        state = next
        scope.launch { MotsFlechesStore.save(context, next) }
        if (next.isSolved) solved = true
    }

    fun start(fresh: PuzzleState) {
        state = fresh
        selection = firstSelection(fresh.puzzle)
        scope.launch { MotsFlechesStore.prepareNext(context) }
    }

    LaunchedEffect(Unit) {
        start(MotsFlechesStore.current(context))
    }

    val current = state
    val slot = current?.let { puzzleSlot(it.puzzle, selection) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Mots fléchés",
            onBack = onBack,
            titleSuffix = {
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Nouvelle grille") },
                            onClick = {
                                menuOpen = false
                                confirmNewGrid = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Effacer la grille") },
                            onClick = {
                                menuOpen = false
                                current?.let { apply(PuzzleState.blank(it.puzzle)) }
                            }
                        )
                    }
                }
            }
        )

        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        PuzzleGrid(
            state = current,
            selection = selection,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            onSelect = { row, col -> selection = selectionFor(current.puzzle, row, col, selection) }
        )

        ClueBar(
            slot = slot,
            onCheck = {
                slot ?: return@ClueBar
                AppSnackbar.show(
                    if (current.isCorrect(slot)) "Ce mot est bon" else "Ce mot n'est pas encore bon"
                )
            },
            onReveal = {
                slot ?: return@ClueBar
                apply(revealSlot(current, slot))
                explained = slot
            }
        )

        LetterKeyboard(
            onLetter = { letter ->
                val target = selection ?: return@LetterKeyboard
                val cell = current.puzzle.index(target.row, target.col)
                apply(current.withLetter(cell, letter).copy(revealed = current.revealed - cell))
                selection = advance(current.puzzle, target)
            },
            onBackspace = {
                val target = selection ?: return@LetterKeyboard
                val cell = current.puzzle.index(target.row, target.col)
                if (current.letterAt(cell) != Puzzle.EMPTY) {
                    apply(current.withLetter(cell, Puzzle.EMPTY).copy(revealed = current.revealed - cell))
                } else {
                    val previous = retreat(current.puzzle, target)
                    val previousCell = current.puzzle.index(previous.row, previous.col)
                    apply(
                        current.withLetter(previousCell, Puzzle.EMPTY)
                            .copy(revealed = current.revealed - previousCell)
                    )
                    selection = previous
                }
            }
        )
    }

    explained?.let { revealedSlot ->
        ExplanationDialog(slot = revealedSlot, onDismiss = { explained = null })
    }

    if (confirmNewGrid) {
        AppDialog(onDismiss = { confirmNewGrid = false }) {
            Text("Abandonner cette grille ?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "La grille en cours sera remplacée par une nouvelle.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(
                    text = "Annuler",
                    modifier = Modifier.weight(1f).height(50.dp),
                    fontSize = 14.sp,
                    onClick = { confirmNewGrid = false }
                )
                MyButton(
                    text = "Nouvelle grille",
                    modifier = Modifier.weight(1f).height(50.dp),
                    fontSize = 14.sp,
                    onClick = {
                        confirmNewGrid = false
                        state = null
                        scope.launch { start(MotsFlechesStore.newGrid(context)) }
                    }
                )
            }
        }
    }

    if (solved) {
        AppDialog(onDismiss = { solved = false }) {
            Text("Grille terminée !", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Bien joué. Une nouvelle grille t'attend.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            MyButton(
                text = "Nouvelle grille",
                modifier = Modifier.fillMaxWidth().height(50.dp),
                fontSize = 16.sp,
                onClick = {
                    solved = false
                    state = null
                    scope.launch { start(MotsFlechesStore.newGrid(context)) }
                }
            )
        }
    }
}

/** The definition of the word being filled, with the two on-demand actions next to it. */
@Composable
private fun ClueBar(slot: Slot?, onCheck: () -> Unit, onReveal: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // The bar keeps its height whatever the definition's length, so the grid above it
                // never jumps as you move from word to word.
                .heightIn(min = 44.dp, max = 96.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                // The full definition, not the shortened one printed in the cell.
                text = slot?.explanation ?: "Touche une case pour commencer",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (slot == null) FontStyle.Italic else FontStyle.Normal
            )
            if (slot != null) {
                Text(
                    text = "${slot.length} lettres" + if (slot.across) ", horizontal" else ", vertical",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onCheck, enabled = slot != null) {
            Icon(Icons.Default.Check, contentDescription = "Vérifier ce mot")
        }
        IconButton(onClick = onReveal, enabled = slot != null) {
            Icon(Icons.Default.Visibility, contentDescription = "Voir la réponse")
        }
    }
}

/** Letters only: the system keyboard's suggestions and autocorrect have nothing to offer here. */
@Composable
private fun LetterKeyboard(onLetter: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Every row adds up to the same total weight, so the keys line up whatever their number.
        KeyboardRow {
            KEYBOARD_ROW_1.forEach { LetterKey(it, onLetter) }
        }
        KeyboardRow {
            Spacer(Modifier.weight(0.5f))
            KEYBOARD_ROW_2.forEach { LetterKey(it, onLetter) }
            Spacer(Modifier.weight(0.5f))
        }
        KeyboardRow {
            Spacer(Modifier.weight(0.5f))
            KEYBOARD_ROW_3.forEach { LetterKey(it, onLetter) }
            Key(modifier = Modifier.weight(2f), onClick = onBackspace) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Effacer",
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.weight(0.5f))
        }
    }
}

@Composable
private fun KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun RowScope.LetterKey(letter: Char, onLetter: (Char) -> Unit) {
    Key(modifier = Modifier.weight(1f), onClick = { onLetter(letter) }) {
        Text(letter.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Key(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

@Composable
private fun ExplanationDialog(slot: Slot, onDismiss: () -> Unit) {
    AppDialog(onDismiss = onDismiss) {
        Text(slot.answer, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = slot.clue,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic
        )
        Spacer(Modifier.height(12.dp))
        Text(slot.explanation, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        MyButton(
            text = "Fermer",
            modifier = Modifier.fillMaxWidth().height(48.dp),
            fontSize = 16.sp,
            onClick = onDismiss
        )
    }
}

private fun puzzleSlot(puzzle: Puzzle, selection: Selection?): Slot? {
    val index = selection?.let { puzzle.slotIndexAt(it.row, it.col, it.across) } ?: return null
    return puzzle.slots[index]
}

/** Opens on the first word of the grid rather than on nothing at all. */
private fun firstSelection(puzzle: Puzzle): Selection? {
    val slot = puzzle.slots.minByOrNull { it.row * puzzle.width + it.col } ?: return null
    return Selection(slot.row, slot.col, slot.across)
}

/**
 * Tapping a letter cell moves there, keeping the direction when the cell has a word that way;
 * tapping the cell you are already on turns the corner. Tapping a definition cell jumps to the
 * word it introduces.
 */
private fun selectionFor(puzzle: Puzzle, row: Int, col: Int, previous: Selection?): Selection? {
    if (puzzle.isDefinition(row, col)) {
        val across = puzzle.slotFromDefinition(row, col, across = true)
        val down = puzzle.slotFromDefinition(row, col, across = false)
        // A cell holding two definitions hands over the other one when tapped again, so both are
        // readable in full in the bar.
        val alreadyOnAcross = across != null && previous?.row == across.row &&
            previous.col == across.col && previous.across
        val slot = (if (alreadyOnAcross) down ?: across else across ?: down) ?: return previous
        return Selection(slot.row, slot.col, slot.across)
    }
    val sameCell = previous != null && previous.row == row && previous.col == col
    val keep = previous?.across ?: true
    val wanted = if (sameCell) !keep else keep
    return when {
        puzzle.slotIndexAt(row, col, wanted) != null -> Selection(row, col, wanted)
        puzzle.slotIndexAt(row, col, !wanted) != null -> Selection(row, col, !wanted)
        else -> previous
    }
}

private fun advance(puzzle: Puzzle, selection: Selection): Selection {
    val slot = puzzleSlot(puzzle, selection) ?: return selection
    val position = if (selection.across) selection.col - slot.col else selection.row - slot.row
    if (position + 1 >= slot.length) return selection
    return selection.copy(
        row = slot.cellRow(position + 1),
        col = slot.cellCol(position + 1)
    )
}

private fun retreat(puzzle: Puzzle, selection: Selection): Selection {
    val slot = puzzleSlot(puzzle, selection) ?: return selection
    val position = if (selection.across) selection.col - slot.col else selection.row - slot.row
    if (position <= 0) return selection
    return selection.copy(
        row = slot.cellRow(position - 1),
        col = slot.cellCol(position - 1)
    )
}

/** Writes the answer in and marks those cells as given, so they read differently from your own. */
private fun revealSlot(state: PuzzleState, slot: Slot): PuzzleState {
    var next = state
    val given = state.revealed.toMutableSet()
    for (position in 0 until slot.length) {
        val cell = state.puzzle.index(slot.cellRow(position), slot.cellCol(position))
        next = next.withLetter(cell, slot.answer[position])
        given.add(cell)
    }
    return next.copy(revealed = given)
}
