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
                if (!gameState.showWord) {
                    if (state.quickStart) {
                        // Instead of skipping directly, show a confirmation dialog
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
                                        state = state.copy(
                                            gameState = GameState.PlayerSetup(gameState.playerIndex, showWord = true)
                                        )
                                        showQuickStartDialog = false
                                    }) {
                                        Text("OK")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showQuickStartDialog = false
                                    }) {
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
                                    if (index == gameState.playerIndex) player.copy(name = name) else player
                                }
                                state = state.copy(
                                    players = updatedPlayers,
                                    gameState = GameState.PlayerSetup(gameState.playerIndex, true)
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
                                state = state.copy(
                                    gameState = GameState.PlayerSetup(gameState.playerIndex + 1, false)
                                )
                            } else {
                                val activeCount = state.players.activePlayers().size
                                state = state.copy(
                                    currentPlayerIndex = if (activeCount > 0) Random.nextInt(activeCount) else 0,
                                    gameState = GameState.PlayMenu,
                                    quickStart = false
                                )
                            }
                        }
                    )
                }
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
                        val updatedPlayers = state.players.eliminate(eliminatedPlayer.name)

                        // Check if Mr. White should guess by seeing the number of active players
                        val shouldMrWhiteGuess = updatedPlayers.shouldMrWhiteGuess()

                        when {
                            // If Mr. White was eliminated, they get to guess
                            eliminatedPlayer.role == PlayerRole.MR_WHITE -> {
                                val correctWord = state.players.first { it.role == PlayerRole.CIVILIAN }.word
                                state = state.copy(
                                    players = updatedPlayers,
                                    gameState = GameState.MrWhiteGuess(
                                        player = eliminatedPlayer,
                                        correctWord = correctWord,
                                        lastEliminated = eliminatedPlayer,
                                        mrWhiteGuesses = emptyList(),
                                        scenario = MrWhiteScenario.EliminatedMrWhite(eliminated = eliminatedPlayer)
                                    )
                                )
                            }
                            // PRIORITY: If Mr. White should guess, let them guess before determining winners
                            shouldMrWhiteGuess -> {
                                val active = updatedPlayers.activePlayers()
                                val mrWhiteToGuess = active.first { it.role == PlayerRole.MR_WHITE }
                                val correctWord = state.players.first { it.role == PlayerRole.CIVILIAN }.word
                                val activeCivilians = active.filter { it.role == PlayerRole.CIVILIAN }
                                val activeImpostors = active.filter { it.role == PlayerRole.IMPOSTOR }

                                val scenario = if (activeCivilians.isEmpty() && activeImpostors.isEmpty()) {
                                    // Case 1: Only Mr. Whites left
                                    MrWhiteScenario.OnlyMrWhitesLeft(
                                        activeMrWhites = active.filter { it.role == PlayerRole.MR_WHITE },
                                        currentGuesser = mrWhiteToGuess
                                    )

                                } else {
                                    // Case 2: Only Mr. White and one Civilian left → Final Two
                                    MrWhiteScenario.FinalTwo(
                                        mrWhite = mrWhiteToGuess,
                                        opponent = activeCivilians.first()
                                    )
                                }

                                state = state.copy(
                                    players = updatedPlayers,
                                    gameState = GameState.MrWhiteGuess(
                                        player = mrWhiteToGuess,
                                        correctWord = correctWord,
                                        lastEliminated = eliminatedPlayer,
                                        mrWhiteGuesses = emptyList(),
                                        scenario = scenario
                                    )
                                )
                            }

                            // Only check win conditions if Mr. White doesn't need to guess
                            else -> {
                                val winCheck = updatedPlayers.checkWinCondition()
                                when (winCheck) {
                                    WinCondition.CiviliansWin -> {
                                        val updatedScores = updatedPlayers.awardCivilianPoints(state.allPlayersScores)
                                        state = state.copy(
                                            players = updatedPlayers,
                                            allPlayersScores = updatedScores,
                                            gameState = GameState.GameOver(true, eliminatedPlayer)
                                        )
                                    }
                                    WinCondition.ImpostorsWin -> {
                                        val updatedScores = updatedPlayers.awardImpostorPoints(state.allPlayersScores)
                                        state = state.copy(
                                            players = updatedPlayers,
                                            allPlayersScores = updatedScores,
                                            gameState = GameState.GameOver(false, eliminatedPlayer)
                                        )
                                    }
                                    WinCondition.Continue -> {
                                        state = state.copy(
                                            players = updatedPlayers,
                                            gameState = GameState.EliminationResult(eliminatedPlayer, false)
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
            is GameState.EliminationResult -> {
                EliminationResultScreen(
                    player = gameState.player,
                    onNextRound = {
                        val activePlayers = state.players.activePlayers()
                        val nextPlayerIndex = if (activePlayers.isNotEmpty()) Random.nextInt(activePlayers.size) else 0
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
                    scenario = gameState.scenario,
                    onGuessSubmitted = { guessedWord ->
                        val correctWord = gameState.correctWord
                        val updatedMrWhiteGuesses = gameState.mrWhiteGuesses + guessedWord

                        val guessCorrect = guessedWord.equals(correctWord, ignoreCase = true)
                        val wasEliminated = gameState.player.isEliminated

                        if (guessCorrect) {
                            // Mr. White wins immediately
                            val updatedScores = state.allPlayersScores.updateScore(
                                gameState.player.name,
                                ScoreValues.MR_WHITE_WIN
                            )
                            state = state.copy(
                                allPlayersScores = updatedScores,
                                gameState = GameState.GameOver(
                                    civiliansWon = false,
                                    lastEliminated = gameState.player,
                                    mrWhiteGuesses = updatedMrWhiteGuesses
                                )
                            )
                        } else {
                            // Update players if Mr. White wasn't already eliminated
                            val updatedPlayers = if (wasEliminated) state.players
                            else state.players.eliminate(gameState.player.name)

                            // Check if any remaining Mr. Whites need to guess
                            val remainingMrWhites = updatedPlayers.activePlayers()
                                .filter { it.role == PlayerRole.MR_WHITE }

                            if (remainingMrWhites.isNotEmpty() && updatedPlayers.shouldMrWhiteGuess()) {
                                val nextMrWhite = remainingMrWhites.first()
                                val scenario = if (
                                    updatedPlayers.activePlayers().none { it.role == PlayerRole.CIVILIAN }
                                    && updatedPlayers.activePlayers().none { it.role == PlayerRole.IMPOSTOR }
                                ) {
                                    MrWhiteScenario.OnlyMrWhitesLeft(
                                        activeMrWhites = remainingMrWhites,
                                        currentGuesser = nextMrWhite
                                    )
                                } else {
                                    val opponent = updatedPlayers.activePlayers()
                                        .first { it.role == PlayerRole.CIVILIAN }
                                    MrWhiteScenario.FinalTwo(nextMrWhite, opponent)
                                }

                                state = state.copy(
                                    players = updatedPlayers,
                                    gameState = GameState.MrWhiteGuess(
                                        player = nextMrWhite,
                                        correctWord = correctWord,
                                        lastEliminated = gameState.lastEliminated,
                                        mrWhiteGuesses = updatedMrWhiteGuesses,
                                        scenario = scenario
                                    )
                                )
                            } else {
                                // No Mr. Whites left to guess, check win conditions
                                when (updatedPlayers.checkWinCondition()) {
                                    WinCondition.CiviliansWin -> {
                                        val updatedScores = updatedPlayers.awardCivilianPoints(state.allPlayersScores)
                                        state = state.copy(
                                            players = updatedPlayers,
                                            allPlayersScores = updatedScores,
                                            gameState = GameState.GameOver(
                                                civiliansWon = true,
                                                lastEliminated = gameState.player,
                                                mrWhiteGuesses = updatedMrWhiteGuesses
                                            )
                                        )
                                    }
                                    WinCondition.ImpostorsWin -> {
                                        val updatedScores = updatedPlayers.awardImpostorPoints(state.allPlayersScores)
                                        state = state.copy(
                                            players = updatedPlayers,
                                            allPlayersScores = updatedScores,
                                            gameState = GameState.GameOver(
                                                civiliansWon = false,
                                                lastEliminated = gameState.player,
                                                mrWhiteGuesses = updatedMrWhiteGuesses
                                            )
                                        )
                                    }
                                    WinCondition.Continue -> {
                                        val activePlayers = updatedPlayers.activePlayers()
                                        val nextPlayerIndex = if (activePlayers.isNotEmpty()) Random.nextInt(activePlayers.size) else 0
                                        state = state.copy(
                                            players = updatedPlayers,
                                            currentRound = state.currentRound + 1,
                                            currentPlayerIndex = nextPlayerIndex,
                                            gameState = GameState.PlayMenu
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
            is GameState.GameOver -> {
                GameOverScreen(
                    civiliansWon = gameState.civiliansWon,
                    lastEliminated = gameState.lastEliminated,
                    players = state.players,
                    gameWord = state.players.first { it.role == PlayerRole.CIVILIAN }.word,
                    mrWhiteGuesses = gameState.mrWhiteGuesses,
                    onContinue = {
                        state = state.copy(gameState = GameState.Leaderboard)
                    }
                )
            }
            is GameState.Leaderboard -> {
                LeaderboardScreen(
                    allScores = state.allPlayersScores,
                    onBackToMenu = { state = UndercoverGameState(gameState = GameState.Settings) },
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