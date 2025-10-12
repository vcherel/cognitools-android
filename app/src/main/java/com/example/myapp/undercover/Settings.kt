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

fun validateGameSettings(
    playerCount: Int,
    impostorCount: Int,
    mrWhiteCount: Int
): Triple<Int, Int, Int> {
    // Constraint: Civilians > Impostors
    // This means: playerCount > 2 * impostorCount + mrWhiteCount

    val validPlayerCount = playerCount.coerceIn(3, 20)

    // At least one impostor or Mr. White must exist
    // Maximum impostors when mrWhite = 0: (playerCount - 1) / 2
    val maxPossibleImpostors = (validPlayerCount - 1) / 2

    // First, ensure impostors are within absolute bounds
    var validImpostorCount = impostorCount.coerceIn(0, maxPossibleImpostors)

    // Calculate max Mr. White given current impostors
    val maxPossibleMrWhite = validPlayerCount - 2 * validImpostorCount - 1
    val validMrWhiteCount = mrWhiteCount.coerceIn(0, maxPossibleMrWhite.coerceAtLeast(0))

    // Ensure at least one impostor or Mr. White exists
    if (validImpostorCount == 0 && validMrWhiteCount == 0) {
        validImpostorCount = 1
    }

    return Triple(validPlayerCount, validImpostorCount, validMrWhiteCount)
}

@Composable
fun SettingsScreen(
    settings: GameSettings,
    onSettingsChange: (GameSettings) -> Unit,
    onStart: () -> Unit
) {
    // Calculate current valid ranges
    val maxImpostors = (settings.playerCount - 1) / 2
    val maxMrWhite = (settings.playerCount - 2 * settings.impostorCount - 1).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Player count
        NumberSetting(
            label = "Number of Players",
            value = settings.playerCount,
            onValueChange = { newPlayerCount ->
                val (validPlayers, validImpostors, validMrWhite) = validateGameSettings(
                    playerCount = newPlayerCount,
                    impostorCount = settings.impostorCount,
                    mrWhiteCount = settings.mrWhiteCount
                )
                onSettingsChange(
                    settings.copy(
                        playerCount = validPlayers,
                        impostorCount = validImpostors,
                        mrWhiteCount = validMrWhite
                    )
                )
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

        NumberSetting(
            label = "Number of Impostors",
            value = settings.impostorCount,
            onValueChange = { newImpostorCount ->
                val (_, validImpostors, validMrWhite) = validateGameSettings(
                    playerCount = settings.playerCount,
                    impostorCount = newImpostorCount,
                    mrWhiteCount = settings.mrWhiteCount
                )
                onSettingsChange(
                    settings.copy(
                        impostorCount = validImpostors,
                        mrWhiteCount = validMrWhite
                    )
                )
            },
            min = 0,
            max = maxImpostors,
            enabled = !settings.randomComposition
        )

        Spacer(modifier = Modifier.height(16.dp))

        NumberSetting(
            label = "Number of Mr. Whites",
            value = settings.mrWhiteCount,
            onValueChange = { newMrWhiteCount ->
                val (_, validImpostors, validMrWhite) = validateGameSettings(
                    playerCount = settings.playerCount,
                    impostorCount = settings.impostorCount,
                    mrWhiteCount = newMrWhiteCount
                )
                onSettingsChange(
                    settings.copy(
                        impostorCount = validImpostors,
                        mrWhiteCount = validMrWhite
                    )
                )
            },
            min = 0,
            max = maxMrWhite,
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