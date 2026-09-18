package com.example.myapp.notes

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.AppSnackbar
import com.example.myapp.matchNormalized
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
            else (value as? Int)?.let { key.name.removePrefix(COUNT_KEY_PREFIX) to it }
        }.toMap()
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

/** The two taps that follow a name in Stock mode: the bonus, then how many. */
private sealed class StockStep {
    data class Score(val name: String) : StockStep()
    data class Quantity(val name: String, val score: Int?) : StockStep()
}

/**
 * The bar pinned under the Car Mechanic Simulator note. One field, two modes: Achats runs each
 * part of the in-game shopping list against the stock, Stock adds what a crate or a wreck gave.
 * The list above proposes known names: in Achats a tap requests one straight away, in Stock a tap
 * starts the bonus then quantity chip rows. A long press forgets a name. While typing, the line
 * next to the switch says what the stock holds for that name, and after an entry what was done.
 */
@Composable
fun CarPartsBar(
    note: CarPartsNote,
    bestRated: Boolean,
    memory: CarPartsMemory,
    onSave: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val counts by memory.counts.collectAsState(initial = emptyMap())
    val stockMode by memory.stockMode.collectAsState(initial = false)
    var input by rememberSaveable { mutableStateOf("") }
    var step by remember { mutableStateOf<StockStep?>(null) }
    var lastMessage by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val typed = remember(input) { parseCarPartInput(input) }
    val suggestions = remember(typed?.name, counts, note.stock) {
        suggestCarPartNames(typed?.name ?: "", counts, note.stock.map { it.name })
    }
    val inStock = remember(typed?.name, note.stock) { typed?.let { note.stockOf(it.name) } ?: emptyList() }

    fun submit(part: CarPart) {
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
        input = ""
        step = null
    }

    // In Stock mode a name alone goes through the bonus and quantity rows; a bonus or a quantity
    // typed with it is taken as is.
    fun submitTyped() {
        val current = typed ?: return
        if (stockMode && current.score == null && current.quantity == 1) {
            step = StockStep.Score(current.name)
        } else submit(current)
    }

    fun pickName(name: String) {
        if (stockMode) step = StockStep.Score(name)
        else submit(CarPart(name, quantity = typed?.quantity ?: 1))
    }

    fun forgetName(name: String) {
        scope.launch {
            val count = memory.forgetName(name)
            AppSnackbar.show("Nom oublié : $name", "Annuler") { memory.restoreName(name, count) }
        }
    }

    val status = when {
        step != null -> ""
        typed != null && inStock.isNotEmpty() -> "En stock : " + inStock.joinToString(", ") {
            "${it.quantity}× " + (it.score?.let { s -> "+$s" } ?: "sans note")
        }
        typed != null -> "Pas en stock"
        else -> lastMessage
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (val current = step) {
            is StockStep.Score -> ChoiceRow(
                title = "${current.name}, qualité :",
                choices = SCORE_CHOICES,
                label = { it?.let { s -> "+$s" } ?: "Sans note" },
                onPick = { step = StockStep.Quantity(current.name, it) },
                onCancel = { step = null }
            )
            is StockStep.Quantity -> ChoiceRow(
                title = "${CarPart(current.name, current.score).label}, quantité :",
                choices = QUANTITY_CHOICES,
                label = { it.toString() },
                onPick = { submit(CarPart(current.name, current.score, it)) },
                onCancel = { step = null },
                extra = "Autre…" to {
                    input = CarPart(current.name, current.score).label + " x"
                    step = null
                    focusRequester.requestFocus()
                }
            )
            null -> if (suggestions.isNotEmpty()) {
                NameList(
                    names = suggestions,
                    note = note,
                    onPick = ::pickName,
                    onForget = ::forgetName
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !stockMode,
                    onClick = { step = null; scope.launch { memory.setStockMode(false) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    label = { Text("Achats") }
                )
                SegmentedButton(
                    selected = stockMode,
                    onClick = { scope.launch { memory.setStockMode(true) } },
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
            value = input,
            onValueChange = { input = it; step = null },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(focusRequester),
            placeholder = { Text(if (stockMode) "Pièce trouvée, +3, x2" else "Pièce à acheter, x2") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submitTyped() }),
            trailingIcon = {
                IconButton(onClick = { submitTyped() }, enabled = typed != null) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Valider")
                }
            }
        )
    }
}

/** One step of the Stock entry: a title, a chip per choice, a cross to drop the whole entry. */
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

/** The known names, one per line, the stock held for each on the right. Long press forgets one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NameList(
    names: List<String>,
    note: CarPartsNote,
    onPick: (String) -> Unit,
    onForget: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().height(NAME_LIST_HEIGHT)) {
        items(names, key = { it }) { name ->
            val held = note.stockOf(name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onPick(name) }, onLongClick = { onForget(name) })
                    .padding(horizontal = 4.dp, vertical = 8.dp)
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

private val NAME_LIST_HEIGHT = 168.dp
