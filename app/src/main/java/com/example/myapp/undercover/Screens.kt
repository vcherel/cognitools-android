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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun PlayerSetupScreen(
    playerIndex: Int,
    totalPlayers: Int,
    onNameEntered: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

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

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (name.isNotBlank()) {
                    onNameEntered(name)
                    name = ""
                }
            }),
            modifier = Modifier.focusRequester(focusRequester)
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
    var wordRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!wordRevealed) {
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

            Button(onClick = { wordRevealed = true }) {
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
    players: List<Player>,
    onNewGame: () -> Unit
) {
    var showScoreboard by remember { mutableStateOf(false) }

    if (!showScoreboard) {
        val winnerText = when {
            civiliansWon -> "Civilians Win!"
            lastEliminated.role == PlayerRole.MR_WHITE -> "Mr White Win!"
            else -> {
                val activeRoles = players.filter { !it.isEliminated }.map { it.role }.toSet()
                when {
                    activeRoles.contains(PlayerRole.MR_WHITE) && activeRoles.contains(PlayerRole.IMPOSTOR) -> "Impostor and Mr White Win!"
                    activeRoles.contains(PlayerRole.IMPOSTOR) -> "Impostor Win!"
                    activeRoles.contains(PlayerRole.MR_WHITE) -> "Mr White Win!"
                    else -> "Impostors Win!"
                }
            }
        }

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
                winnerText,
                style = MaterialTheme.typography.headlineMedium,
                color = when (winnerText) {
                    "Civilians Win!" -> Color.Blue
                    "Mr White Win!" -> Color.Red
                    "Impostor Win!" -> Color(0xFFFF6600)
                    "Impostor and Mr White Win!" -> Color.Magenta
                    else -> Color.Black
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "${lastEliminated.name} was eliminated",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            val roleText = when (lastEliminated.role) {
                PlayerRole.CIVILIAN -> "Civilian"
                PlayerRole.IMPOSTOR -> "Impostor"
                PlayerRole.MR_WHITE -> "Mr. White"
            }

            val roleColor = when (lastEliminated.role) {
                PlayerRole.CIVILIAN -> Color.Blue
                PlayerRole.IMPOSTOR -> Color(0xFFFF6600)
                PlayerRole.MR_WHITE -> Color.Red
            }

            Text(
                "Role: $roleText",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = roleColor
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

@Composable
fun MrWhiteGuessScreen(
    player: Player,
    onGuessSubmitted: (String) -> Unit
) {
    var guessedWord by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

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
            "Role: Mr. White",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.Red
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Mr. White gets one chance to guess the word!",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "If correct, Mr. White wins!",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = guessedWord,
            onValueChange = { guessedWord = it },
            label = { Text("Enter your guess") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { showConfirmation = true },
            enabled = guessedWord.isNotBlank()
        ) {
            Text("Submit Guess")
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirm Guess") },
            text = { Text("Submit '$guessedWord' as your final answer?") },
            confirmButton = {
                Button(onClick = {
                    onGuessSubmitted(guessedWord.trim())
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