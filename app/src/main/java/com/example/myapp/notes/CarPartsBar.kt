package com.example.myapp.notes

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ShoppingCart
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.matchNormalized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.carPartsDataStore by preferencesDataStore("car_parts")

private const val COUNT_KEY_PREFIX = "count:"
private val BEST_RATED_KEY = booleanPreferencesKey("best_rated")

/**
 * What the Car Mechanic Simulator bar remembers: how many times each part name was entered, so
 * the chips propose the usual ones first, and which rating mode the header toggle is in.
 */
class CarPartsMemory(private val context: Context) {
    val counts: Flow<Map<String, Int>> = context.carPartsDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(COUNT_KEY_PREFIX)) null
            else (value as? Int)?.let { key.name.removePrefix(COUNT_KEY_PREFIX) to it }
        }.toMap()
    }

    val bestRated: Flow<Boolean> = context.carPartsDataStore.data.map { it[BEST_RATED_KEY] ?: false }

    /** Counts one more use of [name], under the spelling already known for it if there is one. */
    suspend fun recordName(name: String) {
        context.carPartsDataStore.edit { prefs ->
            val key = name.matchNormalized()
            val existing = prefs.asMap().keys
                .firstOrNull { it.name.startsWith(COUNT_KEY_PREFIX) && it.name.removePrefix(COUNT_KEY_PREFIX).matchNormalized() == key }
                ?.let { intPreferencesKey(it.name) }
                ?: intPreferencesKey(COUNT_KEY_PREFIX + name)
            prefs[existing] = (prefs[existing] ?: 0) + 1
        }
    }

    suspend fun setBestRated(value: Boolean) {
        context.carPartsDataStore.edit { it[BEST_RATED_KEY] = value }
    }
}

/**
 * The bar pinned under the Car Mechanic Simulator note. One field, two modes: Achats runs each
 * part of the in-game shopping list against the stock, Stock adds what a crate or a wreck gave.
 * Chips above propose known names; while typing, the line under the switch says what the stock
 * holds for that name, and after an entry what was done with it.
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
    var input by rememberSaveable { mutableStateOf("") }
    var stockMode by rememberSaveable { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf("") }

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
    }

    // A chip fills the name in, keeping any bonus or quantity typed; a chip that is already the
    // name in the field validates the entry.
    fun pickSuggestion(name: String) {
        val current = typed
        if (current == null) {
            input = name
            return
        }
        if (current.key == name.matchNormalized()) {
            submit(current.copy(name = name))
            return
        }
        input = buildString {
            append(name)
            current.score?.let { append(" +").append(it) }
            if (current.quantity > 1) append(" x").append(current.quantity)
        }
    }

    val status = when {
        typed != null && inStock.isNotEmpty() -> "En stock : " + inStock.joinToString(", ") {
            "${it.quantity}× " + (it.score?.let { s -> "+$s" } ?: "sans note")
        }
        typed != null -> "Pas en stock"
        else -> lastMessage
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions, key = { it }) { name ->
                    SuggestionChip(onClick = { pickSuggestion(name) }, label = { Text(name) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !stockMode,
                    onClick = { stockMode = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    label = { Text("Achats") }
                )
                SegmentedButton(
                    selected = stockMode,
                    onClick = { stockMode = true },
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
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            placeholder = { Text(if (stockMode) "Pièce trouvée, +3, x2" else "Pièce à acheter, x2") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { typed?.let(::submit) }),
            trailingIcon = {
                IconButton(onClick = { typed?.let(::submit) }, enabled = typed != null) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Valider")
                }
            }
        )
    }
}
