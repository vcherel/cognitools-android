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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

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

                    NavHost(navController = flashcardsNavController, startDestination = "lists") {
                        composable("lists") {
                            FlashcardsScreen(
                                onBack = { currentScreen = "menu" },
                                navController = flashcardsNavController
                            )
                        }
                        composable("elements/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardElementsScreen(
                                listId = listId,
                                navController = flashcardsNavController,
                                onBack = { flashcardsNavController.popBackStack() }
                            )
                        }
                        composable("game/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardGameScreen(listId = listId, onBack = { flashcardsNavController.popBackStack() })
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

data class FlashcardList(val id: String = UUID.randomUUID().toString(), val name: String) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
        }
    }
    companion object {
        fun fromJson(json: JSONObject): FlashcardList {
            return FlashcardList(
                id = json.getString("id"),
                name = json.getString("name")
            )
        }

        fun listToJsonString(lists: List<FlashcardList>): String {
            val jsonArray = JSONArray()
            lists.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }

        fun listFromJsonString(jsonString: String): List<FlashcardList> {
            return try {
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { i ->
                    fromJson(jsonArray.getJSONObject(i))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit, navController: NavController) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect DataStore as State
    val listsJson by context.dataStore.data
        .map { prefs -> prefs[stringPreferencesKey("lists")] }
        .collectAsState(initial = null)

    // Derive the actual list from the JSON
    val lists = remember(listsJson) {
        listsJson?.let { FlashcardList.listFromJsonString(it) } ?: emptyList()
    }

    // Helper function to update DataStore
    fun updateLists(newLists: List<FlashcardList>) {
        scope.launch(Dispatchers.IO) {
            val key = stringPreferencesKey("lists")
            context.dataStore.edit { prefs ->
                prefs[key] = FlashcardList.listToJsonString(newLists)
            }
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var dialogAction by remember { mutableStateOf<(String) -> Unit>({}) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Mes listes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(lists) { index, flashcardList ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = flashcardList.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    navController.navigate("elements/${flashcardList.id}")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Ouvrir")
                            }
                            Button(
                                onClick = {
                                    dialogTitle = "Renommer la liste"
                                    dialogValue = flashcardList.name
                                    dialogAction = { newName ->
                                        val updatedLists = lists.toMutableList()
                                        updatedLists[index] = flashcardList.copy(name = newName)
                                        updateLists(updatedLists)
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

        Button(
            onClick = {
                dialogTitle = "Nouvelle liste"
                dialogValue = ""
                dialogAction = { newName ->
                    val updatedLists = lists.toMutableList()
                    updatedLists.add(0, FlashcardList(name = newName))
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

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogTitle) },
            text = {
                TextField(
                    value = dialogValue,
                    onValueChange = { dialogValue = it },
                    label = { Text("Nom de la liste")},
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogValue.isNotBlank()) {
                            dialogAction(dialogValue)
                            showDialog = false
                        }
                    },
                    modifier = Modifier.height(50.dp)
                ) { Text("OK") }
            },
            dismissButton = {
                Button(
                    onClick = { showDialog = false },
                    modifier = Modifier.height(50.dp)
                ) { Text("Annuler") }
            }
        )
    }
}


data class FlashcardElement(val name: String, val definition: String, var easeFactor: Double = 2.5, var interval: Int = 0, var repetitions: Int = 0, var lastReview: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("definition", definition)
            put("easeFactor", easeFactor)
            put("interval", interval)
            put("repetitions", repetitions)
            put("lastReview", lastReview)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): FlashcardElement {
            return FlashcardElement(
                name = json.getString("name"),
                definition = json.getString("definition"),
                easeFactor = json.optDouble("easeFactor", 2.5),
                interval = json.optInt("interval", 0),
                repetitions = json.optInt("repetitions", 0),
                lastReview = json.optLong("lastReview", System.currentTimeMillis())
            )
        }

        fun listToJsonString(elements: List<FlashcardElement>): String {
            val jsonArray = JSONArray()
            elements.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }

        fun listFromJsonString(jsonString: String): List<FlashcardElement> {
            return try {
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

@Composable
fun FlashcardElementsScreen(listId: String, onBack: () -> Unit, navController: NavController) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    // Use ID to store/retrieve elements
    val key = stringPreferencesKey("elements_$listId")
    val elementsJson by context.dataStore.data
        .map { prefs -> prefs[key] }
        .collectAsState(initial = null)

    val elements = remember(elementsJson) {
        elementsJson?.let { FlashcardElement.listFromJsonString(it) } ?: emptyList()
    }

    fun updateElements(newElements: List<FlashcardElement>) {
        scope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[key] = FlashcardElement.listToJsonString(newElements)
            }
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }
    var dialogDefinition by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(listName, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(elements) { index, element ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(element.name, style = MaterialTheme.typography.titleMedium)
                        Text(element.definition, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    editingIndex = index
                                    dialogName = element.name
                                    dialogDefinition = element.definition
                                    showDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Edit, contentDescription = "Éditer") }

                            Button(
                                onClick = {
                                    val updated = elements.toMutableList()
                                    updated.removeAt(index)
                                    updateElements(updated)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Delete, contentDescription = "Supprimer") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { navController.navigate("game/$listId") },
                modifier = Modifier.weight(1f).height(80.dp)
            ) { Text("Jouer") }
            Button(
                onClick = {
                    dialogName = ""
                    dialogDefinition = ""
                    editingIndex = null
                    showDialog = true
                },
                modifier = Modifier.weight(1f).height(80.dp)
            ) { Text("Ajouter") }
        }
    }


    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editingIndex == null) "Nouvel élément" else "Modifier élément") },
            text = {
                Column {
                    TextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text("Nom") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    TextField(
                        value = dialogDefinition,
                        onValueChange = { dialogDefinition = it },
                        label = { Text("Définition") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogName.isNotBlank() && dialogDefinition.isNotBlank()) {
                            val updated = elements.toMutableList()
                            val newElement = FlashcardElement(dialogName, dialogDefinition)
                            if (editingIndex == null) updated.add(0, newElement)
                            else updated[editingIndex!!] = newElement
                            updateElements(updated)
                            showDialog = false
                        }
                    },
                    modifier = Modifier.height(50.dp)
                ) { Text("OK") }
            },
            dismissButton = {
                Button(
                    onClick = { showDialog = false },
                    modifier = Modifier.height(50.dp)
                ) { Text("Annuler") }
            }
        )
    }
}

