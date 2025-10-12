package com.example.myapp.flashcards

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FlashcardListsScreen(onBack: () -> Unit, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State variables
    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var dialogAction by remember { mutableStateOf<(String) -> Unit>({}) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var bulkImportText by remember { mutableStateOf("") }
    var selectedListId by remember { mutableStateOf("") }

    // Load lists asynchronously - start empty, load in background
    var lists by remember { mutableStateOf<List<FlashcardList>>(emptyList()) }
    var flashcards by remember { mutableStateOf<List<FlashcardElement>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Load data asynchronously on first composition
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val prefs = context.dataStore.data.first()

            // Load lists
            val listsJson = prefs[stringPreferencesKey("lists")] ?: "[]"
            val loadedLists = FlashcardList.listFromJsonString(listsJson)

            // Load all flashcards
            val loadedFlashcards = loadedLists.flatMap { list ->
                val key = stringPreferencesKey("elements_${list.id}")
                val cardsJson = prefs[key] ?: "[]"
                FlashcardElement.listFromJsonString(cardsJson)
            }

            withContext(Dispatchers.Main) {
                lists = loadedLists
                flashcards = loadedFlashcards
                isLoading = false
            }
        }
    }

    // Compute due count only when flashcards change, but on background thread
    var dueCountMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(flashcards) {
        withContext(Dispatchers.Default) {
            val newDueCountMap = flashcards
                .filter { isDue(it) }
                .groupingBy { it.listId }
                .eachCount()

            withContext(Dispatchers.Main) {
                dueCountMap = newDueCountMap
            }
        }
    }

    // Helper function to update DataStore
    fun updateLists(newLists: List<FlashcardList>) {
        lists = newLists // Update UI immediately
        scope.launch(Dispatchers.IO) {
            val key = stringPreferencesKey("lists")
            context.dataStore.edit { prefs ->
                prefs[key] = FlashcardList.listToJsonString(newLists)
            }
        }
    }

    BackHandler { onBack() }

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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    "Mes listes",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Row {
                IconButton(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        val (lists, flashcards) = loadFlashcardData(context)
                        exportFlashcards(lists, flashcards)
                    }
                    Toast.makeText(context, "Exported to Downloads", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Upload, contentDescription = "Exporter")
                }
                IconButton(onClick = { importFlashcards(context) }) {
                    Icon(Icons.Default.Download, contentDescription = "Importer")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Show loading indicator while data loads
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // List of flashcard lists
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(items = lists, key = { _, item -> item.id }) { index, flashcardList ->
                    FlashcardListItem(
                        flashcardList = flashcardList,
                        dueCount = dueCountMap[flashcardList.id] ?: 0,
                        onNavigate = { navController.navigate("elements/${flashcardList.id}") },
                        onBulkImport = {
                            selectedListId = flashcardList.id
                            showBulkImportDialog = true
                            bulkImportText = ""
                        },
                        onRename = { newName ->
                            dialogTitle = "Renommer la liste"
                            dialogValue = flashcardList.name
                            dialogAction = { newName ->
                                val updated = lists.toMutableList()
                                updated[index] = flashcardList.copy(name = newName)
                                updateLists(updated)
                            }
                            showDialog = true
                        },
                        onDelete = {
                            val updated = lists.toMutableList()
                            updated.removeAt(index)
                            updateLists(updated)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Button to create a new list
        MyButton(
            text = "Créer une nouvelle liste",
            onClick = {
                dialogTitle = "Nouvelle liste"
                dialogValue = ""
                dialogAction = { newName ->
                    val updated = lists.toMutableList()
                    updated.add(0, FlashcardList(name = newName))
                    updateLists(updated)
                }
                showDialog = true
            }
        )
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

                    val newElements = lines.mapNotNull { line ->
                        val parts = line.split("-", limit = 2)
                        if (parts.size == 2) {
                            val name = parts[0].trim()
                            val definition = parts[1].trim()
                            if (name.isNotBlank() && definition.isNotBlank()) {
                                FlashcardElement(
                                    listId = selectedListId,
                                    name = name,
                                    definition = definition
                                )
                            } else null
                        } else null
                    }

                    if (newElements.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val key = stringPreferencesKey("elements_$selectedListId")
                            val currentJson = context.dataStore.data
                                .map { prefs -> prefs[key] }
                                .first()

                            val currentElements = currentJson?.let {
                                FlashcardElement.listFromJsonString(it)
                            } ?: emptyList()

                            val updatedElements = newElements + currentElements

                            context.dataStore.edit { prefs ->
                                prefs[key] = FlashcardElement.listToJsonString(updatedElements)
                            }

                            // Update local state
                            withContext(Dispatchers.Main) {
                                flashcards = flashcards + updatedElements
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
            },
            context = context
        )
    }

    // Dialog for creating or renaming a list
    ShowAlertDialog(
        show = showDialog,
        onDismiss = { showDialog = false },
        title = dialogTitle,
        textContent = {
            TextField(
                value = dialogValue,
                onValueChange = { dialogValue = it },
                label = { Text("Nom de la liste") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
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
}

@Composable
private fun FlashcardListItem(
    flashcardList: FlashcardList,
    dueCount: Int,
    onNavigate: () -> Unit,
    onBulkImport: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .scale(if (isPressed) 0.95f else 1f)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple()
            ) { onNavigate() },
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flashcardList.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$dueCount à réviser",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                }

                Row {
                    IconButton(onClick = onBulkImport) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter")
                    }

                    IconButton(onClick = { onRename(flashcardList.name) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Éditer")
                    }

                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer")
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
private fun BulkImportDialog(
    bulkImportText: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    context: Context
) {
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
                    Icon(Icons.Default.ContentPaste, contentDescription = "Coller", tint = Color.Black)
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