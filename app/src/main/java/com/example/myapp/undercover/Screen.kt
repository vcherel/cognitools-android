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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.collections.set
import kotlin.random.Random

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
                        val newPlayers = generatePlayers(settings)
                        players = assignRolesAndWords(newPlayers, settings)
                        currentPlayerIndex = 0
                        currentRound = 1
                        gameState = GameState.PlayerSetup(
                            0,
                            showWord = false
                        )
                    }
                )
            }
            is GameState.PlayerSetup -> {
                if (!state.showWord) {
                    // Name entry phase
                    PlayerSetupScreen(
                        playerIndex = state.playerIndex,
                        totalPlayers = settings.playerCount,
                        onNameEntered = { name ->
                            players = players.toMutableList().apply {
                                if (state.playerIndex < size) {
                                    this[state.playerIndex] = this[state.playerIndex].copy(name = name)
                                }
                            }

                            // Move to show word for this player
                            gameState = GameState.PlayerSetup(state.playerIndex, true)
                        }
                    )
                } else {
                    // Show word phase
                    ShowWordScreen(
                        player = players[state.playerIndex],
                        playerIndex = state.playerIndex,
                        totalPlayers = players.size,
                        settings = settings,
                        onNext = {
                            if (state.playerIndex < players.size - 1) {
                                // Move to next player's name entry
                                gameState = GameState.PlayerSetup(state.playerIndex + 1, false)
                            } else {
                                // All players done, start game
                                currentPlayerIndex = Random.nextInt(players.filter { !it.isEliminated }.size)
                                gameState = GameState.RoundMenu
                            }
                        }
                    )
                }
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
    data class PlayerSetup(val playerIndex: Int, val showWord: Boolean) : GameState()
    object RoundMenu : GameState()
    object Voting : GameState()
    data class EliminationResult(val player: Player, val gameOver: Boolean) : GameState()
    data class GameOver(val civiliansWon: Boolean, val lastEliminated: Player) : GameState()
}