@Composable
fun FlashcardGameScreen(listId: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = context.dataStore

    var flashcards by remember { mutableStateOf(listOf<FlashcardElement>()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var showAnswer by remember { mutableStateOf(false) }

    // Load flashcards
    LaunchedEffect(listId) {
        val key = "flashcards_$listId"
        val saved = dataStore.data.map { it[stringPreferencesKey(key)] ?: "[]" }.first()
        flashcards = FlashcardElement.listFromJsonString(saved)
    }

    fun saveFlashcards() {
        scope.launch {
            val key = stringPreferencesKey("flashcards_$listId")
            dataStore.edit { it[key] = FlashcardElement.listToJsonString(flashcards) }
        }
    }

    fun updateCard(correct: Boolean) {
        val card = flashcards[currentIndex]
        if (correct) {
            card.repetitions++
            card.easeFactor = maxOf(1.3, card.easeFactor + 0.1 - (5 - 5) * 0.08) // simplified quality = 5
            card.interval = when (card.repetitions) {
                1 -> 1
                2 -> 6
                else -> (card.interval * card.easeFactor).roundToInt()
            }
        } else {
            card.repetitions = 0
            card.interval = 1
            card.easeFactor = maxOf(1.3, card.easeFactor - 0.2)
        }
        card.lastReview = System.currentTimeMillis()
        saveFlashcards()
    }

    fun nextCard() {
        val now = System.currentTimeMillis()
        val dueCards = flashcards.filter { now - it.lastReview >= it.interval * 24 * 60 * 60 * 1000L }
        if (dueCards.isNotEmpty()) {
            currentIndex = flashcards.indexOf(dueCards.random())
        }
        showAnswer = false
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (flashcards.isNotEmpty()) {
            Card(modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(200.dp)
                .clickable { showAnswer = true }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = if (showAnswer) flashcards[currentIndex].definition else flashcards[currentIndex].name, fontSize = 24.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                Button(onClick = { updateCard(false); nextCard() }) { Text("Jsp") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { updateCard(true); nextCard() }) { Text("EZ") }
            }
        } else {
            Text("Fini !", fontSize = 20.sp)
        }
    }
}

@Composable
private fun ScreenTemplate(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        content()
    }
}