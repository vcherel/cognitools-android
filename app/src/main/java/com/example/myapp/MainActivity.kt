package com.example.myapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.ui.theme.MyAppTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyAppTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var currentScreen by remember { mutableStateOf("menu") }
    val flashcardsNavController = rememberNavController() // NavController for flashcards only

    Scaffold { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "menu" -> MenuScreen(onNavigate = { screen -> currentScreen = screen })
                "randomGenerator" -> RandomGeneratorScreen(onBack = { currentScreen = "menu" })
                "volumeBooster" -> VolumeBoosterScreen(onBack = { currentScreen = "menu" })
                "flashcards" -> {
                    // NavHost for flashcards lists & elements screens
                    NavHost(navController = flashcardsNavController, startDestination = "lists") {
                        composable("lists") {
                            FlashcardsScreen(
                                onBack = { currentScreen = "menu" },
                                navController = flashcardsNavController
                            )
                        }
                        composable("elements/{listName}") { backStackEntry ->
                            val listName = backStackEntry.arguments?.getString("listName") ?: ""
                            FlashcardElementsScreen(
                                listName = listName,
                                onBack = { flashcardsNavController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun MenuScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MenuButton(text = "Générateur aléatoire") { onNavigate("randomGenerator") }
        Spacer(modifier = Modifier.height(16.dp))
        MenuButton(text = "Volume booster") { onNavigate("volumeBooster") }
        Spacer(modifier = Modifier.height(16.dp))
        MenuButton(text = "Flashcards") { onNavigate("flashcards") }
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
    ) {
        Text(text)
    }
}

@Composable
fun RandomGeneratorScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    ScreenTemplate {
        Spacer(modifier = Modifier.height(96.dp))
        RandomIntSection()
        Spacer(modifier = Modifier.height(24.dp))
        RandomWordSection()
    }
}

@Composable
fun RandomIntSection() {
    var min by remember { mutableStateOf("1") }
    var max by remember { mutableStateOf("100") }
    var result by remember { mutableStateOf<Int?>(null) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nombre entier aléatoire")
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = min,
                onValueChange = { min = it },
                label = { Text("Min") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = max,
                onValueChange = { max = it },
                label = { Text("Max") },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val minInt = min.toIntOrNull() ?: 1
                val maxInt = max.toIntOrNull() ?: 100
                if (minInt <= maxInt) result = (minInt..maxInt).random()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Générer")
        }
        Spacer(modifier = Modifier.height(8.dp))
        result?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color.LightGray),
                contentAlignment = Alignment.Center
            ) {
                Text(text = it.toString(), fontSize = 32.sp)
            }
        }
    }
}

@Composable
fun RandomWordSection(context: Context = LocalContext.current) {
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<String?>(null) }

    // Load words asynchronously
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            val loadedWords = context.assets.open("words.txt")
                .bufferedReader()
                .useLines { it.toList() }
            words = loadedWords
            isLoading = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Mot aléatoire")
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { if (words.isNotEmpty()) result = words.random() },
            enabled = !isLoading, // Disable button while loading
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(if (isLoading) "Chargement..." else "Générer")
        }
        Spacer(modifier = Modifier.height(8.dp))
        result?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color.LightGray),
                contentAlignment = Alignment.Center
            ) {
                Text(text = it, fontSize = 32.sp)
            }
        }
    }
}

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    ScreenTemplate(content = {})
}

val Context.dataStore by preferencesDataStore("flashcards")

