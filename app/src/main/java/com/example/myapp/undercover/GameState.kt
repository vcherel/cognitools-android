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
import kotlin.random.Random

@Composable
fun UndercoverScreen(onBack: () -> Unit) {
    var state by remember { mutableStateOf(UndercoverGameState()) }

    BackHandler {
        if (state.gameState is GameState.Settings) {
            onBack()
        } else {
            state = state.copy(gameState = GameState.Settings)
        }
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
                    settings = state.settings,
                    onSettingsChange = { state = state.copy(settings = it) },
                    onStart = {
                        val newPlayers = generatePlayers(state.settings)
                        val assignedPlayers = assignRolesAndWords(newPlayers, state.settings)
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
                    PlayerSetupScreen(
                        playerIndex = gameState.playerIndex,
                        totalPlayers = state.settings.playerCount,
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
                                    gameState = GameState.RoundMenu
                                )
                            }
                        }
                    )
                }
            }
            is GameState.RoundMenu -> {
                RoundMenuScreen(
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
                        if (eliminatedPlayer.role == PlayerRole.MR_WHITE) {
                            val correctWord = state.players.first { it.role == PlayerRole.CIVILIAN }.word
                            state = state.copy(
                                gameState = GameState.MrWhiteGuess(eliminatedPlayer, correctWord)
                            )
                        } else {
                            val updatedPlayers = state.players.eliminate(eliminatedPlayer.name)

                            when (updatedPlayers.checkWinCondition()) {
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
                            gameState = GameState.RoundMenu
                        )
                    }
                )
            }
            is GameState.MrWhiteGuess -> {
                MrWhiteGuessScreen(
                    player = gameState.player,
                    onGuessSubmitted = { guessedWord ->
                        val updatedPlayers = state.players.eliminate(gameState.player.name)

                        if (guessedWord.equals(gameState.correctWord, ignoreCase = true)) {
                            val updatedScores = state.allPlayersScores.updateScore(
                                gameState.player.name,
                                ScoreValues.MR_WHITE_WIN
                            )
                            state = state.copy(
                                players = updatedPlayers,
                                allPlayersScores = updatedScores,
                                gameState = GameState.GameOver(false, gameState.player)
                            )
                        } else {
                            val updatedScores = updatedPlayers.awardCivilianPoints(state.allPlayersScores)
                            state = state.copy(
                                players = updatedPlayers,
                                allPlayersScores = updatedScores,
                                gameState = GameState.GameOver(true, gameState.player)
                            )
                        }
                    }
                )
            }
            is GameState.GameOver -> {
                GameOverScreen(
                    civiliansWon = gameState.civiliansWon,
                    lastEliminated = gameState.lastEliminated,
                    players = state.players,
                    allScores = state.allPlayersScores,
                    gameWord = state.players.first { it.role == PlayerRole.CIVILIAN }.word,
                    onNewGame = {
                        state = UndercoverGameState()
                    }
                )
            }
        }
    }
}

sealed class GameState {
    object Settings : GameState()
    data class PlayerSetup(val playerIndex: Int, val showWord: Boolean) : GameState()
    object RoundMenu : GameState()
    object Voting : GameState()
    data class EliminationResult(val player: Player, val gameOver: Boolean) : GameState()
    data class MrWhiteGuess(val player: Player, val correctWord: String) : GameState()
    data class GameOver(val civiliansWon: Boolean, val lastEliminated: Player) : GameState()
}