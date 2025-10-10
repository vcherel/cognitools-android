package com.example.myapp

import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random

val Context.dataStore by preferencesDataStore("flashcards")

data class FlashcardList(val id: String = UUID.randomUUID().toString(), val name: String) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
        }
    }
    companion object {
        private val cache = mutableMapOf<String, List<FlashcardList>>()
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
            return cache.getOrPut(jsonString) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}

fun exportFlashcards(lists: List<FlashcardList>, allFlashcards: List<FlashcardElement>) {
    val flashcardsMap = allFlashcards.groupBy { it.listId }
    val exportJson = JSONObject().apply {
        put("lists", JSONArray().apply {
            lists.forEach { list ->
                put(JSONObject().apply {
                    put("name", list.name)
                    put("flashcards", JSONArray().apply {
                        flashcardsMap[list.id]?.forEach { card ->
                            put(JSONObject().apply {
                                put("name", card.name)
                                put("definition", card.definition)
                            })
                        }
                    })
                })
            }
        })
    }

    val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloadsFolder, "flashcards_export.json")
    FileOutputStream(file).use { it.write(exportJson.toString().toByteArray()) }
}

suspend fun loadFlashcardData(context: Context): Pair<List<FlashcardList>, List<FlashcardElement>> {
    val prefs = context.dataStore.data.first()
    val listsJson = prefs[stringPreferencesKey("lists")] ?: "[]"
    val lists = FlashcardList.listFromJsonString(JSONArray(listsJson).toString())

    val allFlashcards = lists.flatMap { list ->
        val flashcardsJson = prefs[stringPreferencesKey("elements_${list.id}")] ?: "[]"
        FlashcardElement.listFromJsonString(JSONArray(flashcardsJson).toString())
    }

    return lists to allFlashcards
}

