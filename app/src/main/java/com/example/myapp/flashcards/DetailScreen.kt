package com.example.myapp.flashcards

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.myapp.BackIconButton
import com.example.myapp.deaccented
import com.example.myapp.flashcardRepository
import com.example.myapp.BottomFadeOverlay
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SortField(val menuLabel: String) {
    INTERVAL("Intervalle révision"),
    VIEWS("Nombre vues totales"),
    SCORE("Score"),
    /** Not a sort but a filter: shows one kind of card at a time, still ordered by interval. */
    SIDE("Toujours le même sens")
}

/**
 * Each menu entry toggles on repeat taps: [flipped] is the other sort direction, or, for
 * [SortField.SIDE], the random-side half of the list rather than the fixed-side one.
 */
private data class SortMode(val field: SortField, val flipped: Boolean = false)

private fun List<FlashcardElement>.filteredAndSorted(query: String, mode: SortMode): List<FlashcardElement> {
    val normalized = query.deaccented()
    val matching = filter {
        (normalized.isEmpty() ||
            it.normalizedName.contains(normalized) ||
            it.normalizedDefinition.contains(normalized)) &&
            (mode.field != SortField.SIDE || it.randomSide == mode.flipped)
    }
    return when (mode.field) {
        SortField.SIDE -> matching.sortedByDescending { it.nextReviewAt }
        SortField.INTERVAL ->
            if (mode.flipped) matching.sortedBy { it.nextReviewAt }
            else matching.sortedByDescending { it.nextReviewAt }
        SortField.VIEWS ->
            if (mode.flipped) matching.sortedBy { it.totalWins + it.totalLosses }
            else matching.sortedByDescending { it.totalWins + it.totalLosses }
        SortField.SCORE -> matching.sortedWith(
            if (mode.flipped) {
                compareByDescending<FlashcardElement> { it.score }
                    .thenByDescending { it.totalWins }
                    .thenBy { it.totalLosses }
                    .thenByDescending { it.nextReviewAt }
            } else {
                compareBy<FlashcardElement> { it.score }
                    .thenByDescending { it.totalWins }
                    .thenBy { it.totalLosses }
                    .thenBy { it.nextReviewAt }
            }
        )
    }
}

