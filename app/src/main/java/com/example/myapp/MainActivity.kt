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
import kotlinx.coroutines.flow.first
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

    Scaffold { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "menu" -> MenuScreen(onNavigate = { screen -> currentScreen = screen })
                "randomGenerator" -> RandomGeneratorScreen(onBack = { currentScreen = "menu" })
                "volumeBooster" -> VolumeBoosterScreen(onBack = { currentScreen = "menu" })
                "flashcards" -> FlashcardsScreen(onBack = { currentScreen = "menu" })
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
    val words by remember {
        mutableStateOf(
            context.assets.open("words.txt")
                .bufferedReader()
                .useLines { it.toList() }
        )
    }
    var result by remember { mutableStateOf<String?>(null) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Mot aléatoire")
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { result = words.random() },
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
fun FlashcardsScreen(onBack: () -> Unit) {
    BackHandler { onBack() } // Handle Android back button
    val lists = remember { mutableStateListOf<String>() } // List of flashcard list names
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) } // Controls whether the add/rename dialog is visible
    var dialogTitle by remember { mutableStateOf("") } // Title of the dialog ("Nouvelle liste" or "Renommer la liste")
    var dialogValue by remember { mutableStateOf("") } // Current text in the dialog's TextField
    var dialogAction by remember { mutableStateOf<(String) -> Unit>({}) } // Action to perform when dialog is confirmed
    var editingIndex by remember { mutableStateOf<Int?>(null) } // Index of the list being edited (null if adding new)


    // Load lists when screen starts
    LaunchedEffect(Unit) {
        val key = stringPreferencesKey("lists")
        val prefs = context.dataStore.data.first()
        val saved = prefs[key]
        if (saved != null) lists.addAll(Json.decodeFromString(saved))
    }

    // Function to save lists
    fun saveLists() {
        scope.launch {
            val key = stringPreferencesKey("lists")
            context.dataStore.edit { prefs ->
                prefs[key] = Json.encodeToString(lists)
            }
        }
    }

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
                                onClick = { /* open the list */ },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Ouvrir")
                            }
                            Button(
                                onClick = {
                                    editingIndex = index // Remember which list is being edited
                                    dialogTitle = "Renommer la liste"
                                    dialogValue = name // Pre-fill TextField with current name
                                    dialogAction = { newName ->
                                        lists[editingIndex!!] = newName 
                                        editingIndex = null
                                        saveLists()                     // Rename the list and save
                                    }
                                    showDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Éditer")
                            }
                            Button(
                                onClick = {
                                    lists.removeAt(index)
                                    saveLists()             // Remove list and save
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
                    lists.add(newName)
                    saveLists()         // // Add new list and save
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
private fun ScreenTemplate(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        content()
    }
}