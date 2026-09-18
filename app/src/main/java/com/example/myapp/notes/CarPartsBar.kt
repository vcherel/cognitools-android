package com.example.myapp.notes

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.AppSnackbar
import com.example.myapp.matchNormalized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.carPartsDataStore by preferencesDataStore("car_parts")

private const val COUNT_KEY_PREFIX = "count:"
private val BEST_RATED_KEY = booleanPreferencesKey("best_rated")
private val STOCK_MODE_KEY = booleanPreferencesKey("stock_mode")

private val SCORE_CHOICES = listOf(null, 1, 2, 3, 4, 5)
private val QUANTITY_CHOICES = listOf(1, 2, 3, 4, 5)

/**
 * What the Car Mechanic Simulator bar remembers: how many times each part name was entered, so
 * the list proposes the usual ones first, which rating mode the header toggle is in, and which
 * of Achats / Stock the bar was left on.
 */
class CarPartsMemory(private val context: Context) {
    val counts: Flow<Map<String, Int>> = context.carPartsDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(COUNT_KEY_PREFIX)) null
            else (value as? Int)?.let { key.name.removePrefix(COUNT_KEY_PREFIX).cleanCarPartName() to it }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, uses) -> uses.sum() }
    }

    val bestRated: Flow<Boolean> = context.carPartsDataStore.data.map { it[BEST_RATED_KEY] ?: false }

    val stockMode: Flow<Boolean> = context.carPartsDataStore.data.map { it[STOCK_MODE_KEY] ?: false }

    /** Counts one more use of [name], under the spelling already known for it if there is one. */
    suspend fun recordName(name: String) {
        context.carPartsDataStore.edit { prefs ->
            val key = countKeyOf(prefs.asMap().keys.map { it.name }, name)
            prefs[key] = (prefs[key] ?: 0) + 1
        }
    }

    /** Gives every name not yet known one use, so the stock typed by hand ranks like the rest. Returns how many were new. */
    suspend fun recordNames(names: Collection<String>): Int {
        var added = 0
        context.carPartsDataStore.edit { prefs ->
            names.forEach { name ->
                val key = countKeyOf(prefs.asMap().keys.map { it.name }, name)
                if (prefs[key] == null) {
                    prefs[key] = 1
                    added++
                }
            }
        }
        return added
    }

    /** Forgets [name] (a typo, usually). Returns the count it had, so an undo can put it back. */
    suspend fun forgetName(name: String): Int {
        var count = 0
        context.carPartsDataStore.edit { prefs ->
            val key = countKeyOf(prefs.asMap().keys.map { it.name }, name)
            count = prefs[key] ?: 0
            prefs.remove(key)
        }
        return count
    }

    suspend fun restoreName(name: String, count: Int) {
        context.carPartsDataStore.edit { it[intPreferencesKey(COUNT_KEY_PREFIX + name)] = count }
    }

    suspend fun setBestRated(value: Boolean) {
        context.carPartsDataStore.edit { it[BEST_RATED_KEY] = value }
    }

    suspend fun setStockMode(value: Boolean) {
        context.carPartsDataStore.edit { it[STOCK_MODE_KEY] = value }
    }

    private fun countKeyOf(keys: Collection<String>, name: String) =
        keys.firstOrNull { it.startsWith(COUNT_KEY_PREFIX) && it.removePrefix(COUNT_KEY_PREFIX).matchNormalized() == name.matchNormalized() }
            ?.let { intPreferencesKey(it) }
            ?: intPreferencesKey(COUNT_KEY_PREFIX + name)
}

/** The chip rows that follow a name: the bonus then how many in Stock mode, how many alone in Achats. */
internal sealed class EntryStep {
    data class Score(val name: String) : EntryStep()
    data class Quantity(val name: String, val score: Int?) : EntryStep()
}

/**
 * What the Car Mechanic Simulator bar is doing, shared by the bar itself and the name list the
 * editor floats over the note while the field is typed in. Built by [rememberCarPartsBarState].
 */
