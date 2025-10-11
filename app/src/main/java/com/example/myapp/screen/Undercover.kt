package com.example.myapp.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.random.Random

data class Player(
    val name: String,
    var role: PlayerRole,
    var word: String,
    var isEliminated: Boolean = false,
    var points: Int = 0
)

enum class PlayerRole {
    CIVILIAN, IMPOSTOR, MR_WHITE
}

data class GameSettings(
    val playerCount: Int = 4,
    val impostorCount: Int? = null,
    val mrWhiteCount: Int = 1,
    val randomComposition: Boolean = true,
    val impostorsKnowRole: Boolean = false
)

val wordPairs = listOf(
    "Dog" to "Cat",
    "Coffee" to "Tea",
    "Pizza" to "Burger",
    "Car" to "Bike",
    "Summer" to "Winter",
    "Ocean" to "Lake",
    "Book" to "Magazine",
    "Guitar" to "Piano",
    "Apple" to "Orange",
    "Football" to "Basketball"
)

@Composable
fun UndercoverScreen(onBack: () -> Unit) {
    var gameState by remember { mutableStateOf<GameState>(GameState.Settings) }
    var settings by remember { mutableStateOf(GameSettings()) }
    var players by remember { mutableStateOf<List<Player>>(emptyList()) }
    var currentPlayerIndex by remember { mutableIntStateOf(0) }
    var currentRound by remember { mutableIntStateOf(1) }
    var allPlayersScores by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    BackHandler {
        if (gameState is GameState.Settings) {
            onBack()
        } else {
            gameState = GameState.Settings
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (gameState is GameState.Settings) {
                    onBack()
                } else {
                    gameState = GameState.Settings
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Undercover",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = gameState) {
            is GameState.Settings -> {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    onStart = {
                        players = generatePlayers(settings)
                        currentPlayerIndex = 0
                        currentRound = 1
                        gameState = GameState.PlayerSetup(0)
                    }
                )
            }
            is GameState.PlayerSetup -> {
                PlayerSetupScreen(
                    playerIndex = state.playerIndex,
                    totalPlayers = settings.playerCount,
                    onNameEntered = { name ->
                        if (state.playerIndex < settings.playerCount - 1) {
                            gameState = GameState.PlayerSetup(state.playerIndex + 1)
                        } else {
                            assignRolesAndWords(players, settings)
                            gameState = GameState.ShowWord(0, false)
                        }
                        players = players.toMutableList().apply {
                            if (state.playerIndex < size) {
                                this[state.playerIndex] = this[state.playerIndex].copy(name = name)
                            }
                        }
                    }
                )
            }
            is GameState.ShowWord -> {
                ShowWordScreen(
                    player = players[state.playerIndex],
                    playerIndex = state.playerIndex,
                    totalPlayers = players.size,
                    showWord = state.showWord,
                    settings = settings,
                    onShowWord = {
                        gameState = GameState.ShowWord(state.playerIndex, true)
                    },
                    onNext = {
                        if (state.playerIndex < players.size - 1) {
                            gameState = GameState.ShowWord(state.playerIndex + 1, false)
                        } else {
                            currentPlayerIndex = Random.nextInt(players.filter { !it.isEliminated }.size)
                            gameState = GameState.RoundMenu
                        }
                    }
                )
            }
            is GameState.RoundMenu -> {
                RoundMenuScreen(
                    round = currentRound,
                    players = players,
                    currentPlayerIndex = currentPlayerIndex,
                    onContinue = {
                        gameState = GameState.Voting
                    }
                )
            }
            is GameState.Voting -> {
                VotingScreen(
                    players = players,
                    onPlayerEliminated = { eliminatedPlayer ->
                        players = players.toMutableList().apply {
                            val idx = indexOfFirst { it.name == eliminatedPlayer.name }
                            if (idx != -1) {
                                this[idx] = this[idx].copy(isEliminated = true)
                            }
                        }

                        val activePlayers = players.filter { !it.isEliminated }
                        val civilians = activePlayers.filter { it.role == PlayerRole.CIVILIAN }
                        val impostors = activePlayers.filter { it.role == PlayerRole.IMPOSTOR }
                        val mrWhites = activePlayers.filter { it.role == PlayerRole.MR_WHITE }

                        if (impostors.isEmpty() && mrWhites.isEmpty()) {
                            // Civilians win
                            players.filter { it.role == PlayerRole.CIVILIAN }.forEach {
                                allPlayersScores = allPlayersScores.toMutableMap().apply {
                                    this[it.name] = (this[it.name] ?: 0) + 2
                                }
                            }
                            gameState = GameState.GameOver(true, eliminatedPlayer)
                        } else if (civilians.size <= 1) {
                            // Impostors/Mr. White win
                            players.filter { it.role != PlayerRole.CIVILIAN }.forEach {
                                allPlayersScores = allPlayersScores.toMutableMap().apply {
                                    this[it.name] = (this[it.name] ?: 0) + 2
                                }
                            }
                            gameState = GameState.GameOver(false, eliminatedPlayer)
                        } else {
                            gameState = GameState.EliminationResult(eliminatedPlayer, false)
                        }
                    }
                )
            }
            is GameState.EliminationResult -> {
                EliminationResultScreen(
                    player = state.player,
                    onNextRound = {
                        currentRound++
                        val activePlayers = players.filter { !it.isEliminated }
                        if (activePlayers.isNotEmpty()) {
                            currentPlayerIndex = Random.nextInt(activePlayers.size)
                        }
                        gameState = GameState.RoundMenu
                    }
                )
            }
            is GameState.GameOver -> {
                GameOverScreen(
                    civiliansWon = state.civiliansWon,
                    lastEliminated = state.lastEliminated,
                    allScores = allPlayersScores,
                    onNewGame = {
                        gameState = GameState.Settings
                    }
                )
            }
        }
    }
}

