package com.example.myapp.undercover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog
import kotlin.random.Random

const val MAX_NAME_LENGTH = 30

fun generateAndAssignPlayers(state: UndercoverGameState): List<Player> {
    val players = (0 until state.players.size).map { i -> Player() }.toMutableList()

    val wordPair = wordPairs.random()
    val (civilianWord, impostorWord) = if (Random.nextBoolean()) {
        wordPair.first to wordPair.second
    } else {
        wordPair.second to wordPair.first
    }

    val indices = players.indices.shuffled().toMutableList()

    var impostorCount = state.settings.impostorCount
    val mrWhiteCount = if (state.settings.randomComposition) {
        val maxMrWhite = state.players.size - 2
        var count = 0
        var chance = 0.5
        while (count < maxMrWhite && Random.nextDouble() < chance) {
            count++
            chance *= 0.5
        }
        count
    } else state.settings.mrWhiteCount

    if (state.settings.randomComposition) {
        val maxImpostors = ((state.players.size - mrWhiteCount) / 2) - 1
        impostorCount = if (maxImpostors >= 1) Random.nextInt(1, maxImpostors + 1) else 0

        // Guarantee at least one non-civilian
        if (impostorCount == 0 && mrWhiteCount == 0) { impostorCount = 1 }
    }

    var nextIndex = 0

    repeat(mrWhiteCount) {
        val idx = indices[nextIndex++]
        players[idx] = players[idx].copy(role = PlayerRole.MR_WHITE, word = "")
    }

    repeat(impostorCount) {
        val idx = indices[nextIndex++]
        players[idx] = players[idx].copy(role = PlayerRole.IMPOSTOR, word = impostorWord)
    }

    for (i in nextIndex until indices.size) {
        val idx = indices[i]
        players[idx] = players[idx].copy(role = PlayerRole.CIVILIAN, word = civilianWord)
    }

    return players
}

fun reassignRolesAndWords(state: UndercoverGameState): List<Player> {
    // When replaying game
    val newPlayers = generateAndAssignPlayers(state)
    // Keep the original names
    return newPlayers.mapIndexed { index, p ->
        p.copy(name = state.players.getOrNull(index)?.name ?: p.name)
    }
}

@Composable
fun HandlePlayerSetup(
    gameState: GameState.PlayerSetup,
    state: UndercoverGameState,
    onStateUpdate: (UndercoverGameState) -> Unit
) {
    val currentPlayer = state.players.getOrNull(gameState.playerIndex)

    if (currentPlayer == null) return

    // If player name is empty, ask for name entry
    if (currentPlayer.name.isEmpty()) {
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
    } else {
        // Player has name, check if they've seen their word
        if (!gameState.showWord) {
            // Show warning dialog before revealing word
            var showQuickStartDialog by remember { mutableStateOf(true) }

            if (showQuickStartDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("ATTENTION") },
                    text = {
                        Text(
                            buildAnnotatedString {
                                append("Le mot secret du joueur ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(currentPlayer.name)
                                }
                                append(" va être affiché, cache toi des autres zouaves !")
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            // Update state to show word
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
                            Text("Ok")
                        }
                    }
                )
            }
        } else {
            // Show their secret word / role
            ShowWordScreen(
                player = currentPlayer,
                playerIndex = gameState.playerIndex,
                totalPlayers = state.players.size,
                settings = state.settings,
                onNext = {
                    if (gameState.playerIndex < state.players.size - 1) {
                        // Move to next player
                        onStateUpdate(
                            state.copy(
                                gameState = GameState.PlayerSetup(
                                    gameState.playerIndex + 1,
                                    false
                                )
                            )
                        )
                    } else {
                        // All players setup, start game
                        val activeCount = state.players.activePlayers().size
                        onStateUpdate(
                            state.copy(
                                currentPlayerIndex = if (activeCount > 0)
                                    Random.nextInt(activeCount) else 0,
                                gameState = GameState.PlayMenu
                            )
                        )
                    }
                }
            )
        }
    }
}


@Composable
fun PlayerSetupScreen(
    playerIndex: Int,
    totalPlayers: Int,
    existingNames: List<String>,
    onNameEntered: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var showConfirmationDialog by remember { mutableStateOf(false) }

    val isNameValid by remember {
        derivedStateOf {
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
                    existingNames.none { it.equals(name, ignoreCase = true) }
        }
    }

    val errorMessage by remember {
        derivedStateOf {
            when {
                name.isBlank() -> null
                name.length > MAX_NAME_LENGTH -> "C'est trop loooong (max $MAX_NAME_LENGTH)"
                existingNames.any { it.equals(name, ignoreCase = true) } -> "C'est déjà pris copieur va"
                else -> null
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Joueur ${playerIndex + 1}/$totalPlayers",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Toi s'appeler comment ?", fontSize = FONT_SIZE)
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (isNameValid) showConfirmationDialog = true
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )

            Spacer(modifier = Modifier.width(8.dp))

            MyButton(
                text = "Ok",
                enabled = isNameValid,
                onClick = { if (isNameValid) showConfirmationDialog = true },
                modifier = Modifier.height(55.dp).width(80.dp),
                fontSize = 18.sp
            )
        }
    }

    if (showConfirmationDialog) {
        ShowAlertDialog(
            show = true,
            onDismiss = { showConfirmationDialog = false },
            textContent = {
                Text("Cache toi tu vas découvrir ton rôle jeune troubadour", fontSize = FONT_SIZE)
            },
            confirmText = "Fais péter",
            cancelText = "NOON",
            onConfirm = {
                onNameEntered(name)
                name = ""
                showConfirmationDialog = false
            },
            onCancel = { showConfirmationDialog = false }
        )
    }
}


@Composable
fun ShowWordScreen(
    player: Player,
    playerIndex: Int,
    totalPlayers: Int,
    settings: GameSettings,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(player.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        // Display role and word depending on role/settings
        if (player.role == PlayerRole.MR_WHITE) {
            Text("Tu es M. White!", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "T'as pas de mot chacal",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        } else if (player.role == PlayerRole.IMPOSTOR && settings.impostorsKnowRole) {
            Text("Tu es Undercover!", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Ton mot:", style = MaterialTheme.typography.bodyLarge)
            Text(player.word, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        } else {
            Text("Ton mot:", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(player.word, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Next button: move to next player or start game
        Button(onClick = onNext) {
            Text(if (playerIndex < totalPlayers - 1) "Au suivant" else "Jouer !")
        }
    }
}