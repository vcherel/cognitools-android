package com.example.myapp.undercover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.MyButton
import com.example.myapp.MySwitch
import com.example.myapp.ShowAlertDialog

enum class SwapDirection {
    NONE,
    DECREASE_IMPOSTOR,
    INCREASE_IMPOSTOR,
    DECREASE_MR_WHITE,
    INCREASE_MR_WHITE
}

/** The impostor and Mr. White counts that fit [playerCount]; [swapDirection] says which one gives way to the other. */
fun validateGameSettings(
    playerCount: Int,
    impostorCount: Int,
    mrWhiteCount: Int,
    swapDirection: SwapDirection = SwapDirection.NONE
): Pair<Int, Int> {
    val validPlayerCount = playerCount.coerceAtLeast(3)
    val maxPossibleImpostors = (validPlayerCount - 1) / 2
    var validImpostorCount = impostorCount.coerceIn(0, maxPossibleImpostors)
    val maxPossibleMrWhite = validPlayerCount - 2 * validImpostorCount - 1
    var validMrWhiteCount = mrWhiteCount.coerceIn(0, maxPossibleMrWhite.coerceAtLeast(0))

    when (swapDirection) {
        SwapDirection.DECREASE_IMPOSTOR -> {
            if (impostorCount <= 0 && validImpostorCount == 0 && validMrWhiteCount < maxPossibleMrWhite) {
                validMrWhiteCount = (validMrWhiteCount + 1).coerceAtMost(maxPossibleMrWhite)
            }
        }
        SwapDirection.INCREASE_IMPOSTOR -> {
            if (impostorCount >= maxPossibleImpostors && validMrWhiteCount > 0) {
                validMrWhiteCount = (validMrWhiteCount - 1).coerceAtLeast(0)
                val newMax = (validPlayerCount - 2 * validImpostorCount - 1).coerceAtLeast(0)
                if (validMrWhiteCount < newMax) {
                    validImpostorCount = (validImpostorCount + 1).coerceAtMost(maxPossibleImpostors)
                }
            }
        }
        SwapDirection.DECREASE_MR_WHITE -> {
            if (mrWhiteCount <= 0 && validMrWhiteCount == 0 && validImpostorCount < maxPossibleImpostors) {
                validImpostorCount = (validImpostorCount + 1).coerceAtMost(maxPossibleImpostors)
            }
        }
        SwapDirection.INCREASE_MR_WHITE -> {
            if (mrWhiteCount >= maxPossibleMrWhite && validImpostorCount > 0) {
                validImpostorCount = (validImpostorCount - 1).coerceAtLeast(0)
                val newMax = (validPlayerCount - 2 * validImpostorCount - 1).coerceAtLeast(0)
                validMrWhiteCount = (validMrWhiteCount + 1).coerceAtMost(newMax)
            }
        }
        SwapDirection.NONE -> Unit
    }

    if (validImpostorCount == 0 && validMrWhiteCount == 0) {
        validImpostorCount = 1
    }

    return validImpostorCount to validMrWhiteCount
}

val FONT_SIZE = 22.sp

