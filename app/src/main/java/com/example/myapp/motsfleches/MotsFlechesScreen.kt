package com.example.myapp.motsfleches

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.flashcards.AddToFlashcardsDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where a found word is filed by default; the dialog can still send it anywhere else. */
private const val MOTS_FLECHES_LIST_NAME = "Random"

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
    // The letters a check found wrong. Not saved: a mark only means "wrong when you asked", and it
    // goes away as soon as the cell is touched again.
    var wrong by remember { mutableStateOf(emptySet<Int>()) }
    // The cells a check has already found an error in, without saying where. Pressing check again
    // on the very same cells is what gives the positions away.
    var flagged by remember { mutableStateOf<List<Int>?>(null) }
    var confirmNewGrid by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Null until read from the settings: each language keeps its own grid, so loading one before
    // knowing which is wanted would show the wrong grid for a moment and generate a needless one.
    var lang by remember { mutableStateOf<MotsFlechesLang?>(null) }

    fun apply(next: PuzzleState) {
        val current = lang ?: return
        state = next
        // Any change to the grid starts the check over: a flagged word is only flagged as it was.
        flagged = null
        scope.launch { MotsFlechesStore.save(context, current, next) }
        if (next.isSolved) solved = true
    }

    fun start(fresh: PuzzleState) {
        val current = lang ?: return
        state = fresh
        selection = firstSelection(fresh.puzzle)
        wrong = emptySet()
        flagged = null
        scope.launch { MotsFlechesStore.prepareNext(context, current) }
    }

    /**
     * Checks what is written in [cells], however little that is. A first check only says whether
     * something is wrong; nothing is marked and no count is given away. Checking the same cells
     * again, without having typed since, is what turns the wrong letters red and confirms the
     * right ones. A check that finds nothing wrong has nothing to hold back, so it marks at once.
     */
    fun check(cells: List<Int>, whole: Boolean) {
        val puzzleState = state ?: return
        val filled = puzzleState.filledIn(cells)
        if (filled.isEmpty()) {
            AppSnackbar.show("Rien à vérifier ici pour l'instant")
            return
        }
        val bad = puzzleState.wrongIn(cells).toSet()
        if (bad.isNotEmpty() && flagged != cells) {
            flagged = cells
            AppSnackbar.show(
                if (whole) "Il y a une faute dans la grille. Revérifie pour la situer."
                else "Il y a une faute dans ce mot. Revérifie pour la situer."
            )
            return
        }
        flagged = null
        wrong = wrong - filled.toSet() + bad
        apply(puzzleState.confirming(cells))
        AppSnackbar.show(
            when {
                bad.isNotEmpty() -> "${bad.size} lettre${plural(bad.size)} fausse${plural(bad.size)}"
                filled.size == cells.size -> if (whole) "Toute la grille est juste !" else "Ce mot est bon"
                else -> "Bon jusqu'ici : ${filled.size} lettre${plural(filled.size)} juste${plural(filled.size)}"
            }
        )
    }

    LaunchedEffect(Unit) {
        lang = MotsFlechesStore.language(context).first()
    }

    // Also runs on a language switch: the other language's grid is picked up exactly where it was.
    LaunchedEffect(lang) {
        val current = lang ?: return@LaunchedEffect
        state = null
        selection = null
        start(MotsFlechesStore.current(context, current))
    }

    val current = state
    val slot = current?.let { puzzleSlot(it.puzzle, selection) }

    fun checkGrid() {
        val puzzleState = current ?: return
        check(
            (0 until puzzleState.puzzle.cellCount).filterNot { it in puzzleState.puzzle.definitionCells },
            whole = true
        )
    }

    fun revealWord() {
        val puzzleState = current ?: return
        val target = slot ?: return
        wrong = wrong - puzzleState.puzzle.cellsOf(target).toSet()
        apply(puzzleState.revealSlot(target))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Mots fléchés",
            onBack = onBack,
            titleSuffix = {
                Spacer(Modifier.weight(1f))
                // One tap swaps the language, and with it the grid: each side keeps its own.
                lang?.let { active ->
                    TextButton(
                        onClick = {
                            val other = if (active == MotsFlechesLang.FR) MotsFlechesLang.EN else MotsFlechesLang.FR
                            scope.launch {
                                MotsFlechesStore.setLanguage(context, other)
                                lang = other
                            }
                        }
                    ) {
                        Text(active.label, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // The two rarer actions live here so the clue bar keeps three clear
                        // buttons: checking everything at once, and giving up on a word.
                        DropdownMenuItem(
                            text = { Text("Vérifier toute la grille") },
                            enabled = current != null,
                            onClick = {
                                menuOpen = false
                                checkGrid()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Voir la réponse") },
                            enabled = slot != null,
                            onClick = {
                                menuOpen = false
                                revealWord()
                            }
                        )
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
                                current?.let {
                                    wrong = emptySet()
                                    apply(PuzzleState.blank(it.puzzle))
                                }
                            }
                        )
                    }
                }
            }
        )

        if (current == null) {
            // Nothing is downloaded here: the wait is a grid being filled on the phone, so it says
            // so rather than leaving a bare spinner that could pass for a stalled request.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Génération de la grille…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            wrong = wrong,
            onSelect = { row, col -> selection = selectionFor(current.puzzle, row, col, selection) }
        )

        ClueBar(
            slot = slot,
            // Only once the word has been checked or given away: gating this on the letters simply
            // being right would tell the player they had found it before they asked.
            foundSlot = slot?.takeIf { current.isValidated(it) },
            lang = lang,
            onCheckWord = { slot?.let { check(current.puzzle.cellsOf(it), whole = false) } },
            onRevealLetter = {
                val target = selection ?: return@ClueBar
                val cell = current.puzzle.index(target.row, target.col)
                wrong = wrong - cell
                apply(current.revealCell(cell))
                selection = advance(current.puzzle, target)
            },
            onClearWord = {
                val target = slot ?: return@ClueBar
                wrong = wrong - current.puzzle.cellsOf(target).toSet()
                apply(current.clearSlot(target))
                selection = Selection(target.row, target.col, target.across)
            }
        )

        LetterKeyboard(
            onLetter = { letter ->
                val target = selection ?: return@LetterKeyboard
                val cell = current.puzzle.index(target.row, target.col)
                wrong = wrong - cell
                apply(current.typed(cell, letter))
                selection = advance(current.puzzle, target)
            },
            onBackspace = {
                val target = selection ?: return@LetterKeyboard
                val cell = current.puzzle.index(target.row, target.col)
                if (current.letterAt(cell) != Puzzle.EMPTY) {
                    wrong = wrong - cell
                    apply(current.cleared(cell))
                } else {
                    val previous = retreat(current.puzzle, target)
                    val previousCell = current.puzzle.index(previous.row, previous.col)
                    wrong = wrong - previousCell
                    apply(current.cleared(previousCell))
                    selection = previous
                }
            }
        )
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
                        scope.launch { lang?.let { start(MotsFlechesStore.newGrid(context, it)) } }
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
                    scope.launch { lang?.let { start(MotsFlechesStore.newGrid(context, it)) } }
                }
            )
        }
    }
}

