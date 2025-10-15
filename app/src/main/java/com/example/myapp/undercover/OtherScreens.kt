package com.example.myapp.undercover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val MAX_NAME_LENGTH = 30

@Composable
fun PlayerSetupScreen(
    playerIndex: Int,
    totalPlayers: Int,
    existingNames: List<String>,
    onNameEntered: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var showConfirmationDialog by remember { mutableStateOf(false) }

    val isNameValid by remember {
        derivedStateOf {
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
                    existingNames.none { it.equals(name, ignoreCase = true) }
        }
    }

    val errorMessage by remember {
        derivedStateOf {
            when {
                name.isBlank() -> null
                name.length > MAX_NAME_LENGTH -> "Name too long (max $MAX_NAME_LENGTH characters)"
                existingNames.any { it.equals(name, ignoreCase = true) } -> "Name already taken"
                else -> null
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Player ${playerIndex + 1} of $totalPlayers", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Enter your name:")
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (isNameValid) {
                        showConfirmationDialog = true
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (isNameValid) {
                        showConfirmationDialog = true
                    }
                },
                enabled = isNameValid
            ) {
                Text("OK")
            }
        }
    }

    if (showConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmationDialog = false },
            title = { Text("Confirm Name") },
            text = {
                Text("Are you sure you want to use \"$name\"?\n\nReady to see your secret word and role? Make sure no one else is watching!")
            },
            confirmButton = {
                TextButton(onClick = {
                    onNameEntered(name)
                    name = ""
                    showConfirmationDialog = false
                }) {
                    Text("Yes, I'm ready")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConfirmationDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}


@Composable
fun ShowWordScreen(
    player: Player,
    playerIndex: Int,
    totalPlayers: Int,
    settings: GameSettings,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            player.name,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (player.role == PlayerRole.MR_WHITE) {
            Text(
                "You are Mr. White!",
                style = MaterialTheme.typography.headlineLarge,
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

@Composable
fun PlayScreen(
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

    val activePlayers by remember { derivedStateOf { players.activePlayers() } }

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

        Text(
            player.role.displayName(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = player.role.displayColor()
        )

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
    players: List<Player>,
    gameWord: String,
    mrWhiteGuesses: List<String>,
    onContinue: () -> Unit
) {
    val activePlayers by remember { derivedStateOf { players.activePlayers() } }
    val activeRoles by remember { derivedStateOf { activePlayers.map { it.role }.toSet() } }

    val winnerText = when {
        civiliansWon -> "Civilians Win!"
        lastEliminated.role == PlayerRole.MR_WHITE -> "Mr White (${lastEliminated.name}) Wins!"
        else -> when {
            activeRoles.contains(PlayerRole.MR_WHITE) && activeRoles.contains(PlayerRole.IMPOSTOR) ->
                "Impostor and Mr White Win!"
            activeRoles.contains(PlayerRole.IMPOSTOR) -> "Impostor Win!"
            activeRoles.contains(PlayerRole.MR_WHITE) -> "Mr White Win!"
            else -> "Impostors Win!"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val headerText = if (lastEliminated.role == PlayerRole.MR_WHITE && !civiliansWon) {
            "Bien joué c'était ça!"
        } else {
            "Game Over!"
        }

        Text(
            headerText,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            winnerText,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!(lastEliminated.role == PlayerRole.MR_WHITE && !civiliansWon) && mrWhiteGuesses.isEmpty()) {
            Text(
                "${lastEliminated.name} was eliminated",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Role: ${lastEliminated.role.displayName()}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = lastEliminated.role.displayColor()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "The word was $gameWord",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )

        if (mrWhiteGuesses.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            val message = when (mrWhiteGuesses.size) {
                1 -> "Mr White tried to guess \"${mrWhiteGuesses[0]}\" but it was not correct."
                else -> {
                    val guessesString = mrWhiteGuesses.joinToString(separator = "\", \"", prefix = "\"", postfix = "\"")
                    "Mr Whites tried to guess $guessesString but it was not correct."
                }
            }

            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onContinue) {
            Text("View Leaderboard")
        }
    }
}

@Composable
fun LeaderboardScreen(
    allScores: Map<String, Int>,
    onBackToMenu: () -> Unit,
    onNewGame: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Leaderboard",
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

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onBackToMenu) {
            Text("Back to Main Menu")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onNewGame) {
            Text("Play Again")
        }
    }
}


@Composable
fun MrWhiteGuessScreen(
    lastEliminated: Player,
    scenario: MrWhiteScenario,
    onGuessSubmitted: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var guessedWord by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    // Auto focus input field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (scenario) {

            // CASE 1 — Eliminated Mr White
            is MrWhiteScenario.EliminatedMrWhite -> {
                Text(
                    text = "${lastEliminated.name} was eliminated and was Mr. White!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "You can now try to guess the secret word.",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "If you guess correctly, you win. Otherwise, you're eliminated for good.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }

            // CASE 2 — Final Two Players (Mr White + Impostor/Civilian)
            is MrWhiteScenario.FinalTwo -> {
                val mrWhite = scenario.mrWhite
                val opponent = scenario.opponent

                Text(
                    text = "${lastEliminated.name} (${lastEliminated.role.displayName()}) was just eliminated!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = lastEliminated.role.displayColor(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Only two players remain!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "${mrWhite.name} (Mr. White) vs ${opponent.name} (${opponent.role.displayName()})",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Mr. White can now guess the secret word.",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "If correct, ${mrWhite.name} wins! Otherwise, ${opponent.name} wins.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }

            // CASE 3 — Only Mr Whites Remaining
            is MrWhiteScenario.OnlyMrWhitesLeft -> {
                val mrWhites = scenario.activeMrWhites
                val currentGuesser = scenario.currentGuesser

                Text(
                    text = "Only Mr. Whites remain!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "There are ${mrWhites.size} Mr. Whites left in the game.",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "It's ${currentGuesser.name}'s turn to guess the secret word.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "If the guess is correct, all Mr. Whites win. If wrong, others get their chance.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Common input UI (for all cases)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = guessedWord,
            onValueChange = { guessedWord = it },
            label = { Text("Enter your guess") },
            placeholder = { Text("What is the secret word?") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (guessedWord.isNotBlank()) showConfirmation = true
                }
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { showConfirmation = true },
            enabled = guessedWord.isNotBlank()
        ) {
            Text("Submit Guess")
        }
    }

    // Confirmation Dialog
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirm Guess") },
            text = { Text("Submit '$guessedWord' as your final answer?") },
            confirmButton = {
                Button(onClick = {
                    onGuessSubmitted(guessedWord.trim())
                    showConfirmation = false
                    guessedWord = ""
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