package com.example.myapp.screen.flashcard

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.example.myapp.data.dataStore
import com.example.myapp.data.exportFlashcards
import com.example.myapp.data.importFlashcards
import com.example.myapp.data.loadFlashcardData
import com.example.myapp.helper.createNotificationChannel
import com.example.myapp.models.FlashcardElement
import com.example.myapp.models.FlashcardList
import com.example.myapp.models.isDue
import com.example.myapp.ui.MyButton
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

    // Create notification channel on first composition
    LaunchedEffect(Unit) {
        createNotificationChannel(context)
    }

    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var dialogAction by remember { mutableStateOf<(String) -> Unit>({}) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var bulkImportText by remember { mutableStateOf("") }
    var selectedListId by remember { mutableStateOf("") }

    val flashcards by remember {
        context.dataStore.data.map { prefs ->
            val listsJson = prefs[stringPreferencesKey("lists")] ?: "[]"
            val lists = FlashcardList.listFromJsonString(listsJson)

            lists.flatMap { list ->
                val key = stringPreferencesKey("elements_${list.id}")
                val cardsJson = prefs[key] ?: "[]"
                FlashcardElement.listFromJsonString(cardsJson)
            }
        }
    }.collectAsState(initial = emptyList())

    val lists by remember {
        context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("lists")]?.let {
                FlashcardList.listFromJsonString(it)
            } ?: emptyList()
        }
    }.collectAsState(initial = emptyList())

    // Helper function to update DataStore
    fun updateLists(newLists: List<FlashcardList>) {
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

        val dueCountMap by remember(flashcards) {
            derivedStateOf {
                flashcards
                    .filter { isDue(it) }
                    .groupingBy { it.listId }
                    .eachCount()
            }
        }

        // List of flashcard lists
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(items = lists, key = { _, item -> item.id }) { index, flashcardList ->
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .scale(if (isPressed) 0.95f else 1f)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = ripple()
                        ) {
                            navController.navigate("elements/${flashcardList.id}")
                        },
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
                                    "${dueCountMap[flashcardList.id] ?: 0} à réviser",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.Gray
                                )
                            }

                            Row {
                                IconButton(onClick = {
                                    selectedListId = flashcardList.id
                                    showBulkImportDialog = true
                                    bulkImportText = ""
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Ajouter")
                                }

                                IconButton(onClick = {
                                    dialogTitle = "Renommer la liste"
                                    dialogValue = flashcardList.name
                                    dialogAction = { newName ->
                                        val updated = lists.toMutableList()
                                        updated[index] = flashcardList.copy(name = newName)
                                        updateLists(updated)
                                    }

                                    showDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Éditer")
                                }

                                var showDeleteDialog by remember { mutableStateOf(false) }
                                IconButton(onClick = { showDeleteDialog = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                                }

                                if (showDeleteDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showDeleteDialog = false },
                                        title = { Text("T'es sûr ??") },
                                        text = null,
                                        confirmButton = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                MyButton(
                                                    text = "Oula non merci",
                                                    onClick = { showDeleteDialog = false },
                                                    modifier = Modifier.weight(1f).height(50.dp),
                                                    fontSize = 14.sp
                                                )
                                                MyButton(
                                                    text = "Oui t'inquiète",
                                                    onClick = {
                                                        val updated = lists.toMutableList()
                                                        updated.removeAt(index)
                                                        updateLists(updated)
                                                        showDeleteDialog = false
                                                    },
                                                    modifier = Modifier.weight(1f).height(50.dp),
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    )

                                }

                                if (showBulkImportDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showBulkImportDialog = false },
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
                                                    onValueChange = { bulkImportText = it },
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
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                IconButton(onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clipData = clipboard.primaryClip
                                                    if (clipData != null && clipData.itemCount > 0) {
                                                        bulkImportText = clipData.getItemAt(0).text?.toString() ?: ""
                                                    }
                                                }) {
                                                    Icon(Icons.Default.ContentPaste, contentDescription = "Coller", tint=Color.Black)
                                                }

                                                MyButton(
                                                    text = "Annuler",
                                                    onClick = { showBulkImportDialog = false },
                                                    modifier = Modifier.height(50.dp)
                                                )

                                                MyButton(
                                                    text = "Importer",
                                                    onClick = {
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
                                                    },
                                                    modifier = Modifier.height(50.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
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

    // Dialog for creating or renaming a list
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogTitle) },
            text = {
                TextField(
                    value = dialogValue,
                    onValueChange = { dialogValue = it },
                    label = { Text("Nom de la liste") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            },
            confirmButton = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyButton(
                        text = "Annuler",
                        onClick = { showDialog = false },
                        modifier = Modifier.weight(1f).height(50.dp),
                        fontSize = 14.sp
                    )
                    MyButton(
                        text = "Ok",
                        onClick = {
                            if (dialogValue.isNotBlank()) {
                                dialogAction(dialogValue)
                                showDialog = false
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        fontSize = 14.sp
                    )
                }
            }
        )
    }
}