/**
 * The definition of the word being filled, and the three actions that act on it. Checking the
 * whole grid and giving a word away outright are in the screen's menu instead: they are asked for
 * far less often, and five unlabelled icons side by side told you nothing about what they did.
 */
@Composable
private fun ClueBar(
    slot: Slot?,
    foundSlot: Slot?,
    lang: MotsFlechesLang?,
    onCheckWord: () -> Unit,
    onRevealLetter: () -> Unit,
    onClearWord: () -> Unit
) {
    val context = LocalContext.current
    var filing by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // No ceiling here: the definition is shown whole, however long it is. The bar takes
                // the height it needs and the grid above, which is the weighted one, gives it up.
                .heightIn(min = 40.dp)
        ) {
            Text(
                // The definition in full, not the one cut down to fit the cell.
                text = slot?.explanation ?: "Touche une case pour commencer",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (slot == null) FontStyle.Italic else FontStyle.Normal
            )
            if (slot != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${slot.length} lettres" + if (slot.across) ", horizontal" else ", vertical",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Both of these hand the answer over, so they only exist once the word is found.
                    if (foundSlot != null) {
                        Spacer(Modifier.weight(1f))
                        LinkAction(Icons.Default.School, "Flashcard") { filing = true }
                        if (lang != null) {
                            LinkAction(Icons.AutoMirrored.Filled.OpenInNew, "En savoir plus") {
                                openWiktionary(context, lang, foundSlot.answer)
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClueAction(Icons.Default.Check, "Vérifier", enabled = slot != null, onClick = onCheckWord)
            ClueAction(Icons.Default.Lightbulb, "Indice", enabled = slot != null, onClick = onRevealLetter)
            ClueAction(Icons.Default.DeleteOutline, "Effacer", enabled = slot != null, onClick = onClearWord)
        }
    }

    if (filing && foundSlot != null) {
        AddToFlashcardsDialog(
            // The grid holds the word in capitals and without its accents: it goes in the dialog as
            // an ordinary word, and whatever is left to fix is fixed there before it is filed.
            word = foundSlot.answer.lowercase(),
            definition = foundSlot.explanation,
            defaultListName = MOTS_FLECHES_LIST_NAME,
            onDismiss = { filing = false }
        )
    }
}

/** A small text action shown next to the definition once its word is found. */
@Composable
private fun LinkAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** An icon with what it does written under it: on its own the icon was anybody's guess. */
@Composable
private fun ClueAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * The found word's dictionary entry. The grid holds it stripped of its accents, which is not always
 * a page title, so this goes through the search rather than straight to a URL that may not exist.
 */
private fun openWiktionary(context: Context, lang: MotsFlechesLang, word: String) {
    val url = "https://${lang.wiktionary}.wiktionary.org/w/index.php?search=" +
        Uri.encode(word.lowercase())
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { AppSnackbar.show("Aucun navigateur pour ouvrir le lien") }
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

private fun plural(count: Int) = if (count > 1) "s" else ""
