package com.example.myapp.undercover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class SwapDirection {
    NONE,
    DECREASE_IMPOSTOR,
    INCREASE_IMPOSTOR,
    DECREASE_MR_WHITE,
    INCREASE_MR_WHITE
}

fun validateGameSettings(
    playerCount: Int,
    impostorCount: Int,
    mrWhiteCount: Int,
    allowSwap: Boolean = false,
    swapDirection: SwapDirection = SwapDirection.NONE
): Triple<Int, Int, Int> {
    // 3 players minimum
    val validPlayerCount = playerCount.coerceAtLeast(3)

    // At least one impostor or Mr. White must exist
    val maxPossibleImpostors = (validPlayerCount - 1) / 2

    // First, ensure impostors are within absolute bounds
    var validImpostorCount = impostorCount.coerceIn(0, maxPossibleImpostors)

    // Calculate max Mr. White given current impostors
    val maxPossibleMrWhite = validPlayerCount - 2 * validImpostorCount - 1
    var validMrWhiteCount = mrWhiteCount.coerceIn(0, maxPossibleMrWhite.coerceAtLeast(0))

    // Handle swap scenarios
    if (allowSwap) {
        when (swapDirection) {
            SwapDirection.DECREASE_IMPOSTOR -> {
                // If trying to decrease impostor below 0, but it's already 0
                if (impostorCount <= 0 && validImpostorCount == 0 && validMrWhiteCount < maxPossibleMrWhite) {
                    // Increase Mr. White instead
                    validMrWhiteCount = (validMrWhiteCount + 1).coerceAtMost(maxPossibleMrWhite)
                }
            }
            SwapDirection.INCREASE_IMPOSTOR -> {
                // If trying to increase impostor but at max, and Mr. White > 0
                if (impostorCount >= maxPossibleImpostors && validMrWhiteCount > 0) {
                    // Decrease Mr. White to make room
                    validMrWhiteCount = (validMrWhiteCount - 1).coerceAtLeast(0)
                    val newMax = (validPlayerCount - 2 * validImpostorCount - 1).coerceAtLeast(0)
                    if (validMrWhiteCount < newMax) {
                        validImpostorCount = (validImpostorCount + 1).coerceAtMost(maxPossibleImpostors)
                    }
                }
            }
            SwapDirection.DECREASE_MR_WHITE -> {
                // If trying to decrease Mr. White below 0, but it's already 0
                if (mrWhiteCount <= 0 && validMrWhiteCount == 0 && validImpostorCount < maxPossibleImpostors) {
                    // Increase Impostor instead
                    validImpostorCount = (validImpostorCount + 1).coerceAtMost(maxPossibleImpostors)
                }
            }
            SwapDirection.INCREASE_MR_WHITE -> {
                // If trying to increase Mr. White but at max, and Impostor > 0
                if (mrWhiteCount >= maxPossibleMrWhite && validImpostorCount > 0) {
                    // Decrease Impostor to make room
                    validImpostorCount = (validImpostorCount - 1).coerceAtLeast(0)
                    val newMax = (validPlayerCount - 2 * validImpostorCount - 1).coerceAtLeast(0)
                    validMrWhiteCount = (validMrWhiteCount + 1).coerceAtMost(newMax)
                }
            }
            SwapDirection.NONE -> { /* No swap */ }
        }
    }

    // Ensure at least one impostor or Mr. White exists
    if (validImpostorCount == 0 && validMrWhiteCount == 0) {
        validImpostorCount = 1
    }

    return Triple(validPlayerCount, validImpostorCount, validMrWhiteCount)
}

