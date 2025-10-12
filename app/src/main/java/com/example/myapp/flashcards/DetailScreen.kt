package com.example.myapp.flashcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun FlashcardDetailScreen(listId: String, onBack: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }
    var dialogDefinition by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var sortAscending by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var elementToDelete by remember { mutableStateOf<FlashcardElement?>(null) }

    // Get the list name for display
    val listsJson by context.dataStore.data
        .map { prefs -> prefs[stringPreferencesKey("lists")] }
        .collectAsState(initial = null)

    val listName = remember(listsJson, listId) {
        listsJson?.let { it ->
            FlashcardList.listFromJsonString(it)
                .find { it.id == listId }?.name
        } ?: ""
    }

    val key = stringPreferencesKey("elements_$listId")
    val elements by remember {
        context.dataStore.data
            .map { prefs ->
                prefs[key]?.let { FlashcardElement.listFromJsonString(it) } ?: emptyList()
            }
    }.collectAsState(initial = emptyList())

    val sortedElements by remember(elements, sortAscending) {
        derivedStateOf {
            if (sortAscending) {
                elements.sortedBy { it.lastReview + (it.interval * 60 * 1000L) }
            } else {
                elements.sortedByDescending { it.lastReview + (it.interval * 60 * 1000L) }
            }
        }
    }

    fun updateElements(newElements: List<FlashcardElement>) {
        scope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[key] = FlashcardElement.listToJsonString(newElements)
            }
        }
    }

    BackHandler {
        when {
            searchQuery.isNotEmpty() -> {
                searchQuery = ""
                return@BackHandler
            }
            else -> {
                onBack()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    listName,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.clickable {
                        scope.launch {
                            listState.scrollToItem(0)
                        }
                    }
                )
            }

            // Spacer to push right column to the end
            Spacer(modifier = Modifier.width(16.dp))

            // Right Column
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        scope.launch {
                            listState.scrollToItem(0)
                        }
                        sortAscending = !sortAscending
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
                        "${elements.count { isDue(it) }} à réviser",
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

        val filteredElements by remember(sortedElements, searchQuery) {
            derivedStateOf {
                if (searchQuery.isEmpty()) sortedElements
                else sortedElements.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                            it.definition.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        // List of elements
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            itemsIndexed(filteredElements, key = { _, item -> "${item.listId}_${item.name}_${item.definition}" }) { index, element ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            // Left column for element name & definition
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    element.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    element.definition,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // Right column for review time & score
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.Top,
                                modifier = Modifier.width(IntrinsicSize.Min)
                            ) {
                                val timeUntilReview = remember(element.lastReview, element.interval) {
                                    val now = System.currentTimeMillis()
                                    val nextReviewTime = element.lastReview + (element.interval * 60 * 1000L)
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

                                val scoreColor = when (element.score?.toInt() ?: 0) {
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
                                            .border(width = 2.dp, color = scoreColor, shape = CircleShape)
                                    ) {
                                        Text(
                                            "${element.score?.toInt() ?: 0}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                shadow = if ((element.score?.toInt() ?: 0) <= 3 || element.score?.toInt() == 10) null else Shadow(
                                                    color = Color.Black,
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
                                            editingIndex = elements.indexOfFirst { it.id == element.id }
                                            dialogName = element.name
                                            dialogDefinition = element.definition
                                            showDialog = true
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Éditer", modifier = Modifier.size(20.dp))
                                    }

                                    IconButton(
                                        onClick = { elementToDelete = element },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(100.dp),
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

    // Delete confirmation dialog
    elementToDelete?.let { element ->
        ShowAlertDialog(
            show = true,
            onDismiss = { elementToDelete = null },
            title = "T'es sûr ??",
            onCancel = { elementToDelete = null },
            onConfirm = {
                val updated = elements.filterNot { it.id == elementToDelete!!.id }
                updateElements(updated)
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
                val updated = elements.toMutableList()
                val newElement = FlashcardElement(listId = listId, name = dialogName, definition = dialogDefinition)
                if (editingIndex == null) updated.add(0, newElement)
                else updated[editingIndex!!] = newElement
                updateElements(updated)
                showDialog = false
            }
        }

        ShowAlertDialog(
            show = true,
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