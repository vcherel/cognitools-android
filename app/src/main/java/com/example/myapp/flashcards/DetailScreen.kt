package com.example.myapp.flashcards

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
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
    val repository = remember { FlashcardRepository(context) }

    var showDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }
    var dialogDefinition by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var elementToDelete by remember { mutableStateOf<FlashcardElement?>(null) }
    var selectedElement by remember { mutableStateOf<FlashcardElement?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var sortState by remember { mutableIntStateOf(0) }

    // Observe lists
    val lists by repository.observeLists().collectAsState(initial = emptyList())
    val listName = remember(lists, listId) {
        lists.find { it.id == listId }?.name ?: ""
    }

    // Local mutable list for fast UI updates
    val elementsState = remember { mutableStateListOf<FlashcardElement>() }

    // Sync repository elements into local state
    LaunchedEffect(listId) {
        isLoading = true
        repository.observeElements(listId).collect { list ->
            elementsState.clear()
            val chunkSize = 20
            list.chunked(chunkSize).forEach { chunk ->
                elementsState.addAll(chunk)
                delay(16) // 60 FPS
            }
            isLoading = false
        }
    }

    // Computed filtered & sorted list in-place to avoid repeated list allocations
    val visibleElements = remember { mutableStateListOf<FlashcardElement>() }

    LaunchedEffect(elementsState.toList(), searchQuery, sortState) {
        val filtered = elementsState.filter {
            searchQuery.isEmpty() ||
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.definition.contains(searchQuery, ignoreCase = true)
        }

        val sorted = when (sortState) {
            0 -> filtered.sortedByDescending { it.lastReview + it.interval * 60_000L }
            1 -> filtered.sortedBy { it.lastReview + it.interval * 60_000L }
            2 -> filtered.sortedByDescending { it.totalWins + it.totalLosses }
            3 -> filtered.sortedBy { it.totalWins + it.totalLosses }
            else -> filtered
        }

        visibleElements.clear()
        visibleElements.addAll(sorted)
    }

    BackHandler {
        if (searchQuery.isNotEmpty()) searchQuery = ""
        else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        listName,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.clickable {
                            scope.launch { listState.scrollToItem(0) }
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            scope.launch { listState.scrollToItem(0) }

                            // Cycle through 0 -> 1 -> 2 -> 3 -> 0
                            sortState = (sortState + 1) % 4

                            // Show a toast for the current sort
                            val toastMessage = when(sortState) {
                                0 -> "Tri: Intervalle révision (décroissant)"
                                1 -> "Tri: Intervalle révision (croissant)"
                                2 -> "Tri: Nombre vues totales (décroissant)"
                                3 -> "Tri: Nombre vues totales (croissant)"
                                else -> ""
                            }
                            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Trier")
                        }

                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .width(1.dp)
                                .background(Color.Gray)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${elementsState.count { isDue(it) }} à réviser",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
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
                        contentPadding = PaddingValues(bottom = 116.dp),
                        state = listState
                    ) {
                        items(
                            count = visibleElements.size,
                            key = { index -> visibleElements[index].id }
                        ) { index ->
                            val element = visibleElements[index]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedElement = element },
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(element.name, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                element.definition,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }

                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            verticalArrangement = Arrangement.Top,
                                            modifier = Modifier.width(IntrinsicSize.Min)
                                        ) {
                                            val timeUntilReview =
                                                remember(element.lastReview, element.interval) {
                                                    val now = System.currentTimeMillis()
                                                    val nextReviewTime =
                                                        element.lastReview + (element.interval * 60_000L)
                                                    val diffMs = nextReviewTime - now
                                                    when {
                                                        diffMs <= 0 -> "Maintenant"
                                                        diffMs < 60 * 60 * 1000 -> "${(diffMs / (60 * 1000)).toInt()}min"
                                                        diffMs < 24 * 60 * 60 * 1000 -> "${(diffMs / (60 * 60 * 1000)).toInt()}h"
                                                        diffMs < 7 * 24 * 60 * 60 * 1000 -> "${(diffMs / (24 * 60 * 60 * 1000)).toInt()}j"
                                                        diffMs < 30 * 24 * 60 * 60 * 1000L -> "${(diffMs / (7 * 24 * 60 * 60 * 1000)).toInt()}sem"
                                                        else -> "${(diffMs / (30 * 24 * 60 * 60 * 1000L)).toInt()}mois"
                                                    }
                                                }

                                            Text(
                                                timeUntilReview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isDue(element)) Color(0xFF009900) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            val scoreColor = when (element.score.toInt()) {
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
                                                        editingIndex =
                                                            elementsState.indexOfFirst { it.id == element.id }
                                                        dialogName = element.name
                                                        dialogDefinition = element.definition
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
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Gradient overlay at the bottom
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0xFFFEF7FF)
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
                    editingIndex = null
                    showDialog = true
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
                scope.launch { repository.deleteElement(listId, element.id) }
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
                    if (editingIndex == null) {
                        val newElement = FlashcardElement(
                            listId = listId,
                            name = dialogName,
                            definition = dialogDefinition
                        )
                        elementsState.add(newElement)
                        repository.addElement(listId, newElement)
                    } else {
                        val updatedElement = elementsState[editingIndex!!].copy(
                            name = dialogName,
                            definition = dialogDefinition
                        )
                        elementsState[editingIndex!!] = updatedElement
                        repository.updateElement(listId, updatedElement)
                    }
                }
                showDialog = false
            }
        }

        ShowAlertDialog(
            onDismiss = { showDialog = false },
            title = if (editingIndex == null) "Nouvel élément" else "Modifier élément",
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
                }
            },
            onCancel = { showDialog = false },
            onConfirm = { saveElement() }
        )
    }
}