class CarPartsBarState internal constructor(
    private val scope: CoroutineScope,
    private val memory: CarPartsMemory,
    private val onSave: (String) -> Unit
) {
    internal var note by mutableStateOf(CarPartsNote())
    internal var bestRated by mutableStateOf(false)
    internal var counts by mutableStateOf<Map<String, Int>>(emptyMap())
    internal var stockMode by mutableStateOf(false)
    internal var imeVisible by mutableStateOf(false)

    internal var fieldValue by mutableStateOf(TextFieldValue())
    internal var step by mutableStateOf<EntryStep?>(null)
    internal var lastMessage by mutableStateOf("")
    internal var focused by mutableStateOf(false)
    internal val focusRequester = FocusRequester()

    internal val typed: CarPart? get() = parseCarPartInput(fieldValue.text)

    /** Fills the field with the cursor at the end, so a quantity can be typed right after. */
    internal fun setInput(text: String) {
        fieldValue = TextFieldValue(text, TextRange(text.length))
    }
    internal val suggestions: List<String>
        get() = suggestCarPartNames(typed?.name ?: "", counts, note.stock.map { it.name })
    internal val inStock: List<CarPart> get() = typed?.let { note.stockOf(it.name) } ?: emptyList()

    /** True while the known names should float over the note. */
    val listShown: Boolean get() = step == null && focused && imeVisible && suggestions.isNotEmpty()

    internal fun submit(part: CarPart) {
        val message: String
        val updated: CarPartsNote
        if (stockMode) {
            updated = note.withStock(part)
            message = "Ajouté au stock : ${part.render()}"
        } else {
            val result = note.request(part.name, part.quantity, bestRated)
            updated = result.note
            message = listOfNotNull(
                result.taken.takeIf { it.isNotEmpty() }
                    ?.let { taken -> "Pris du stock : " + taken.joinToString(", ") { it.render() } },
                result.toBuy?.let { "À acheter : ${it.render()}" }
            ).joinToString(" · ")
        }
        onSave(updated.render())
        scope.launch { memory.recordName(part.name) }
        lastMessage = message
        setInput("")
        step = null
    }

    // In Stock mode a name alone goes through the bonus and quantity rows; a bonus or a quantity
    // typed with it is taken as is.
    internal fun submitTyped() {
        val current = typed ?: return
        if (stockMode && current.score == null && current.quantity == 1) {
            step = EntryStep.Score(current.name)
        } else submit(current)
    }

    // In Achats the tap only fills the field (a slip must not fire a request): a quantity chip or
    // send confirms.
    internal fun pickName(name: String) {
        if (stockMode) step = EntryStep.Score(name)
        else {
            setInput(CarPart(name, quantity = typed?.quantity ?: 1).render())
            step = EntryStep.Quantity(name, null)
        }
    }

    internal fun forgetName(name: String) {
        scope.launch {
            val count = memory.forgetName(name)
            AppSnackbar.show("Nom oublié : $name", "Annuler") { memory.restoreName(name, count) }
        }
    }

    internal fun switchMode(value: Boolean) {
        if (!value) step = null
        scope.launch { memory.setStockMode(value) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberCarPartsBarState(
    note: CarPartsNote,
    bestRated: Boolean,
    memory: CarPartsMemory,
    onSave: (String) -> Unit
): CarPartsBarState {
    val scope = rememberCoroutineScope()
    val state = remember { CarPartsBarState(scope, memory, onSave) }
    state.note = note
    state.bestRated = bestRated
    state.counts = memory.counts.collectAsState(initial = emptyMap()).value
    state.stockMode = memory.stockMode.collectAsState(initial = false).value
    state.imeVisible = WindowInsets.isImeVisible
    return state
}

/**
 * The bar pinned under the Car Mechanic Simulator note. One field, two modes: Achats runs each
 * part of the in-game shopping list against the stock, Stock adds what a crate or a wreck gave.
 * Under the field, a Stock entry unfolds its bonus then quantity chip rows, an Achats name picked
 * from the list its quantity row alone. The known names are
 * not here: the editor draws them over the note with [CarPartsNameList] while the field is typed
 * in, so the field never moves. While typing, the line next to the switch says what the stock
 * holds for that name, and after an entry what was done.
 */
@Composable
fun CarPartsBar(state: CarPartsBarState) {
    val stockMode = state.stockMode
    val typed = state.typed
    val inStock = state.inStock

    val status = when {
        state.step != null && stockMode -> ""
        typed != null && inStock.isNotEmpty() -> "En stock : " + inStock.joinToString(", ") {
            "${it.quantity}× " + (it.score?.let { s -> "+$s" } ?: "sans note")
        }
        typed != null -> "Pas en stock"
        else -> state.lastMessage
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !stockMode,
                    onClick = { state.switchMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    label = { Text("Achats") }
                )
                SegmentedButton(
                    selected = stockMode,
                    onClick = { state.switchMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                    label = { Text("Stock") }
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = state.fieldValue,
            onValueChange = { state.fieldValue = it; state.step = null },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .focusRequester(state.focusRequester)
                .onFocusChanged { state.focused = it.isFocused },
            placeholder = { Text(if (stockMode) "Pièce trouvée, +3, x2" else "Pièce à acheter, x2") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { state.submitTyped() }),
            trailingIcon = {
                IconButton(onClick = { state.submitTyped() }, enabled = typed != null) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Valider")
                }
            }
        )
        when (val current = state.step) {
            is EntryStep.Score -> ChoiceRow(
                title = "${current.name} · qualité",
                choices = SCORE_CHOICES,
                label = { it?.let { s -> "+$s" } ?: "Sans note" },
                onPick = { state.step = EntryStep.Quantity(current.name, it) },
                onCancel = { state.step = null }
            )
            is EntryStep.Quantity -> ChoiceRow(
                title = "${CarPart(current.name, current.score).label} · quantité",
                choices = QUANTITY_CHOICES,
                label = { it.toString() },
                onPick = { state.submit(CarPart(current.name, current.score, it)) },
                onCancel = { state.step = null },
                extra = "Autre…" to {
                    state.setInput(CarPart(current.name, current.score).label + " x")
                    state.step = null
                    state.focusRequester.requestFocus()
                }
            )
            null -> Unit
        }
    }
}

/** One step of an entry: a title, a chip per choice, a cross to drop the step. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    choices: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onCancel: () -> Unit,
    extra: Pair<String, () -> Unit>? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Annuler") }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices) { choice ->
            SuggestionChip(onClick = { onPick(choice) }, label = { Text(label(choice)) })
        }
        if (extra != null) {
            item { SuggestionChip(onClick = extra.second, label = { Text(extra.first) }) }
        }
    }
}

/**
 * The known names floated over the note while the field is typed in, a menu that grows upward
 * from the bar as far as the note area allows and scrolls past that. The stock held for each name
 * is on the right. Tap picks, long press forgets. Shown only while [CarPartsBarState.listShown].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CarPartsNameList(state: CarPartsBarState, modifier: Modifier = Modifier) {
    if (!state.listShown) return
    val note = state.note
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.suggestions, key = { it }) { name ->
                val held = note.stockOf(name)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { state.pickName(name) }, onLongClick = { state.forgetName(name) })
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (held.isNotEmpty()) {
                        Text(
                            held.joinToString(" ") { "${it.quantity}×" + (it.score?.let { s -> "+$s" } ?: "") },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
            }
        }
    }
}