@Composable
fun SettingsScreen(
    state: UndercoverGameState,
    playAgain: Boolean = false,
    onSettingsChange: (UndercoverGameState) -> Unit,
    onStart: () -> Unit
) {
    // Calculate current valid ranges
    val maxImpostors = (state.players.size - 1) / 2
    val (_, _, maxMrWhiteFromSwap) = validateGameSettings(
        playerCount = state.players.size,
        impostorCount = state.settings.impostorCount,
        mrWhiteCount = state.settings.mrWhiteCount + 1,
        allowSwap = true,
        swapDirection = SwapDirection.INCREASE_MR_WHITE
    )
    val maxMrWhite = maxMrWhiteFromSwap

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Player count
        NumberSetting(
            label = "Number of Players",
            value = state.players.size,
            onValueChange = { newPlayerCount ->
                val (_, validImpostors, validMrWhite) = validateGameSettings(
                    playerCount = newPlayerCount,
                    impostorCount = state.settings.impostorCount,
                    mrWhiteCount = state.settings.mrWhiteCount
                )

                val updatedPlayers = when {
                    newPlayerCount > state.players.size -> {
                        state.players + List(newPlayerCount - state.players.size) { index ->
                            Player(
                                name = "Player ${state.players.size + index + 1}",
                                role = PlayerRole.CIVILIAN,
                                word = ""
                            )
                        }
                    }
                    newPlayerCount < state.players.size -> {
                        state.players.take(newPlayerCount)
                    }
                    else -> state.players
                }

                val updatedState = state.copy(
                    settings = state.settings.copy(
                        impostorCount = validImpostors,
                        mrWhiteCount = validMrWhite
                    ),
                    players = updatedPlayers
                )

                onSettingsChange(updatedState)
            },
            min = 3,
            max = 20,
            enabled = !playAgain
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
                checked = state.settings.randomComposition,
                onCheckedChange = { isChecked ->
                    val updatedState = state.copy(
                        settings = state.settings.copy(randomComposition = isChecked)
                    )
                    onSettingsChange(updatedState)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        NumberSetting(
            label = "Number of Impostors",
            value = state.settings.impostorCount,
            onValueChange = { newImpostorCount ->
                val swapDirection = when {
                    newImpostorCount < state.settings.impostorCount -> SwapDirection.DECREASE_IMPOSTOR
                    newImpostorCount > state.settings.impostorCount -> SwapDirection.INCREASE_IMPOSTOR
                    else -> SwapDirection.NONE
                }

                val (_, validImpostors, validMrWhite) = validateGameSettings(
                    playerCount = state.players.size,
                    impostorCount = newImpostorCount,
                    mrWhiteCount = state.settings.mrWhiteCount,
                    allowSwap = true,
                    swapDirection = swapDirection
                )

                val updatedState = state.copy(
                    settings = state.settings.copy(
                        impostorCount = validImpostors,
                        mrWhiteCount = validMrWhite
                    )
                )
                onSettingsChange(updatedState)
            },
            min = 0,
            max = maxImpostors,
            enabled = !state.settings.randomComposition
        )

        Spacer(modifier = Modifier.height(16.dp))

        NumberSetting(
            label = "Number of Mr. Whites",
            value = state.settings.mrWhiteCount,
            onValueChange = { newMrWhiteCount ->
                val swapDirection = when {
                    newMrWhiteCount < state.settings.mrWhiteCount -> SwapDirection.DECREASE_MR_WHITE
                    newMrWhiteCount > state.settings.mrWhiteCount -> SwapDirection.INCREASE_MR_WHITE
                    else -> SwapDirection.NONE
                }

                val (_, validImpostors, validMrWhite) = validateGameSettings(
                    playerCount = state.players.size,
                    impostorCount = state.settings.impostorCount,
                    mrWhiteCount = newMrWhiteCount,
                    allowSwap = true,
                    swapDirection = swapDirection
                )

                val updatedState = state.copy(
                    settings = state.settings.copy(
                        impostorCount = validImpostors,
                        mrWhiteCount = validMrWhite
                    )
                )

                onSettingsChange(updatedState)
            },
            min = 0,
            max = maxMrWhite,
            enabled = !state.settings.randomComposition
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
                checked = state.settings.impostorsKnowRole,
                onCheckedChange = { isChecked ->
                    val updatedState = state.copy(
                        settings = state.settings.copy(impostorsKnowRole = isChecked)
                    )
                    onSettingsChange(updatedState)
                }
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