fun importFlashcards(context: Context) {
    val jsonString = context.assets.open("flashcards_export.json").bufferedReader().use { it.readText() }

    CoroutineScope(Dispatchers.IO).launch {
        val success = importFlashcardsData(context, jsonString)
        withContext(Dispatchers.Main) {
            if (success) {
                Toast.makeText(context, "Flashcards imported successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to import flashcards", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

suspend fun importFlashcardsData(context: Context, jsonString: String): Boolean {
    return try {
        val json = JSONObject(jsonString)
        val listsJsonArray = json.getJSONArray("lists")

        // Read existing lists and elements
        val prefs = context.dataStore.data.first()
        val existingListsJson = prefs[stringPreferencesKey("lists")]?.let { JSONArray(it) } ?: JSONArray()
        val existingLists = mutableListOf<FlashcardList>()
        for (i in 0 until existingListsJson.length()) {
            val obj = existingListsJson.getJSONObject(i)
            existingLists.add(FlashcardList(id = obj.getString("id"), name = obj.getString("name")))
        }

        val existingElements = mutableListOf<FlashcardElement>()
        existingLists.forEach { list ->
            val key = stringPreferencesKey("elements_${list.id}")
            val cardsJson = prefs[key]?.let { JSONArray(it) } ?: JSONArray()
            for (i in 0 until cardsJson.length()) {
                val obj = cardsJson.getJSONObject(i)
                existingElements.add(
                    FlashcardElement(
                        listId = obj.getString("listId"),
                        name = obj.getString("name"),
                        definition = obj.getString("definition")
                    )
                )
            }
        }

        // Parse imported lists and cards
        val newLists = mutableListOf<FlashcardList>()
        val newElements = mutableListOf<FlashcardElement>()
        for (i in 0 until listsJsonArray.length()) {
            val listJson = listsJsonArray.getJSONObject(i)
            val list = FlashcardList(name = listJson.getString("name"))
            newLists.add(list)

            val flashcardsJsonArray = listJson.getJSONArray("flashcards")
            for (j in 0 until flashcardsJsonArray.length()) {
                val cardJson = flashcardsJsonArray.getJSONObject(j)
                newElements.add(
                    FlashcardElement(
                        listId = list.id,
                        name = cardJson.getString("name"),
                        definition = cardJson.getString("definition")
                    )
                )
            }
        }

        context.dataStore.edit { store ->
            val updatedListsJson = JSONArray(existingLists.map { JSONObject().apply { put("id", it.id); put("name", it.name) } })
            newLists.forEach { list ->
                updatedListsJson.put(JSONObject().apply { put("id", list.id); put("name", list.name) })
            }
            store[stringPreferencesKey("lists")] = updatedListsJson.toString()

            newLists.forEach { list ->
                val key = stringPreferencesKey("elements_${list.id}")
                val cardsJson = JSONArray()
                newElements.filter { it.listId == list.id }.forEach { card ->
                    cardsJson.put(JSONObject().apply { put("listId", card.listId); put("name", card.name); put("definition", card.definition) })
                }
                store[key] = cardsJson.toString()
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit, navController: NavController) {
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

        // Calculate total due count and send notification if >= 50
        val totalDueCount = dueCountMap.values.sum()

        LaunchedEffect(totalDueCount) {
            if (totalDueCount >= 50) {
                sendReviewNotification(context, totalDueCount)
            }
        }

        // List of flashcard lists
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(items = lists, key = { _, item -> item.id }) { index, flashcardList ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable { navController.navigate("elements/${flashcardList.id}") }
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
                                        confirmButton = {
                                            TextButton(onClick = {
                                                val updated = lists.toMutableList()
                                                updated.removeAt(index)
                                                updateLists(updated)
                                                showDeleteDialog = false
                                            }) { Text("Oui t'inquiète") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = {
                                                showDeleteDialog = false
                                            }) { Text("Oula non merci") }
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
                                                    Icon(Icons.Default.ContentPaste, contentDescription = "Coller")
                                                }

                                                Button(
                                                    onClick = { showBulkImportDialog = false },
                                                    modifier = Modifier.height(50.dp)
                                                ) {
                                                    Text("Annuler")
                                                }

                                                Button(
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
                                                ) {
                                                    Text("Importer")
                                                }
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

data class FlashcardElement(
    val id: String = UUID.randomUUID().toString(),
    val listId: String,
    val name: String,
    val definition: String,
    var easeFactor: Double = 2.5,
    var interval: Int = 0,
    var repetitions: Int = 0,
    var lastReview: Long = System.currentTimeMillis(),
    var totalWins: Int = 0,
    var totalLosses: Int = 0,
    var score: Double? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("listId", listId)
            put("name", name)
            put("definition", definition)
            put("easeFactor", easeFactor)
            put("interval", interval)
            put("repetitions", repetitions)
            put("lastReview", lastReview)
            put("totalWins", totalWins)
            put("totalLosses", totalLosses)
            put("score", score)
        }
    }

    companion object {
        private val cache = mutableMapOf<String, List<FlashcardElement>>()
        fun fromJson(json: JSONObject): FlashcardElement {
            return FlashcardElement(
                id = json.optString("id", UUID.randomUUID().toString()), // optString to handle old data
                listId = json.getString("listId"),
                name = json.getString("name"),
                definition = json.getString("definition"),
                easeFactor = json.optDouble("easeFactor", 2.5),
                interval = json.optInt("interval", 0),
                repetitions = json.optInt("repetitions", 0),
                lastReview = json.optLong("lastReview", System.currentTimeMillis()),
                totalWins = json.optInt("totalWins", 0),
                totalLosses = json.optInt("totalLosses", 0),
                score = json.optDouble("score").takeIf { !json.isNull("score") }
            )
        }

        fun listToJsonString(elements: List<FlashcardElement>): String {
            val jsonArray = JSONArray()
            elements.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }

        fun listFromJsonString(jsonString: String): List<FlashcardElement> {
            return cache.getOrPut(jsonString) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    List(jsonArray.length()) { i -> fromJson(jsonArray.getJSONObject(i)) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}
fun isDue(card: FlashcardElement): Boolean {
    val now = System.currentTimeMillis()
    val intervalMs = card.interval * 60 * 1000L // interval is in minutes
    return (now - card.lastReview) >= intervalMs
}

@Composable
fun FlashcardElementsScreen(listId: String, onBack: () -> Unit, navController: NavController) {
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
                        .padding(vertical = 4.dp)
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
        AlertDialog(
            onDismissRequest = { elementToDelete = null },
            title = { Text("T'es sûr ??") },
            confirmButton = {
                TextButton(onClick = {
                    val updated = elements.filterNot { it.id == elementToDelete!!.id }
                    updateElements(updated)
                    elementToDelete = null
                }) { Text("Oui t'inquiète") }
            },
            dismissButton = {
                TextButton(onClick = { elementToDelete = null }) { Text("Oula non merci") }
            }
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

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editingIndex == null) "Nouvel élément" else "Modifier élément") },
            text = {
                Column {
                    TextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text("Nom") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusRequester2.requestFocus() }
                        )
                    )
                    TextField(
                        value = dialogDefinition,
                        onValueChange = { dialogDefinition = it },
                        label = { Text("Définition") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { saveElement() }),
                        modifier = Modifier.focusRequester(focusRequester2)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { saveElement() },
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = stringPreferencesKey("elements_$listId")
    val elementsJson by context.dataStore.data
        .map { prefs -> prefs[key] }
        .collectAsState(initial = null)

    var allElements by remember { mutableStateOf<List<FlashcardElement>>(emptyList()) }
    var dueCards by remember { mutableStateOf<List<FlashcardElement>>(emptyList()) }
    var currentCard by remember { mutableStateOf<FlashcardElement?>(null) }
    var showDefinition by remember { mutableStateOf(false) }
    var cardOffset by remember { mutableFloatStateOf(0f) }
    var isProcessingSwipe by remember { mutableStateOf(false) }
    var showFront by remember { mutableStateOf(true) }

    BackHandler { onBack() }

    // Load and filter due cards
    LaunchedEffect(elementsJson) {
        elementsJson?.let {
            val loaded = FlashcardElement.listFromJsonString(it)
            allElements = loaded
            dueCards = loaded.filter { card -> isDue(card) }
            if (currentCard == null && dueCards.isNotEmpty()) {
                currentCard = dueCards.random()
            }
        }
    }

    fun updateCards(card: FlashcardElement, quality: Int /* quality = 2 if lost, 4 if won */): FlashcardElement {
        // Update ease factor
        var newEF = card.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
        if (newEF < 1.3) newEF = 1.3

        // Bonus if didn't see answer
        if (!showDefinition) {
            newEF *= 1.05
        }

        // Update repetitions
        val newReps = if (quality >= 3) card.repetitions + 1 else 0

        // Update total wins/losses
        val newWins = card.totalWins + if (quality >= 3) 1 else 0
        val newLosses = card.totalLosses + if (quality < 3) 1 else 0

        // Calculate new score
        val newScore = ((newWins.toDouble() / (newWins + newLosses)) * 10).coerceIn(0.0, 10.0)

        // Update interval
        val newInterval = when {
            // If we fail, the card comes again quickly
            quality < 3 -> {
                // Randomly send the card far away if we struggle too much with it
                val probability = ((2.5 - newScore) / 2.5 * 0.33).coerceIn(0.0, 0.33)
                if (newScore < 2.5 && Math.random() < probability) 61.0

                // 1/3 chance that the card come back a bit later
                if (Math.random() < 0.33) newScore.coerceAtLeast(1.0)

                else 0.0
            }

            // On the first win we have to wait 6 minutes
            newReps == 1 -> 6.0

            else -> {
                val randomMultiplier = 0.8 + Math.random() * 1.2
                card.interval * newEF * randomMultiplier
            }
        }

        return card.copy(
            easeFactor = newEF,
            interval = newInterval.toInt(),
            repetitions = newReps,
            lastReview = System.currentTimeMillis(),
            totalWins = newWins,
            totalLosses = newLosses,
            score = newScore
        )
    }


    // Function to save updated elements
    fun saveElements(updated: List<FlashcardElement>) {
        scope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[key] = FlashcardElement.listToJsonString(updated)
            }
        }
    }

    // Handle card swipe/answer
    fun handleAnswer(wasCorrect: Boolean) {
        if (isProcessingSwipe) return
        isProcessingSwipe = true

        currentCard?.let { card ->
            val quality = if (wasCorrect) 4 else 2 // 4 = correct, 2 = incorrect
            val updatedCard = updateCards(card, quality)

            // Update the card in allElements
            val updatedElements = allElements.map {
                if (it.name == card.name && it.definition == card.definition) updatedCard else it
            }
            allElements = updatedElements
            saveElements(updatedElements)

            // Update due cards list
            dueCards = updatedElements.filter { isDue(it) }

            // Move to next card (exclude current card)
            showDefinition = false
            val availableCards = dueCards.filter {
                it.id != card.id
            }
            currentCard = if (availableCards.isNotEmpty()) {
                val card = availableCards.random()
                showFront = Random.nextBoolean() // Randomly show front or back
                card
            } else if (dueCards.isNotEmpty()) {
                val card = dueCards.random()
                showFront = Random.nextBoolean()
                card
            } else {
                null
            }
            isProcessingSwipe = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val greenColor = Color(0xFF56C92F)
        val redColor = Color(0xFFDE1E1E)

        if (cardOffset != 0f) {
            val swipeProgress = (kotlin.math.abs(cardOffset) / 200f).coerceIn(0f, 1f)
            val shadowColor = if (cardOffset < 0) greenColor else redColor

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (cardOffset < 0) {
                            Brush.radialGradient(
                                colors = listOf(
                                    shadowColor.copy(alpha = swipeProgress * 0.8f),
                                    shadowColor.copy(alpha = swipeProgress * 0.6f),
                                    shadowColor.copy(alpha = swipeProgress * 0.2f),
                                    Color.Transparent
                                ),
                                center = Offset(0f, 0.5f),
                                radius = 800f
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(
                                    shadowColor.copy(alpha = swipeProgress * 0.8f),
                                    shadowColor.copy(alpha = swipeProgress * 0.6f),
                                    shadowColor.copy(alpha = swipeProgress * 0.2f),
                                    Color.Transparent
                                ),
                                center = Offset(Float.POSITIVE_INFINITY, 0.5f),
                                radius = 800f
                            )
                        }
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    text = "Cartes à réviser: ${dueCards.size}",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(32.dp))

            // Main content
            if (currentCard != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                            .offset { IntOffset(cardOffset.toInt(), 0) }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        when {
                                            cardOffset < -200 -> handleAnswer(true)  // Swipe left = Correct
                                            cardOffset > 200 -> handleAnswer(false)  // Swipe right = Incorrect
                                        }
                                        cardOffset = 0f
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    cardOffset += dragAmount.x
                                }
                            }
                            .clickable { if (!showDefinition) showDefinition = true },
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        val scoreColor = when (currentCard?.score?.toInt() ?: 0) {
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

                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Text(
                                    text = if (showFront != showDefinition) currentCard!!.name else currentCard!!.definition,
                                    style = MaterialTheme.typography.headlineMedium,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold
                                )

                                if (showDefinition) {
                                    Spacer(Modifier.height(24.dp))
                                    Text(
                                        text = if (showFront) currentCard!!.name else currentCard!!.definition,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Score circle at top right
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                                    .size(36.dp)
                                    .border(width = 3.dp, color = scoreColor, shape = CircleShape)
                            ) {
                                Text(
                                    "${currentCard?.score?.toInt() ?: 0}",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        shadow = if ((currentCard?.score?.toInt() ?: 0) <= 3 || currentCard?.score?.toInt() == 10) null else Shadow(
                                            color = Color.Black,
                                            offset = Offset(0f, 0f),
                                            blurRadius = 4f
                                        )
                                    ),
                                    color = scoreColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))

                    // Alternative buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                        ) {
                            // Shadow layer
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 6.dp)
                                    .background(
                                        Color(0xFF2E7D32),
                                        RoundedCornerShape(16.dp)
                                    )
                            )
                            // Main button
                            Button(
                                onClick = { handleAnswer(true) },
                                modifier = Modifier.fillMaxSize(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = greenColor
                                ),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "YES",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                        ) {
                            // Shadow layer
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(y = 6.dp)
                                    .background(
                                        Color(0xFF7A0707),
                                        RoundedCornerShape(16.dp)
                                    )
                            )
                            // Main button
                            Button(
                                onClick = { handleAnswer(false) },
                                modifier = Modifier.fillMaxSize(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = redColor
                                ),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "NO",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // No cards due
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                    ) {
                        // Shadow layer
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(y = 6.dp)
                                .background(Color(0xFF2E7D32), RoundedCornerShape(24.dp))
                        )
                        // Main icon background
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(greenColor, RoundedCornerShape(24.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Félicitations !",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Vous avez révisé toutes les cartes disponibles",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Retour")
                    }
                }
            }
        }
    }
}