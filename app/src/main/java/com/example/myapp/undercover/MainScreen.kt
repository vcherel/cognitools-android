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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapp.ShowAlertDialog
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

    ShowAlertDialog(
        show = showExitDialog,
        onDismiss = { showExitDialog = false },
        title = "Confirmation quittage",
        textContent = { Text("T'es sûr ? Tous sera perdu") },
        confirmText = "Oui",
        cancelText = "Non",
        onConfirm = {
            state = state.copy(gameState = GameState.Settings())
            showExitDialog = false },
        onCancel = {
            showExitDialog = false
        }
    )

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
                    state = state.copy(gameState = GameState.Settings())
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
                    playAgain = gameState.playAgain,
                    onSettingsChange = { updatedState ->
                        state = updatedState
                    },
                    onStart = {
                        if (!gameState.playAgain) {
                            val assignedPlayers = generateAndAssignPlayers(state)
                            state = state.copy(
                                players = assignedPlayers,
                                currentPlayerIndex = 0,
                                currentRound = 1,
                                gameState = GameState.PlayerSetup()
                            )
                        }
                        else {
                            state = state.copy(
                                gameState = GameState.Leaderboard
                            )
                        }
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
                        state = UndercoverGameState(gameState = GameState.Settings())
                    },
                    onNewGame = {
                        val reassignedPlayers = reassignRolesAndWords(state)
                        state = state.copy(
                            players = reassignedPlayers,
                            currentPlayerIndex = 0,
                            currentRound = 1,
                            gameState = GameState.PlayerSetup()
                        )
                    },
                    onSettings = {
                        state = state.copy(
                            gameState = GameState.Settings(playAgain = true)
                        )
                    }
                )
            }
        }
    }
}