@Composable
fun SettingsScreen(
    state: UndercoverGameState,
    playAgain: Boolean = false,
    onSettingsChange: (UndercoverGameState) -> Unit,
    onStart: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val maxImpostors = (state.players.size - 1) / 2
    val (_, maxMrWhite) = validateGameSettings(
        playerCount = state.players.size,
        impostorCount = state.settings.impostorCount,
        mrWhiteCount = state.settings.mrWhiteCount + 1,
        swapDirection = SwapDirection.INCREASE_MR_WHITE
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NumberSetting(
            label = "Nombre de joueurs",
            value = state.players.size,
            min = 3,
            max = 20,
            enabled = true,
            biggerFont = true,
            onValueChange = { newPlayerCount ->
                if (newPlayerCount < state.players.size && state.players.any { it.name.isNotEmpty() }) {
                    showDeleteDialog = true
                    return@NumberSetting
                }

                val (validImpostors, validMrWhite) =validateGameSettings(
                    playerCount = newPlayerCount,
                    impostorCount = state.settings.impostorCount,
                    mrWhiteCount = state.settings.mrWhiteCount
                )

                val updatedPlayers = when {
                    newPlayerCount > state.players.size -> state.players + List(newPlayerCount - state.players.size) { Player() }
                    newPlayerCount < state.players.size -> state.players.take(newPlayerCount)
                    else -> state.players
                }

                onSettingsChange(
                    state.copy(
                        players = updatedPlayers,
                        settings = state.settings.copy(
                            impostorCount = validImpostors,
                            mrWhiteCount = validMrWhite
                        )
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider(thickness = 1.dp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "Rôles aléatoires",
                fontSize = FONT_SIZE,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 25.dp)
            )
            MySwitch(
                checked = state.settings.randomComposition,
                onToggle = { checked ->
                    onSettingsChange(state.copy(settings = state.settings.copy(randomComposition = checked)))
                },
                modifier = Modifier.scale(0.85f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        NumberSetting(
            label = "Nombre d'Undercover",
            value = state.settings.impostorCount,
            min = 0,
            max = maxImpostors,
            enabled = !state.settings.randomComposition,
            onValueChange = { newImpostorCount ->
                val swapDirection = when {
                    newImpostorCount < state.settings.impostorCount -> SwapDirection.DECREASE_IMPOSTOR
                    newImpostorCount > state.settings.impostorCount -> SwapDirection.INCREASE_IMPOSTOR
                    else -> SwapDirection.NONE
                }

                val (validImpostors, validMrWhite) =validateGameSettings(
                    playerCount = state.players.size,
                    impostorCount = newImpostorCount,
                    mrWhiteCount = state.settings.mrWhiteCount,
                    swapDirection = swapDirection
                )

                onSettingsChange(state.copy(settings = state.settings.copy(
                    impostorCount = validImpostors,
                    mrWhiteCount = validMrWhite
                )))
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        NumberSetting(
            label = "Nombre de M. Whites",
            value = state.settings.mrWhiteCount,
            min = 0,
            max = maxMrWhite,
            enabled = !state.settings.randomComposition,
            onValueChange = { newMrWhiteCount ->
                val swapDirection = when {
                    newMrWhiteCount < state.settings.mrWhiteCount -> SwapDirection.DECREASE_MR_WHITE
                    newMrWhiteCount > state.settings.mrWhiteCount -> SwapDirection.INCREASE_MR_WHITE
                    else -> SwapDirection.NONE
                }

                val (validImpostors, validMrWhite) =validateGameSettings(
                    playerCount = state.players.size,
                    impostorCount = state.settings.impostorCount,
                    mrWhiteCount = newMrWhiteCount,
                    swapDirection = swapDirection
                )

                onSettingsChange(state.copy(settings = state.settings.copy(
                    impostorCount = validImpostors,
                    mrWhiteCount = validMrWhite
                )))
            }
        )

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider(thickness = 1.dp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "Undercover savent",
                fontSize = FONT_SIZE,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 25.dp)
            )
            MySwitch(
                checked = state.settings.impostorsKnowRole,
                onToggle = { checked ->
                    onSettingsChange(state.copy(settings = state.settings.copy(impostorsKnowRole = checked)))
                },
                modifier = Modifier.scale(0.85f)
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        MyButton(
            text = if (playAgain) "Classement" else "JOUER",
            onClick = onStart,
            modifier = Modifier
                .height(150.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )
    }

    if (showDeleteDialog) ShowAlertDialog(
        onDismiss = { showDeleteDialog = false },
        title = "Choisissez qui dégager",
        textContent = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                itemsIndexed(state.players) { index, player ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                val updatedPlayers = state.players.filterIndexed { i, _ -> i != index }
                                val (validImpostors, validMrWhite) =validateGameSettings(
                                    playerCount = updatedPlayers.size,
                                    impostorCount = state.settings.impostorCount,
                                    mrWhiteCount = state.settings.mrWhiteCount
                                )
                                onSettingsChange(
                                    state.copy(
                                        players = updatedPlayers,
                                        settings = state.settings.copy(
                                            impostorCount = validImpostors,
                                            mrWhiteCount = validMrWhite
                                        )
                                    )
                                )
                                showDeleteDialog = false
                            },
                        elevation = CardDefaults.cardElevation(4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = player.name.ifEmpty { "Nouveau joueur ${index + 1}" },
                            modifier = Modifier.padding(16.dp),
                            fontSize = FONT_SIZE
                        )
                    }
                }
            }
        },
        confirmText = "Ok",
        cancelText = "Annuler",
        onConfirm = { showDeleteDialog = false },
        onCancel = { showDeleteDialog = false }
    )

}

@Composable
fun NumberSetting(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    enabled: Boolean = true,
    biggerFont: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = if (biggerFont) 30.sp else FONT_SIZE, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fontSize = 30.sp
            MyButton(
                text = "-",
                onClick = { onValueChange(value - 1) },
                enabled = enabled && value > min,
                fontSize = fontSize,
                secondary = true,
                modifier = Modifier.size(62.dp)
            )
            Text(
                text = if (enabled) value.toString() else "-",
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 35.dp)
            )
            MyButton(
                text = "+",
                onClick = { onValueChange(value + 1) },
                enabled = enabled && value < max,
                fontSize = fontSize,
                secondary = true,
                modifier = Modifier.size(62.dp)
            )
        }
    }
}