@Composable
fun FlashcardsScreen(onBack: () -> Unit, navController: NavController) {
    BackHandler { onBack() } // Handle Android back button
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect DataStore as State - automatically updates when data changes
    val listsJson by context.dataStore.data
        .map { prefs -> prefs[stringPreferencesKey("lists")] }
        .collectAsState(initial = null)

    // Derive the actual list from the JSON
    val lists = remember(listsJson) {
        listsJson?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
    }

    // Helper function to update DataStore
    fun updateLists(newLists: List<String>) {
        scope.launch(Dispatchers.IO) {
            val key = stringPreferencesKey("lists")
            context.dataStore.edit { prefs ->
                prefs[key] = Json.encodeToString(newLists)
            }
        }
    }

    var showDialog by remember { mutableStateOf(false) } // Controls whether the add/rename dialog is visible
    var dialogTitle by remember { mutableStateOf("") } // Title of the dialog ("Nouvelle liste" or "Renommer la liste")
    var dialogValue by remember { mutableStateOf("") } // Current text in the dialog's TextField
    var dialogAction by remember { mutableStateOf<(String) -> Unit>({}) } // Action to perform when dialog is confirmed
    var editingIndex by remember { mutableStateOf<Int?>(null) } // Index of the list being edited (null if adding new)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Mes listes", style = MaterialTheme.typography.headlineMedium) // Screen title
        Spacer(Modifier.height(16.dp))

        // Display all lists in a scrollable column
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(lists) { index, name ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = name, // List name
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { navController.navigate("elements/${name}") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Ouvrir")
                            }
                            Button(
                                onClick = {
                                    editingIndex = index // Remember which list is being edited
                                    dialogTitle = "Renommer la liste"
                                    dialogValue = name
                                    dialogAction = { newName ->
                                        val updatedLists = lists.toMutableList()
                                        updatedLists[editingIndex!!] = newName
                                        updatedLists[index] = newName
                                        updateLists(updatedLists)
                                        editingIndex = null
                                    }
                                    showDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Éditer")
                            }
                            Button(
                                onClick = {
                                    val updatedLists = lists.toMutableList()
                                    updatedLists.removeAt(index)
                                    updateLists(updatedLists)
                                          },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Button to create a new list
        Button(
            onClick = {
                dialogTitle = "Nouvelle liste"
                dialogValue = "" // Empty text for new list
                dialogAction = { newName ->
                    val updatedLists = lists.toMutableList()
                    updatedLists.add(newName)
                    updateLists(updatedLists)
                }
                showDialog = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text("Créer une nouvelle liste")
        }
    }

    // Dialog to add or rename a list
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogTitle) }, // Dialog title
            text = {
                TextField(
                    value = dialogValue, // TextField input
                    onValueChange = { dialogValue = it },
                    label = { Text("Nom de la liste") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogValue.isNotBlank()) {
                            dialogAction(dialogValue) // Execute add/rename action
                            showDialog = false
                        }
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
fun FlashcardElementsScreen(listName: String, onBack: () -> Unit) {
    BackHandler { onBack() } // Handle Android back button
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect elements for this list from DataStore
    val key = stringPreferencesKey("elements_$listName")
    val elementsJson by context.dataStore.data
        .map { prefs -> prefs[key] }
        .collectAsState(initial = null)

    // Convert JSON to actual list of elements
    val elements = remember(elementsJson) {
        elementsJson?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
    }

    // Helper to update elements in DataStore
    fun updateElements(newElements: List<String>) {
        scope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[key] = Json.encodeToString(newElements)
            }
        }
    }

    var showDialog by remember { mutableStateOf(false) } // Show add/edit dialog
    var dialogValue by remember { mutableStateOf("") } // Current text in dialog
    var editingIndex by remember { mutableStateOf<Int?>(null) } // Which element is being edited

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(listName, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(elements) { index, value ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(value, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    editingIndex = index
                                    dialogValue = value
                                    showDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Éditer")
                            }
                            Button(
                                onClick = {
                                    val updated = elements.toMutableList()
                                    updated.removeAt(index)
                                    updateElements(updated)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                dialogValue = ""
                editingIndex = null
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text("Ajouter un élément")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editingIndex == null) "Nouvel élément" else "Modifier élément") },
            text = {
                TextField(
                    value = dialogValue,
                    onValueChange = { dialogValue = it },
                    label = { Text("Texte de l'élément") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogValue.isNotBlank()) {
                            val updated = elements.toMutableList()
                            if (editingIndex == null) {
                                updated.add(dialogValue) // Add new element
                            } else {
                                updated[editingIndex!!] = dialogValue // Edit existing
                            }
                            updateElements(updated)
                            showDialog = false
                        }
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun ScreenTemplate(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        content()
    }
}