sealed class GameState {
    object Settings : GameState()
    data class PlayerSetup(val playerIndex: Int) : GameState()
    data class ShowWord(val playerIndex: Int, val showWord: Boolean) : GameState()
    object RoundMenu : GameState()
    object Voting : GameState()
    data class EliminationResult(val player: Player, val gameOver: Boolean) : GameState()
    data class GameOver(val civiliansWon: Boolean, val lastEliminated: Player) : GameState()
}

@Composable
fun SettingsScreen(
    settings: GameSettings,
    onSettingsChange: (GameSettings) -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(24.dp))

        // Player count
        NumberSetting(
            label = "Number of Players",
            value = settings.playerCount,
            onValueChange = {
                val newCount = it.coerceIn(3, 20)
                onSettingsChange(settings.copy(playerCount = newCount))
            },
            min = 3,
            max = 20
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Random composition toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Random Composition")
            Switch(
                checked = settings.randomComposition,
                onCheckedChange = { onSettingsChange(settings.copy(randomComposition = it)) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Impostor count (disabled if random)
        NumberSetting(
            label = "Number of Impostors",
            value = settings.impostorCount ?: 0,
            onValueChange = {
                val maxImpostors = (settings.playerCount - settings.mrWhiteCount) / 2
                val newCount = it.coerceIn(1, maxImpostors)
                onSettingsChange(settings.copy(impostorCount = newCount))
            },
            min = 1,
            max = (settings.playerCount - settings.mrWhiteCount) / 2,
            enabled = !settings.randomComposition
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Mr. White count (disabled if random)
        NumberSetting(
            label = "Number of Mr. Whites",
            value = settings.mrWhiteCount,
            onValueChange = {
                onSettingsChange(settings.copy(mrWhiteCount = it.coerceIn(0, 1)))
            },
            min = 0,
            max = 1,
            enabled = !settings.randomComposition
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Impostors know role toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Impostors Know Their Role")
            Switch(
                checked = settings.impostorsKnowRole,
                onCheckedChange = { onSettingsChange(settings.copy(impostorsKnowRole = it)) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Start Game", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun NumberSetting(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    enabled: Boolean = true
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onValueChange(value - 1) },
                enabled = enabled && value > min
            ) {
                Text("-", style = MaterialTheme.typography.headlineMedium)
            }

            Text(
                text = if (enabled) value.toString() else "-",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Button(
                onClick = { onValueChange(value + 1) },
                enabled = enabled && value < max
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
fun PlayerSetupScreen(
    playerIndex: Int,
    totalPlayers: Int,
    onNameEntered: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Player ${playerIndex + 1} of $totalPlayers",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Enter your name:")

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isNotBlank()) {
                    onNameEntered(name)
                }
            },
            enabled = name.isNotBlank()
        ) {
            Text("Confirm")
        }
    }
}

@Composable
fun ShowWordScreen(
    player: Player,
    playerIndex: Int,
    totalPlayers: Int,
    showWord: Boolean,
    settings: GameSettings,
    onShowWord: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!showWord) {
            Text(
                "${player.name}, it's your turn!",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Make sure others don't see the screen.",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onShowWord) {
                Text("Show My Word")
            }
        } else {
            Text(
                player.name,
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (player.role == PlayerRole.MR_WHITE) {
                Text(
                    "You are Mr. White!",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.Red,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "You have no word. Listen carefully and blend in!",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            } else if (player.role == PlayerRole.IMPOSTOR && settings.impostorsKnowRole) {
                Text(
                    "You are an Impostor!",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color(0xFFFF6600),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Your word:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    player.word,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    "Your word:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    player.word,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onNext) {
                Text(if (playerIndex < totalPlayers - 1) "Next Player" else "Start Game")
            }
        }
    }
}

@Composable
fun RoundMenuScreen(
    round: Int,
    players: List<Player>,
    currentPlayerIndex: Int,
    onContinue: () -> Unit
) {
    val activePlayers = players.filter { !it.isEliminated }
    val startingPlayer = activePlayers.getOrNull(currentPlayerIndex)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Round $round",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Instructions:",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "• Each player says a word related to their secret word",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "• Never say your secret word or words from the same family",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "• Civilians: find the impostors and Mr. White",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (startingPlayer != null) {
            Text(
                "Starting player: ${startingPlayer.name}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Continue clockwise",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onContinue) {
            Text("Proceed to Voting")
        }
    }
}

@Composable
fun VotingScreen(
    players: List<Player>,
    onPlayerEliminated: (Player) -> Unit
) {
    var selectedPlayer by remember { mutableStateOf<Player?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }

    val activePlayers = players.filter { !it.isEliminated }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Voting Phase",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Vote for the player you want to eliminate:",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(activePlayers) { player ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedPlayer = player
                            showConfirmation = true
                        },
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Text(
                        player.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showConfirmation && selectedPlayer != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirm Elimination") },
            text = { Text("Are you sure you want to eliminate ${selectedPlayer?.name}?") },
            confirmButton = {
                Button(onClick = {
                    selectedPlayer?.let { onPlayerEliminated(it) }
                    showConfirmation = false
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                Button(onClick = { showConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EliminationResultScreen(
    player: Player,
    onNextRound: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${player.name} was eliminated!",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Role:",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        val roleText = when (player.role) {
            PlayerRole.CIVILIAN -> "Civilian"
            PlayerRole.IMPOSTOR -> "Impostor"
            PlayerRole.MR_WHITE -> "Mr. White"
        }

        val roleColor = when (player.role) {
            PlayerRole.CIVILIAN -> Color.Blue
            PlayerRole.IMPOSTOR -> Color(0xFFFF6600)
            PlayerRole.MR_WHITE -> Color.Red
        }

        Text(
            roleText,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = roleColor
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (player.role != PlayerRole.MR_WHITE) {
            Text(
                "Word: ${player.word}",
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onNextRound) {
            Text("Next Round")
        }
    }
}

@Composable
fun GameOverScreen(
    civiliansWon: Boolean,
    lastEliminated: Player,
    allScores: Map<String, Int>,
    onNewGame: () -> Unit
) {
    var showScoreboard by remember { mutableStateOf(false) }

    if (!showScoreboard) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Game Over!",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                if (civiliansWon) "Civilians Win!" else "Impostors/Mr. White Win!",
                style = MaterialTheme.typography.headlineMedium,
                color = if (civiliansWon) Color.Blue else Color(0xFFFF6600)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "${lastEliminated.name} was eliminated",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = { showScoreboard = true }) {
                Text("View Scoreboard")
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scoreboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sortedScores = allScores.entries.sortedByDescending { it.value }
                items(sortedScores) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                entry.key,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "${entry.value} pts",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onNewGame,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("New Game")
            }
        }
    }
}

fun generatePlayers(settings: GameSettings): List<Player> {
    return (0 until settings.playerCount).map { i ->
        Player(
            name = "Player ${i + 1}",
            role = PlayerRole.CIVILIAN,
            word = ""
        )
    }
}

fun assignRolesAndWords(players: List<Player>, settings: GameSettings) {
    val wordPair = wordPairs.random()
    val civilianWord = wordPair.first
    val impostorWord = wordPair.second

    val indices = players.indices.toMutableList()
    indices.shuffle()

    var impostorCount = settings.impostorCount
    val mrWhiteCount = settings.mrWhiteCount

    if (settings.randomComposition) {
        val maxImpostors = (settings.playerCount - mrWhiteCount) / 2
        impostorCount = Random.nextInt(1, maxImpostors + 1)
    }

    var assigned = 0

    // Assign Mr. White
    repeat(mrWhiteCount) {
        if (assigned < indices.size) {
            val idx = indices[assigned]
            players[idx].role = PlayerRole.MR_WHITE
            players[idx].word = ""
            assigned++
        }
    }

    // Assign Impostors
    repeat(impostorCount ?: 0) {
        if (assigned < indices.size) {
            val idx = indices[assigned]
            players[idx].role = PlayerRole.IMPOSTOR
            players[idx].word = impostorWord
            assigned++
        }
    }

    // Assign Civilians
    for (i in assigned until indices.size) {
        val idx = indices[i]
        players[idx].role = PlayerRole.CIVILIAN
        players[idx].word = civilianWord
    }
}