package com.example.myapp.flashcards

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun FlashcardListsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = (context.applicationContext as com.example.myapp.MyApplication).flashcardRepository
    val isDarkMode = LocalIsDarkMode.current

    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var dialogAction by remember { mutableStateOf<(String) -> Unit>({}) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var bulkImportText by remember { mutableStateOf("") }
    var selectedListId by remember { mutableStateOf("") }
    var showGlobalStats by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val json = repository.createBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sauvegarde créée", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = context.contentResolver.openInputStream(uri)
                        ?.use { it.bufferedReader().readText() } ?: return@launch
                    repository.importFromJson(json)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Importation réussie", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Erreur d'importation", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    var listsWithCountsState by remember { mutableStateOf(Pair(emptyList<FlashcardList>(), emptyMap<String, Pair<Int, Int>>())) }
    var hasLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scheduleFlashcardReminders(context)
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
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
                    // Search All button
                    IconButton(onClick = {
                        navController.navigate("elements/all")
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Rechercher dans tout")
                    }

                    // Play All button
                    IconButton(onClick = {
                        navController.navigate("game/all")
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Jouer tout")
                    }

                    IconButton(onClick = {
                        backupLauncher.launch("cognitools_backup.json")
                    }) {
                        Icon(Icons.Default.Upload, contentDescription = "Sauvegarder")
                    }

                    IconButton(onClick = {
                        restoreLauncher.launch("application/json")
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Restaurer")
                    }
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
                                                dialogTitle = "Renommer la liste"
                                                dialogValue = flashcardList.name
                                                dialogAction = { newName ->
                                                    scope.launch {
                                                        repository.updateList(flashcardList.id, newName)
                                                    }
                                                }
                                                showDialog = true
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
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Floating button at the bottom
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
                    dialogTitle = "Nouvelle liste"
                    dialogValue = ""
                    dialogAction = { newName ->
                        scope.launch {
                            repository.addList(FlashcardList(name = newName))
                        }
                    }
                    showDialog = true
                }
            )
        }
    }

    // Bulk import dialog
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
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "${newElements.size} carte(s) ajoutée(s)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    showBulkImportDialog = false
                }
            }
        )
    }

    // Dialog for create/rename
    ShowAlertDialog(
        show = showDialog,
        onDismiss = { showDialog = false },
        title = dialogTitle,
        textContent = {
            TextField(
                value = dialogValue,
                onValueChange = { dialogValue = it },
                label = { Text("Nom de la liste") }
            )
        },
        onCancel = { showDialog = false },
        onConfirm = {
            if (dialogValue.isNotBlank()) {
                dialogAction(dialogValue)
                showDialog = false
            }
        }
    )

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

    ShowAlertDialog(
        show = showDeleteDialog,
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