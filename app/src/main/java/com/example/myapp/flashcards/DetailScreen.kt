package com.example.myapp.flashcards

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FlashcardDetailScreen(
    listId: String,
    onBack: () -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val repository = (context.applicationContext as com.example.myapp.MyApplication).flashcardRepository
    val isDarkMode = LocalIsDarkMode.current

    var showDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }
    var dialogDefinition by remember { mutableStateOf("") }
    var dialogRandomSide by remember { mutableStateOf(true) }
    var editingElement by remember { mutableStateOf<FlashcardElement?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var elementToDelete by remember { mutableStateOf<FlashcardElement?>(null) }
    var selectedElement by remember { mutableStateOf<FlashcardElement?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var sortState by remember { mutableIntStateOf(0) }
    var showSortMenu by remember { mutableStateOf(false) }

    val isAllLists = listId == "all"

    // Observe lists
    val lists by repository.observeLists().collectAsState(initial = emptyList())
    val listName = remember(lists, listId) {
        if (isAllLists) "Tout"
        else lists.find { it.id == listId }?.name ?: ""
    }

    // Local mutable list for fast UI updates
    val elementsState = remember { mutableStateListOf<FlashcardElement>() }

    // Sync repository elements into local state
    LaunchedEffect(listId, lists) {
        isLoading = true

        if (isAllLists) {
            val allElements = lists.flatMap { list -> repository.getElements(list.id) }
            val chunkSize = 20
            allElements.chunked(chunkSize).forEach { chunk ->
                elementsState.addAll(chunk)
                delay(16)
            }
            isLoading = false
            scope.launch { listState.scrollToItem(0) }
        } else {
            var firstEmission = true
            repository.observeElements(listId).collect { list ->
                if (firstEmission) {
                    // Initial load: paint in chunks to keep a big list smooth, then jump to top.
                    elementsState.clear()
                    list.chunked(20).forEach { chunk ->
                        elementsState.addAll(chunk)
                        delay(16)
                    }
                    isLoading = false
                    firstEmission = false
                    scope.launch { listState.scrollToItem(0) }
                } else {
                    // Later DB updates (reset, edit, ...): patch only what changed so just the
                    // affected cards recompose and the scroll stays where it is.
                    elementsState.patchTo(list)
                }
            }
        }
    }

    // Computed filtered & sorted list in-place to avoid repeated list allocations
    val visibleElements = remember { mutableStateListOf<FlashcardElement>() }

    // Detect whether this effect fired from a filter/sort change (jump to top) or from a data
    // update like reset/edit (keep the viewport where it is).
    val prevQuery = remember { mutableStateOf(searchQuery) }
    val prevSort = remember { mutableIntStateOf(sortState) }

    LaunchedEffect(elementsState.toList(), searchQuery, sortState) {
        // Capture the current scroll position before the visible list is rebuilt.
        val savedIndex = listState.firstVisibleItemIndex
        val savedOffset = listState.firstVisibleItemScrollOffset
        val filterOrSortChanged = searchQuery != prevQuery.value || sortState != prevSort.value
        prevQuery.value = searchQuery
        prevSort.value = sortState

        val normalizedQuery = searchQuery.normalizeForSearch()
        val filtered = elementsState.filter {
            (normalizedQuery.isEmpty() ||
                    it.normalizedName.contains(normalizedQuery) ||
                    it.normalizedDefinition.contains(normalizedQuery)) &&
                    (sortState != 6 || !it.randomSide) && (sortState != 7 || it.randomSide)
        }

        val sorted = when (sortState) {
            0 -> filtered.sortedByDescending { it.lastReview + it.interval * 60_000L }
            1 -> filtered.sortedBy { it.lastReview + it.interval * 60_000L }
            2 -> filtered.sortedByDescending { it.totalWins + it.totalLosses }
            3 -> filtered.sortedBy { it.totalWins + it.totalLosses }
            4 -> filtered.sortedWith(
                compareBy<FlashcardElement> { it.score }
                    .thenByDescending { it.totalWins }
                    .thenBy { it.totalLosses }
                    .thenBy { it.lastReview + it.interval * 60_000L }
            )
            5 -> filtered.sortedWith(
                compareByDescending<FlashcardElement> { it.score }
                    .thenByDescending { it.totalWins }
                    .thenBy { it.totalLosses }
                    .thenByDescending { it.lastReview + it.interval * 60_000L }
            )
            6 -> filtered.sortedByDescending { it.lastReview + it.interval * 60_000L }
            7 -> filtered.sortedByDescending { it.lastReview + it.interval * 60_000L }
            else -> filtered
        }

        // Deduplicate by composite key (listId + id)
        val unique = sorted.distinctBy { "${it.listId}_${it.id}" }

        visibleElements.clear()
        visibleElements.addAll(unique)

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
        else navController.navigate("lists")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: back button + title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                    Text(
                        listName,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.clickable { showStats = true }
                    )
                }

                // Right side: sort button + divider + "X à réviser"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Trier")
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Intervalle révision") },
                                onClick = {
                                    sortState = if (sortState == 0) 1 else 0
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Nombre vues totales") },
                                onClick = {
                                    sortState = if (sortState == 2) 3 else 2
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Score") },
                                onClick = {
                                    sortState = if (sortState == 4) 5 else 4
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Toujours le même sens") },
                                onClick = {
                                    sortState = if (sortState == 6) 7 else 6
                                    showSortMenu = false
                                }
                            )
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
                        "${elementsState.count { isDue(it) }} à réviser",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.clickable {
                            if (visibleElements.isNotEmpty()) {
                                val avgMillis = visibleElements.map {
                                    val nextReviewTime = it.lastReview + it.interval * 60_000L
                                    maxOf(0L, nextReviewTime - System.currentTimeMillis())
                                }.average().toLong()

                                Toast.makeText(
                                    context,
                                    "Temps moyen : ${formatDuration(avgMillis, detailed = true)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Aucune carte à réviser",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                    )
                }
            }

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
                        val duplicates = elementsState.groupingBy { "${it.listId}_${it.id}" }
                            .eachCount().filter { it.value > 1 }

                        if (duplicates.isNotEmpty()) {
                            Log.w("FlashcardDetail", "Duplicate keys found: $duplicates")
                        }

                        items(
                            count = visibleElements.size,
                            key = { index ->
                                val e = visibleElements[index]
                                if (isAllLists) "${e.listId}_${e.id}" else e.id
                            }

                        ) { index ->
                            val element = visibleElements[index]
                            val elementListName = lists.find { it.id == element.listId }?.name ?: ""

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedElement = element },
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    // Show list name if viewing all lists
                                    if (isAllLists && elementListName.isNotBlank()) {
                                        Text(
                                            text = elementListName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            var expandedName by remember { mutableStateOf(false) }
                                            Text(
                                                text = element.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = if (expandedName) Int.MAX_VALUE else 1,
                                                overflow = if (expandedName) TextOverflow.Visible else TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .clickable { expandedName = !expandedName }
                                            )

                                            var expandedDefinition by remember { mutableStateOf(false) }
                                            Text(
                                                text = element.definition,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = if (expandedDefinition) Int.MAX_VALUE else 1,
                                                overflow = if (expandedDefinition) TextOverflow.Visible else TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .clickable { expandedDefinition = !expandedDefinition }
                                            )
                                        }

                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            verticalArrangement = Arrangement.Top,
                                            modifier = Modifier.width(IntrinsicSize.Min)
                                        ) {
                                            val timeUntilReview =
                                                remember(element.lastReview, element.interval) {
                                                    val nextReviewTime =
                                                        element.lastReview + (element.interval * 60_000L)
                                                    formatDuration(nextReviewTime - System.currentTimeMillis())
                                                }

                                            Text(
                                                timeUntilReview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isDue(element)) Color(0xFF009900) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            val scoreColor = scoreColor(element.score.toInt())

                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .border(
                                                            width = 2.dp,
                                                            color = scoreColor,
                                                            shape = CircleShape
                                                        )
                                                ) {
                                                    Text(
                                                        "${element.score.toInt()}",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            shadow = if ((element.score.toInt()) <= 3 || element.score.toInt() == 10) null else Shadow(
                                                                offset = Offset(0f, 0f),
                                                                blurRadius = 1f
                                                            )
                                                        ),
                                                        color = scoreColor,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(4.dp))

                                                IconButton(
                                                    onClick = {
                                                        editingElement = element
                                                        dialogName = element.name
                                                        dialogDefinition = element.definition
                                                        dialogRandomSide = element.randomSide
                                                        showDialog = true
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = "Éditer",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { elementToDelete = element },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Supprimer",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            // Update local state immediately for UI stability
                                                            val idx = elementsState.indexOfFirst { it.id == element.id }
                                                            if (idx != -1) {
                                                                elementsState[idx] = element.copy(
                                                                    easeFactor = 2.5,
                                                                    interval = 0,
                                                                    repetitions = 0,
                                                                    lastReview = System.currentTimeMillis(),
                                                                    totalWins = 0,
                                                                    totalLosses = 0,
                                                                    score = 0.0
                                                                )
                                                            }
                                                            repository.resetElement(element.id)
                                                        }
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = "Réinitialiser",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Gradient overlay at the bottom
                    val color = if (isDarkMode) {
                        Color(0xFF000000)
                    }
                    else {
                        Color(0xFFFEF7FF)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        color
                                    ),
                                    startY = 0f,
                                    endY = Float.POSITIVE_INFINITY
                                )
                            )
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // Show action buttons only if not in "all lists" mode
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
                        dialogName = ""
                        dialogDefinition = ""
                        dialogRandomSide = true
                        editingElement = null
                        showDialog = true
                    }
                }
            }
        }
    }

    // Information dialog
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
                    // Update UI immediately
                    elementsState.removeIf { it.id == element.id && it.listId == element.listId }
                }
                elementToDelete = null
            },
            cancelText = "Oula non merci",
            confirmText = "Oui t'inquiète"
        )
    }

    val focusRequester2 = remember { FocusRequester() }

    if (showDialog) {
        fun saveElement() {
            if (dialogName.isNotBlank() && dialogDefinition.isNotBlank()) {
                scope.launch {
                    if (editingElement == null) {
                        val newElement = FlashcardElement(
                            listId = listId,
                            name = dialogName,
                            definition = dialogDefinition,
                            randomSide = dialogRandomSide
                        )
                        elementsState.add(newElement)
                        repository.addElement(listId, newElement)
                    } else {
                        val updatedElement = editingElement!!.copy(
                            name = dialogName,
                            definition = dialogDefinition,
                            randomSide = dialogRandomSide
                        )
                        val idx = elementsState.indexOfFirst { it.id == editingElement!!.id }
                        if (idx != -1) {
                            elementsState[idx] = updatedElement
                        }
                        repository.updateElement(updatedElement)
                    }
                }
                showDialog = false
            }
        }

        ShowAlertDialog(
            onDismiss = { showDialog = false },
            title = if (editingElement == null) "Nouvel élément" else "Modifier élément",
            textContent = {
                Column {
                    TextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text("Nom") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusRequester2.requestFocus() })
                    )
                    TextField(
                        value = dialogDefinition,
                        onValueChange = { dialogDefinition = it },
                        label = { Text("Définition") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveElement() }),
                        modifier = Modifier.focusRequester(focusRequester2)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Montrer côté aléatoire")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = dialogRandomSide,
                            onCheckedChange = { dialogRandomSide = it }
                        )
                    }
                }
            },
            onCancel = { showDialog = false },
            onConfirm = { saveElement() }
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

/**
 * Reconciles this list (the live UI state) with a fresh list from the database, mutating only the
 * entries that actually changed. Removes deleted cards, replaces changed cards in place, and appends
 * new ones. Cards are matched by id (unique within a single list). Display order is unaffected since
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