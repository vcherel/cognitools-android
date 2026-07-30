package com.example.myapp.flashcards

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.navigation.NavController
import com.example.myapp.BackIconButton
import com.example.myapp.flashcardRepository
import com.example.myapp.BackupRestoreActions
import com.example.myapp.BottomFadeOverlay
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// An open name prompt: what it is titled, the name being typed, and what to do with it.
private data class NamePrompt(val title: String, val value: String, val onConfirm: (String) -> Unit)

@Composable
fun FlashcardListsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = context.flashcardRepository

    var namePrompt by remember { mutableStateOf<NamePrompt?>(null) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var bulkImportText by remember { mutableStateOf("") }
    var selectedListId by remember { mutableStateOf("") }
    var showGlobalStats by remember { mutableStateOf(false) }

    var listsWithCountsState by remember { mutableStateOf(Pair(emptyList<FlashcardList>(), emptyMap<String, Pair<Int, Int>>())) }
    var hasLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        repository.observeListsWithCounts().collect { pair ->
            listsWithCountsState = pair
            hasLoaded = true
        }
    }
    val lists = listsWithCountsState.first
    val countsMap = listsWithCountsState.second
    val isLoading = !hasLoaded

    // Local mirror of the list order so drag feels instant. Synced from the DB flow
    // while not dragging, and written back to the DB when a drag ends.
    var localLists by remember { mutableStateOf(lists) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localLists = localLists.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    LaunchedEffect(lists) {
        if (!reorderableState.isAnyItemDragging) localLists = lists
    }
    var wasDragging by remember { mutableStateOf(false) }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        val dragging = reorderableState.isAnyItemDragging
        if (wasDragging && !dragging) {
            repository.reorderLists(localLists)
        }
        wasDragging = dragging
    }

    // Back from the lists screen always lands on the app menu, whatever the stack contains
    val backToMenu: () -> Unit = { navController.popBackStack("menu", inclusive = false) }
    BackHandler { backToMenu() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    BackIconButton(onBack = { backToMenu() })
                    Text(
                        "Listes",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .padding(start = 8.dp, end = 15.dp)
                            .clickable { showGlobalStats = true }
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.Gray)
                    )
                }

                Row {
                    IconButton(onClick = {
                        navController.navigate("elements/all")
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Rechercher dans tout")
                    }

                    IconButton(onClick = {
                        navController.navigate("game/all")
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Jouer tout")
                    }

                    BackupRestoreActions(
                        backupFileName = "cognitools_flashcards.json",
                        importDialogText = "Les listes et cartes du fichier seront ajoutées. " +
                                "Celles qui existent déjà seront remplacées par la version du fichier.",
                        createBackupJson = { repository.createBackupJson() },
                        importFromJson = { repository.importFromJson(it) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator()
                    }
                    lists.isEmpty() -> {
                        Text("Aucune liste disponible", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = lazyListState,
                                contentPadding = PaddingValues(bottom = 116.dp)
                            ) {
                                items(items = localLists, key = { it.id }) { flashcardList ->
                                    ReorderableItem(reorderableState, key = flashcardList.id) { isDragging ->
                                        FlashcardListItem(
                                            flashcardList = flashcardList,
                                            totalCount = countsMap[flashcardList.id]?.first ?: 0,
                                            dueCount = countsMap[flashcardList.id]?.second ?: 0,
                                            isDragging = isDragging,
                                            dragModifier = Modifier.longPressDraggableHandle(),
                                            onNavigate = {
                                                navController.navigate("elements/${flashcardList.id}")
                                            },
                                            onBulkImport = {
                                                selectedListId = flashcardList.id
                                                showBulkImportDialog = true
                                                bulkImportText = ""
                                            },
                                            onRename = {
                                                namePrompt = NamePrompt(
                                                    title = "Renommer la liste",
                                                    value = flashcardList.name
                                                ) { newName ->
                                                    scope.launch { repository.updateList(flashcardList.id, newName) }
                                                }
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    repository.deleteList(flashcardList.id)
                                                }
                                            },
                                            onPlay = { navController.navigate("game/${flashcardList.id}") }
                                        )
                                    }
                                }
                            }

                            BottomFadeOverlay()
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            MyButton(
                text = "Créer une nouvelle liste",
                modifier = Modifier.fillMaxWidth().height(100.dp),
                onClick = {
                    namePrompt = NamePrompt(title = "Nouvelle liste", value = "") { newName ->
                        scope.launch { repository.addList(FlashcardList(name = newName)) }
                    }
                }
            )
        }
    }

    if (showBulkImportDialog) {
        BulkImportDialog(
            bulkImportText = bulkImportText,
            onTextChange = { bulkImportText = it },
            onDismiss = { showBulkImportDialog = false },
            onConfirm = {
                if (bulkImportText.isNotBlank()) {
                    val lines = bulkImportText.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                    val separators = listOf(" - ", " : ", " ; ")
                    val newElements = lines.mapNotNull { line ->
                        var processedLine = line
                        val randomSide = !processedLine.endsWith("#")

                        if (processedLine.endsWith("#")) {
                            processedLine = processedLine.dropLast(1).trim()
                        }

                        val sep = separators.firstOrNull { processedLine.contains(it) } ?: return@mapNotNull null
                        val index = processedLine.indexOf(sep)

                        val name = processedLine.substring(0, index).trim()
                        val definition = processedLine.substring(index + sep.length).trim()

                        if (name.isNotBlank() && definition.isNotBlank()) {
                            FlashcardElement(
                                listId = selectedListId,
                                name = name,
                                definition = definition,
                                randomSide = randomSide
                            )
                        } else null
                    }

                    if (newElements.isNotEmpty()) {
                        scope.launch {
                            repository.addElements(selectedListId, newElements)
                            Toast.makeText(
                                context,
                                "${newElements.size} carte(s) ajoutée(s)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    showBulkImportDialog = false
                }
            }
        )
    }

    namePrompt?.let { prompt ->
        ShowAlertDialog(
            onDismiss = { namePrompt = null },
            title = prompt.title,
            textContent = {
                TextField(
                    value = prompt.value,
                    onValueChange = { namePrompt = prompt.copy(value = it) },
                    label = { Text("Nom de la liste") }
                )
            },
            onCancel = { namePrompt = null },
            onConfirm = {
                if (prompt.value.isNotBlank()) {
                    prompt.onConfirm(prompt.value)
                    namePrompt = null
                }
            }
        )
    }

    if (showGlobalStats) {
        StatsSheet(
            title = "Toutes les listes",
            onDismiss = { showGlobalStats = false },
            loadStats = { repository.getStats() }
        )
    }
}

@Composable
fun FlashcardListItem(
    flashcardList: FlashcardList,
    totalCount: Int,
    dueCount: Int,
    onNavigate: () -> Unit,
    onBulkImport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    isDragging: Boolean = false,
    dragModifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val scale = if (isDragging) 1.03f else if (isPressed) 0.90f else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(dragModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple()
            ) { onNavigate() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 12.dp else 6.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text on the left
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flashcardList.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$dueCount / $totalCount à réviser",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                }

                // Buttons on the right in 2 rows
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.width(80.dp)
                    ) {
                        IconButton(
                            onClick = onRename,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Éditer")
                        }
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.width(80.dp)
                    ) {
                        IconButton(
                            onClick = onPlay,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Jouer")
                        }
                        IconButton(
                            onClick = onBulkImport,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) ShowAlertDialog(
        onDismiss = { showDeleteDialog = false },
        title = "T'es sûr ??",
        onCancel = { showDeleteDialog = false },
        onConfirm = {
            onDelete()
            showDeleteDialog = false
        },
        cancelText = "Oula non merci",
        confirmText = "Oui t'inquiète"
    )
}

@Composable
fun BulkImportDialog(
    bulkImportText: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importer des cartes") },
        text = {
            Column {
                Text(
                    "Collez vos cartes au format :\nNom - Définition",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                TextField(
                    value = bulkImportText,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    placeholder = { Text("Exemple:\nMot 1 - Définition 1\nMot 2 - Définition 2") },
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = clipboard.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        onTextChange(clipData.getItemAt(0).text?.toString() ?: "")
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Coller")
                }

                MyButton(
                    text = "Annuler",
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    fontSize = 14.sp
                )

                MyButton(
                    text = "Ok",
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    fontSize = 14.sp
                )
            }
        }
    )
}