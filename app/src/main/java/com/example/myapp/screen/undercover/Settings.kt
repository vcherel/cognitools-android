package com.example.myapp.screen.undercover

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
        NumberSetting(
            label = "Number of Players",
            value = settings.playerCount,
            onValueChange = {
                val newCount = it.coerceIn(3, 20)
                onSettingsChange(settings.copy(playerCount = newCount))
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

        // This ensures at least half the players are civilians
        val maxImpostors = ((settings.playerCount - settings.mrWhiteCount - 1) / 2).coerceAtLeast(1)

        NumberSetting(
            label = "Number of Impostors",
            value = settings.impostorCount,
            onValueChange = {
                val newCount = it.coerceIn(1, maxImpostors)
                onSettingsChange(settings.copy(impostorCount = newCount))
            },
            min = 1,
            max = maxImpostors,
            enabled = !settings.randomComposition
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Mr. White count (disabled if random)
        NumberSetting(
            label = "Number of Mr. Whites",
            value = settings.mrWhiteCount,
            onValueChange = {
                onSettingsChange(settings.copy(mrWhiteCount = it.coerceIn(0, 1)))
            },
            min = 0,
            max = 1,
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