@Composable
fun FlashcardDetailScreen(
    listId: String,
    onBack: () -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val repository = context.flashcardRepository

    var showEditDialog by remember { mutableStateOf(false) }
    var editingElement by remember { mutableStateOf<FlashcardElement?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var elementToDelete by remember { mutableStateOf<FlashcardElement?>(null) }
    var selectedElement by remember { mutableStateOf<FlashcardElement?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(SortMode(SortField.INTERVAL)) }
    var showSortMenu by remember { mutableStateOf(false) }

    val isAllLists = listId == "all"

    val lists by repository.observeLists().collectAsState(initial = emptyList())
    val listName = remember(lists, listId) {
        if (isAllLists) "Tout"
        else lists.find { it.id == listId }?.name ?: ""
    }
    val listFixedSide = remember(lists, listId) {
        !isAllLists && lists.find { it.id == listId }?.fixedSide == true
    }

    // Every card of the list, mutated in place so an edit shows up without waiting on the database.
    val elementsState = remember { mutableStateListOf<FlashcardElement>() }
    // Bumped whenever elementsState is mutated, so the filter/sort effect below can key off a
    // cheap int instead of copying and diffing the whole list on every recomposition.
    var elementsVersion by remember { mutableStateOf(0) }

    LaunchedEffect(listId) {
        isLoading = true
        val flow = if (isAllLists) repository.observeAllElements()
        else repository.observeElements(listId)

        var firstEmission = true
        flow.collect { list ->
            if (firstEmission) {
                // Initial load: paint in chunks to keep a big list smooth, then jump to top.
                elementsState.clear()
                list.chunked(20).forEach { chunk ->
                    elementsState.addAll(chunk)
                    elementsVersion++
                    delay(16)
                }
                isLoading = false
                firstEmission = false
                scope.launch { listState.scrollToItem(0) }
            } else {
                // Later DB updates (reset, edit, ...): patch only what changed so just the
                // affected cards recompose and the scroll stays where it is.
                elementsState.patchTo(list)
                elementsVersion++
            }
        }
    }

    // The filtered and sorted list actually rendered, rebuilt when data, query, or sort change.
    val visibleElements = remember { mutableStateListOf<FlashcardElement>() }

    // Detect whether this effect fired from a filter/sort change (jump to top) or from a data
    // update like reset/edit (keep the viewport where it is).
    val prevQuery = remember { mutableStateOf(searchQuery) }
    val prevSort = remember { mutableStateOf(sortMode) }

    LaunchedEffect(elementsVersion, searchQuery, sortMode) {
        // Capture the current scroll position before the visible list is rebuilt.
        val savedIndex = listState.firstVisibleItemIndex
        val savedOffset = listState.firstVisibleItemScrollOffset
        val filterOrSortChanged = searchQuery != prevQuery.value || sortMode != prevSort.value
        prevQuery.value = searchQuery
        prevSort.value = sortMode

        visibleElements.clear()
        visibleElements.addAll(elementsState.filteredAndSorted(searchQuery, sortMode))

        // A new search or sort jumps to the top; a data update (reset, edit, ...) keeps the
        // viewport anchored to the same scroll offset so the touched card just moves underneath.
        if (filterOrSortChanged) {
            listState.scrollToItem(0)
        } else {
            listState.scrollToItem(
                savedIndex.coerceAtMost(maxOf(0, visibleElements.size - 1)),
                savedOffset
            )
        }
    }

    BackHandler {
        if (searchQuery.isNotEmpty()) searchQuery = ""
        else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(Modifier.height(16.dp))
            DetailTopBar(
                listName = listName,
                isAllLists = isAllLists,
                listFixedSide = listFixedSide,
                dueCount = remember(elementsVersion) { elementsState.count { isDue(it) } },
                sortMode = sortMode,
                showSortMenu = showSortMenu,
                onSetSortMenu = { showSortMenu = it },
                onPickSort = { sortMode = it },
                onBack = onBack,
                onShowStats = { showStats = true },
                onToggleFixedSide = {
                    val fixed = !listFixedSide
                    scope.launch {
                        repository.setListFixedSide(listId, fixed)
                        Toast.makeText(
                            context,
                            if (fixed) "Une seule face" else "Deux faces",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onShowAverageWait = {
                    val message = if (visibleElements.isEmpty()) "Aucune carte à réviser" else {
                        val avgMillis = visibleElements.map {
                            maxOf(0L, it.nextReviewAt - System.currentTimeMillis())
                        }.average().toLong()
                        "Temps moyen : ${formatDuration(avgMillis, detailed = true)}"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(Modifier.height(16.dp))

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher...") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = if (isAllLists) 20.dp else 110.dp),
                        state = listState
                    ) {
                        items(
                            count = visibleElements.size,
                            key = { index -> visibleElements[index].id }
                        ) { index ->
                            val element = visibleElements[index]
                            FlashcardElementCard(
                                element = element,
                                listName = if (isAllLists) {
                                    lists.find { it.id == element.listId }?.name
                                } else null,
                                onClick = { selectedElement = element },
                                onEdit = {
                                    editingElement = element
                                    showEditDialog = true
                                },
                                onDelete = { elementToDelete = element },
                                onReset = {
                                    scope.launch {
                                        val idx = elementsState.indexOfFirst { it.id == element.id }
                                        if (idx != -1) {
                                            elementsState[idx] = element.resetProgress()
                                        }
                                        repository.resetElement(element.id)
                                    }
                                }
                            )
                        }
                    }

                    BottomFadeOverlay()
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // "Tout" is read only: cards are added and played from a real list.
        if (!isAllLists) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MyButton(
                        text = "Jouer",
                        modifier = Modifier.weight(1f).height(100.dp)
                    ) { navController.navigate("game/$listId") }

                    MyButton(
                        text = "Ajouter",
                        modifier = Modifier.weight(1f).height(100.dp)
                    ) {
                        editingElement = null
                        showEditDialog = true
                    }
                }
            }
        }
    }

    selectedElement?.let { element ->
        ShowAlertDialog(
            onDismiss = { selectedElement = null },
            title = element.name,
            textContent = {
                Column(
                    modifier = Modifier
                        .padding(vertical = 16.dp, horizontal = 8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Nombre total de réponses: ${element.totalWins + element.totalLosses}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Bonnes réponses: ${element.totalWins}",
                        color = Color(0xFF37A13B),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Mauvaises réponses: ${element.totalLosses}",
                        color = Color(0xFFC4362D),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                }
            },
            onCancel = { selectedElement = null },
            onConfirm = { selectedElement = null },
            cancelText = "Fermer"
        )
    }

    elementToDelete?.let { element ->
        ShowAlertDialog(
            onDismiss = { elementToDelete = null },
            title = "T'es sûr ??",
            onCancel = { elementToDelete = null },
            onConfirm = {
                scope.launch {
                    repository.deleteElement(element.id)
                    elementsState.removeIf { it.id == element.id }
                }
                elementToDelete = null
            },
            cancelText = "Oula non merci",
            confirmText = "Oui t'inquiète"
        )
    }

    if (showEditDialog) {
        ElementEditDialog(
            element = editingElement,
            defaultRandomSide = !listFixedSide,
            onDismiss = { showEditDialog = false },
            onSave = { name, definition, randomSide ->
                scope.launch {
                    val editing = editingElement
                    if (editing == null) {
                        val newElement = FlashcardElement(
                            listId = listId,
                            name = name,
                            definition = definition,
                            randomSide = randomSide
                        )
                        elementsState.add(newElement)
                        repository.addElement(listId, newElement)
                    } else {
                        val updatedElement = editing.copy(
                            name = name,
                            definition = definition,
                            normalizedName = name.deaccented(),
                            normalizedDefinition = definition.deaccented(),
                            randomSide = randomSide
                        )
                        val idx = elementsState.indexOfFirst { it.id == editing.id }
                        if (idx != -1) {
                            elementsState[idx] = updatedElement
                        }
                        repository.updateElement(updatedElement)
                    }
                }
                showEditDialog = false
            }
        )
    }

    if (showStats) {
        StatsSheet(
            title = listName,
            onDismiss = { showStats = false },
            loadStats = { repository.getStats(if (isAllLists) null else listId) }
        )
    }
}

/** Back arrow + list name (tap for stats), then the one-face toggle, the sort menu and the due count. */
@Composable
private fun DetailTopBar(
    listName: String,
    isAllLists: Boolean,
    listFixedSide: Boolean,
    dueCount: Int,
    sortMode: SortMode,
    showSortMenu: Boolean,
    onSetSortMenu: (Boolean) -> Unit,
    onPickSort: (SortMode) -> Unit,
    onBack: () -> Unit,
    onShowStats: () -> Unit,
    onToggleFixedSide: () -> Unit,
    onShowAverageWait: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackIconButton(onBack = onBack)
            Text(
                listName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable(onClick = onShowStats)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // "Tout" spans every list, so there is no single list to fix a side on.
            if (!isAllLists) {
                IconButton(onClick = onToggleFixedSide) {
                    Icon(
                        if (listFixedSide) Icons.Default.LooksOne else Icons.Default.Flip,
                        contentDescription = if (listFixedSide) "Une seule face" else "Deux faces",
                        tint = if (listFixedSide) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box {
                IconButton(onClick = { onSetSortMenu(true) }) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Trier")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { onSetSortMenu(false) }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.menuLabel) },
                            onClick = {
                                // Tapping the entry already in use flips it rather than doing nothing.
                                val alreadyActive = sortMode.field == field && !sortMode.flipped
                                onPickSort(SortMode(field, flipped = alreadyActive))
                                onSetSortMenu(false)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.width(2.dp))

            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(1.dp)
                    .background(Color.Gray)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                "$dueCount à réviser",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.clickable(onClick = onShowAverageWait)
            )
        }
    }
}

@Composable
private fun ElementEditDialog(
    element: FlashcardElement?,
    defaultRandomSide: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, definition: String, randomSide: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(element?.name ?: "") }
    var definition by remember { mutableStateOf(element?.definition ?: "") }
    var randomSide by remember { mutableStateOf(element?.randomSide ?: defaultRandomSide) }
    val definitionFocusRequester = remember { FocusRequester() }

    fun save() {
        if (name.isNotBlank() && definition.isNotBlank()) {
            onSave(name, definition, randomSide)
        }
    }

    ShowAlertDialog(
        onDismiss = onDismiss,
        title = if (element == null) "Nouvel élément" else "Modifier élément",
        textContent = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { definitionFocusRequester.requestFocus() })
                )
                TextField(
                    value = definition,
                    onValueChange = { definition = it },
                    label = { Text("Définition") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.focusRequester(definitionFocusRequester)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Montrer côté aléatoire")
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = randomSide,
                        onCheckedChange = { randomSide = it }
                    )
                }
            }
        },
        onCancel = onDismiss,
        onConfirm = { save() }
    )
}

/**
 * Reconciles this list (the live UI state) with a fresh list from the database, mutating only the
 * entries that actually changed. Removes deleted cards, replaces changed cards in place, and appends
 * new ones. Cards are matched by id (a UUID, unique across lists). Display order is unaffected since
 * the visible list is sorted separately. Touching only changed entries keeps recomposition and the
 * scroll position stable, unlike a full clear-and-rebuild.
 */
private fun MutableList<FlashcardElement>.patchTo(new: List<FlashcardElement>) {
    val newById = new.associateBy { it.id }
    removeAll { it.id !in newById }
    val existingIds = HashSet<String>(size)
    for (i in indices) {
        val updated = newById[this[i].id]
        if (updated != null && updated != this[i]) this[i] = updated
        existingIds.add(this[i].id)
    }
    for (card in new) {
        if (card.id !in existingIds) add(card)
    }
}
