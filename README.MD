I started the creation of an Android App, to group multiple functionnalities. I want it to be minimalistic and fast.

I am now creating a flashcard component, where the final goal is to learn word and associated definitions.
Here is the functionnality I would like :
- Create list (DONE)
- Edit / Delete list (DONE)
- Add elements in list (TODO)
- Edit elements from list (TODO)
- Clever training (TODO)
- Import export data to internal files (TODO)


I have this code in my MainActivity :
@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
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
                                onClick = { /* open the list */ },
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

Give me a simple code to open a list, and add elements inside of it.
When I open a list, I want it to open a brand new screen, which looks exactly like the list screen, but with elements instead of lists.
Put comments so that I know what is going on with the variables




This app is a bit slow
Show me what is the best thing I could to to improve performances.
Don't rewrite the code, make a short answer.

The things that takes time are (from longer to short wait time):