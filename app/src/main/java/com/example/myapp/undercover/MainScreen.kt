package com.example.myapp.undercover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun UndercoverScreen(onBack: () -> Unit) {
    var state by remember { mutableStateOf(UndercoverGameState()) }
    var showExitDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (state.gameState is GameState.Settings) {
            onBack()
        } else {
            showExitDialog = true
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Leave Game?") },
            text = { Text("Are you sure you want to leave the current game? Your progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    state = state.copy(gameState = GameState.Settings)
                    showExitDialog = false
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("No")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (state.gameState is GameState.Settings) {
                    onBack()
                } else {
                    state = state.copy(gameState = GameState.Settings)
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

        when (val gameState = state.gameState) {
            is GameState.Settings -> {
                SettingsScreen(
                    state = state,
                    onSettingsChange = { updatedState ->
                        state = updatedState
                    },
                    onStart = {
                        val assignedPlayers = generateAndAssignPlayers(state)
                        state = state.copy(
                            players = assignedPlayers,
                            currentPlayerIndex = 0,
                            currentRound = 1,
                            gameState = GameState.PlayerSetup(0, showWord = false)
                        )
                    }
                )
            }

            is GameState.PlayerSetup -> {
                HandlePlayerSetup(
                    gameState = gameState,
                    state = state,
                    onStateUpdate = { newState -> state = newState }
                )
            }

            is GameState.PlayMenu -> {
                PlayScreen(
                    round = state.currentRound,
                    players = state.players,
                    currentPlayerIndex = state.currentPlayerIndex,
                    onContinue = {
                        state = state.copy(gameState = GameState.Voting)
                    }
                )
            }

            is GameState.Voting -> {
                VotingScreen(
                    players = state.players,
                    onPlayerEliminated = { eliminatedPlayer ->
                        state = handlePlayerElimination(state, eliminatedPlayer)
                    }
                )
            }

            is GameState.EliminationResult -> {
                EliminationResultScreen(
                    player = gameState.player,
                    onNextRound = {
                        val activePlayers = state.players.activePlayers()
                        val nextPlayerIndex = if (activePlayers.isNotEmpty())
                            Random.nextInt(activePlayers.size) else 0
                        state = state.copy(
                            currentRound = state.currentRound + 1,
                            currentPlayerIndex = nextPlayerIndex,
                            gameState = GameState.PlayMenu
                        )
                    }
                )
            }

            is GameState.MrWhiteGuess -> {
                MrWhiteGuessScreen(
                    lastEliminated = gameState.lastEliminated,
                    scenario = gameState.scenario,
                    onGuessSubmitted = { guessedWord ->
                        state = handleMrWhiteGuess(state, gameState, guessedWord)
                    }
                )
            }

            is GameState.GameOver -> {
                GameOverScreen(
                    civiliansWon = gameState.civiliansWon,
                    lastEliminated = gameState.lastEliminated,
                    players = state.players,
                    gameWord = state.players.getCivilianWord(),
                    mrWhiteGuesses = state.mrWhiteGuesses,
                    onContinue = {
                        state = state.copy(gameState = GameState.Leaderboard)
                    }
                )
            }

            is GameState.Leaderboard -> {
                LeaderboardScreen(
                    allScores = state.allPlayersScores,
                    onBackToMenu = {
                        state = UndercoverGameState(gameState = GameState.Settings)
                    },
                    onNewGame = {
                        val reassignedPlayers = reassignRolesAndWords(state)
                        state = state.copy(
                            players = reassignedPlayers,
                            currentPlayerIndex = 0,
                            currentRound = 1,
                            gameState = GameState.PlayerSetup(0, showWord = false),
                            quickStart = true
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HandlePlayerSetup(
    gameState: GameState.PlayerSetup,
    state: UndercoverGameState,
    onStateUpdate: (UndercoverGameState) -> Unit
) {
    if (!gameState.showWord) {
        if (state.quickStart) {
            var showQuickStartDialog by remember { mutableStateOf(true) }
            val currentPlayer = state.players.getOrNull(gameState.playerIndex)

            if (currentPlayer != null && showQuickStartDialog) {
                AlertDialog(
                    onDismissRequest = { showQuickStartDialog = false },
                    title = { Text("Warning") },
                    text = {
                        Text(
                            buildAnnotatedString {
                                append("The secret word for ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(currentPlayer.name)
                                }
                                append(" will now be displayed. Make sure no one else is watching!")
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onStateUpdate(
                                state.copy(
                                    gameState = GameState.PlayerSetup(
                                        gameState.playerIndex,
                                        showWord = true
                                    )
                                )
                            )
                            showQuickStartDialog = false
                        }) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showQuickStartDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        } else {
            PlayerSetupScreen(
                playerIndex = gameState.playerIndex,
                totalPlayers = state.players.size,
                existingNames = state.players.map { it.name },
                onNameEntered = { name ->
                    val updatedPlayers = state.players.mapIndexed { index, player ->
                        if (index == gameState.playerIndex)
                            player.copy(name = name)
                        else player
                    }
                    onStateUpdate(
                        state.copy(
                            players = updatedPlayers,
                            gameState = GameState.PlayerSetup(gameState.playerIndex, true)
                        )
                    )
                }
            )
        }
    } else {
        ShowWordScreen(
            player = state.players[gameState.playerIndex],
            playerIndex = gameState.playerIndex,
            totalPlayers = state.players.size,
            settings = state.settings,
            onNext = {
                if (gameState.playerIndex < state.players.size - 1) {
                    onStateUpdate(
                        state.copy(
                            gameState = GameState.PlayerSetup(
                                gameState.playerIndex + 1,
                                false
                            )
                        )
                    )
                } else {
                    val activeCount = state.players.activePlayers().size
                    onStateUpdate(
                        state.copy(
                            currentPlayerIndex = if (activeCount > 0)
                                Random.nextInt(activeCount) else 0,
                            gameState = GameState.PlayMenu,
                            quickStart = false
                        )
                    )
                }
            }
        )
    }
}