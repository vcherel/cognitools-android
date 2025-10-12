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
        // Player count
        // Player count
        NumberSetting(
            label = "Number of Players",
            value = settings.playerCount,
            onValueChange = { newPlayerCount ->
                val coercedCount = newPlayerCount.coerceIn(3, 20)
                // Ensure: Civilians > Impostors, meaning playerCount > 2 * impostorCount + mrWhiteCount
                val maxImpostors = (coercedCount - settings.mrWhiteCount - 1) / 2
                val adjustedImpostorCount = settings.impostorCount.coerceIn(1, maxImpostors.coerceAtLeast(1))

                // Recalculate max Mr. White based on adjusted impostor count
                val maxMrWhite = coercedCount - 2 * adjustedImpostorCount - 1
                val adjustedMrWhite = settings.mrWhiteCount.coerceIn(0, maxMrWhite.coerceAtLeast(0))

                onSettingsChange(
                    settings.copy(
                        playerCount = coercedCount,
                        impostorCount = adjustedImpostorCount,
                        mrWhiteCount = adjustedMrWhite
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

        // Maximum impostors: ensure at least 2 civilians remain
        val maxImpostors = ((settings.playerCount - settings.mrWhiteCount - 1) / 2).coerceAtLeast(0)

        NumberSetting(
            label = "Number of Impostors",
            value = settings.impostorCount,
            onValueChange = { newImpostorCount ->
                val coercedImpostorCount = newImpostorCount.coerceIn(0, maxImpostors)
                val minMrWhite = if (coercedImpostorCount == 0) 1 else 0
                val newMaxMrWhite = (settings.playerCount - 2 * coercedImpostorCount - 1).coerceAtLeast(minMrWhite)
                val adjustedMrWhite = settings.mrWhiteCount.coerceIn(minMrWhite, newMaxMrWhite)

                onSettingsChange(
                    settings.copy(
                        impostorCount = coercedImpostorCount,
                        mrWhiteCount = adjustedMrWhite
                    )
                )
            },
            min = 0,
            max = maxImpostors,
            enabled = !settings.randomComposition
        )

        Spacer(modifier = Modifier.height(16.dp))

        val minMrWhite = if (settings.impostorCount == 0) 1 else 0
        val maxMrWhite = (settings.playerCount - 2 * settings.impostorCount - 1).coerceAtLeast(minMrWhite)

        NumberSetting(
            label = "Number of Mr. Whites",
            value = settings.mrWhiteCount,
            onValueChange = { newMrWhiteCount ->
                val coercedMrWhite = newMrWhiteCount.coerceIn(0, maxMrWhite)
                var adjustedImpostorCount = settings.impostorCount

                // If Mr. White goes to 0 and impostors are 0, increase impostors to 1
                if (coercedMrWhite == 0 && adjustedImpostorCount == 0) {
                    adjustedImpostorCount = 1
                }

                onSettingsChange(
                    settings.copy(
                        mrWhiteCount = coercedMrWhite,
                        impostorCount = adjustedImpostorCount
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